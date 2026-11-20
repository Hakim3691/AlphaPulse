package com.aistudio.alphapulse.build

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing identity is part of the update contract.
 *
 * Why these assertions exist
 * --------------------------
 * Android refuses to install an APK whose certificate differs from the installed
 * one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the only recovery is uninstall +
 * reinstall, which wipes the ledger, the catalogue and the downloaded TSFM
 * weights. The pipeline used to restore both keystores from an Actions cache and,
 * when that cache was cold, silently generate a brand-new certificate for the run.
 * Nothing failed; the next install simply could not update in place.
 *
 * So the debug identity is committed (a debug key is not a secret by convention),
 * its fingerprint is pinned in the repository, and the workflow compares the
 * keystore it is about to sign with to that pin and fails loudly when they differ.
 * The release key stays a secret and is never committed.
 *
 * Spec Ref: C-36, §45 Performance Requirements, §47 Model Download Architecture
 */
class SigningIdentityBddSpec {

    private val workflow: String by lazy {
        repoFile(".github/workflows/build-apk.yml").readText()
    }
    private val pins: Map<String, String> by lazy { readPins() }

    @Test
    fun `given the committed debug keystore when decoded then its certificate matches the pinned fingerprint`() {
        val encoded = repoFile("debug.keystore.base64")
        assertTrue(
            "The debug keystore must be committed as base64 so CI and a local build " +
                "share one identity",
            encoded.isFile && encoded.length() > 0
        )

        val decoded = Base64.getMimeDecoder().decode(encoded.readText())
        val temporary = File.createTempFile("debug-keystore", ".jks").apply {
            deleteOnExit()
            writeBytes(decoded)
        }

        val certificate = loadCertificate(temporary)
        val fingerprint = sha256(certificate)

        assertEquals(
            "The committed keystore must be the certificate the pin describes; " +
                "regenerating it breaks every in-place update",
            pins["debug"],
            fingerprint
        )
    }

    @Test
    fun `given the pinned fingerprints when read then the debug identity is pinned and no release key is committed`() {
        assertTrue(
            "The committed debug certificate must be pinned",
            pins["debug"]?.length == 64
        )
        pins["release"]?.let {
            assertTrue("A release fingerprint, when present, must be a full SHA-256", it.length == 64)
        }

        // A release private key in a public repository would let anyone sign an
        // update the installed app accepts. Only the debug key may be committed.
        val forbidden = listOf(
            "my-upload-key.jks",
            "my-upload-key.jks.base64",
            "release.keystore"
        )
        forbidden.forEach { name ->
            assertFalse(
                "$name must never be committed: the release key is a secret",
                isTracked(name)
            )
        }

        assertTrue(
            "The debug keystore must be tracked, or CI and a laptop diverge again",
            isTracked("debug.keystore.base64")
        )
    }

    @Test
    fun `given the workflow when it signs an APK then it verifies the certificate against the pin first`() {
        assertTrue(
            "The workflow must restore the same keystores on every run",
            workflow.contains("key: alphapulse-signing-keystores-v1")
        )
        assertTrue(
            "A cache key scoped to the run would hand every run a fresh identity",
            !workflow.contains("key: alphapulse-signing-keystores-\${{")
        )
        assertTrue(
            "The workflow must compare the keystore it signs with to the pinned fingerprints",
            workflow.contains("signing-fingerprints.txt")
        )
        assertTrue(
            "A changed certificate must fail the build, not warn",
            Regex("""::error::[^\n]*[Ss]igning""").containsMatchIn(workflow)
        )
        assertTrue(
            "The release identity must be checkable without committing its key",
            workflow.contains("RELEASE_CERT_SHA256")
        )
    }

    private fun readPins(): Map<String, String> {
        val file = repoFile("signing-fingerprints.txt")
        assertTrue("signing-fingerprints.txt must exist", file.isFile)
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (name, value) = line.split("=", limit = 2)
                name.trim() to value.trim().replace(":", "").lowercase()
            }
    }

    private fun loadCertificate(keystore: File): java.security.cert.Certificate {
        val store = runCatching {
            KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, "android".toCharArray()) }
            }
        }.getOrElse {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                keystore.inputStream().use { load(it, "android".toCharArray()) }
            }
        }
        val alias = store.getCertificate("androiddebugkey")
        if (alias != null) return alias
        val aliases = store.aliases().toList()
        for (candidate in aliases) {
            val certificate = store.getCertificate(candidate)
            if (certificate != null) return certificate
        }
        throw AssertionError("The committed keystore carries no certificate")
    }

    private fun sha256(certificate: java.security.cert.Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    /** Locates a repository file from whichever directory tests run in. */
    private fun repoFile(relativePath: String): File = File(repoRoot(), relativePath)

    /** True when git tracks [relativePath]; a local, gitignored key is not committed. */
    private fun isTracked(relativePath: String): Boolean {
        val output = runCatching {
            val process = ProcessBuilder("git", "ls-files", "--", relativePath)
                .directory(repoRoot())
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            text
        }.getOrDefault("")
        return output.isNotBlank()
    }

    private fun repoRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
