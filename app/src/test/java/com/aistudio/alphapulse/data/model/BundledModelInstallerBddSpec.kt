package com.aistudio.alphapulse.data.model

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The bundled-weights path, specified Given/When/Then (spec §30, §47, §52).
 *
 * The model dock normally downloads a checkpoint. When the APK already carries
 * one, [BundledModelInstaller] is what turns those bytes into the file the engine
 * can memory-map - and if its name, size or digest ever drifts from what the
 * build task packaged, the app would either never find the weights or mount bytes
 * it should reject. Two halves are covered:
 *
 *  1. **Disk/build half:** the Gradle task and [ModelArtifacts.NEEDLE3] must name
 *     the same URL, size and digest, and the asset on disk (when the build has
 *     produced it) must be exactly those bytes. A missing asset is a visible
 *     skip, never a silent pass, because the file is a build-time input.
 *  2. **Resolution/verification half:** resolution order, absence, empty files and
 *     wrong digests, driven through [BundledAssetSource] - the same logic the
 *     device runs, with a fake asset manager.
 *
 * No Robolectric or device is used: the installer never touches an
 * `AssetManager` outside its production adapter.
 */
class BundledModelInstallerBddSpec {

    private lateinit var workspace: File

    /** The artifact the build task bundles; the only one shipped in the APK. */
    private val needle = ModelArtifacts.NEEDLE3

    @Before
    fun setUp() {
        workspace = Files.createTempDirectory("bundled-weights").toFile()
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    // ------------------------------------------------ the build produces it

    @Test
    fun `given the bundling task when read then it downloads the artifact the installer pins`() {
        val gradle = repoFile("app/build.gradle.kts").readText()

        assertTrue(
            "The task the workflow calls must exist",
            gradle.contains("tasks.register(\"bundleNeedle3Weights\")")
        )
        assertTrue(
            "The task must write the asset path the installer resolves: " +
                "src/main/assets/${needle.fileName}",
            gradle.contains("src/main/assets/${needle.fileName}")
        )
        assertTrue(
            "The task must fetch the pinned download URL: ${needle.downloadUrl}",
            gradle.contains("\"${needle.downloadUrl}\"")
        )
        assertTrue(
            "The task must verify the pinned size (${needle.sizeBytes})",
            gradle.contains("\"${needle.sizeBytes}\"")
        )
        assertTrue(
            "The task must verify the pinned SHA-256 (${needle.sha256})",
            gradle.contains(needle.sha256)
        )
    }

    @Test
    fun `given the bundled weights on disk when checked then they are the pinned artifact`() {
        val asset = repoFile("app/src/main/assets/${needle.fileName}")

        // The weights are a build-time input, produced by `:app:bundleNeedle3Weights`
        // and deliberately not committed. A plain test run may not have them; that is
        // reported as a skip with the command to run, never as a pass.
        assumeTrue(
            "No bundled weights at ${asset.absolutePath}. The asset is produced by " +
                "`:app:bundleNeedle3Weights`; run it to exercise this check.",
            asset.isFile
        )

        assertTrue("The bundled weights must not be empty", asset.length() > 0)
        assertEquals(
            "The asset on disk must be the size the app pins",
            needle.sizeBytes,
            asset.length()
        )
        assertEquals(
            "The asset on disk must be the digest the app verifies before mounting",
            needle.sha256,
            sha256(asset)
        )
    }

    // ------------------------------------------------ resolution and mounting

    @Test
    fun `given an apk without the asset when resolved then absence is reported and nothing is written`() {
        val assets = FakeAssetSource()

        assertNull(
            "An absent asset has no path to report",
            BundledModelInstaller.bundledAssetPath(needle, assets)
        )

        val target = destination()
        assertFalse(
            "Absence must be the actionable failure that keeps the download fallback",
            BundledModelInstaller.install(assets, needle, target)
        )
        assertFalse("A failed mount must not create the model file", target.exists())
        assertFalse("A failed mount must not leave staging behind", staging().exists())
    }

    @Test
    fun `given the asset at the apk root when resolved then that path is reported`() {
        val assets = FakeAssetSource(mapOf(needle.fileName to bytes()))

        assertEquals(
            "The task writes assets/${needle.fileName}, which is the root candidate",
            needle.fileName,
            BundledModelInstaller.bundledAssetPath(needle, assets)
        )
    }

    @Test
    fun `given the asset under the model directory when resolved then the specific path wins`() {
        val nested = "models/${needle.modelId}/${needle.fileName}"
        val assets = FakeAssetSource(
            mapOf(
                needle.fileName to bytes(),
                nested to bytes()
            )
        )

        assertEquals(
            "The model-specific location must take precedence over the shared root",
            nested,
            BundledModelInstaller.bundledAssetPath(needle, assets)
        )
    }

    @Test
    fun `given a matching bundled asset when installed then it is copied to the engine path and verified`() {
        val payload = bytes()
        val source = needle.copy(sizeBytes = payload.size.toLong(), sha256 = sha256Of(payload))
        val assets = FakeAssetSource(mapOf(needle.fileName to payload))
        val target = destination()

        assertTrue("A verified asset must mount", BundledModelInstaller.install(assets, source, target))
        assertArrayEquals("The engine must receive the bundled bytes", payload, target.readBytes())
        assertFalse("Staging must be gone after a successful mount", staging().exists())
    }

    @Test
    fun `given an empty asset when installed then it is rejected rather than mounted`() {
        val assets = FakeAssetSource(mapOf(needle.fileName to ByteArray(0)))
        val target = destination()

        assertFalse(
            "Zero-byte weights can never satisfy the pinned size",
            BundledModelInstaller.install(assets, needle, target)
        )
        assertFalse("Nothing may be mounted from an empty asset", target.exists())
        assertFalse("The rejected copy must not be left behind", staging().exists())
    }

    @Test
    fun `given an asset of the right length with the wrong digest when installed then it is rejected`() {
        // Same length as the pinned artifact, different bytes: only the digest can
        // catch this, which is the case a size check alone would miss.
        val payload = bytes()
        val tampered = needle.copy(
            sizeBytes = payload.size.toLong(),
            sha256 = sha256Of("the-published-bytes".toByteArray())
        )
        val assets = FakeAssetSource(mapOf(needle.fileName to payload))
        val target = destination()

        assertFalse(
            "A tampered asset must not mount even at the pinned length",
            BundledModelInstaller.install(assets, tampered, target)
        )
        assertFalse("Nothing may be mounted from a tampered asset", target.exists())
        assertFalse("The rejected copy must not be left behind", staging().exists())
    }

    // ---------------------------------------------------------------- helpers

    /** A small payload whose digest is computed, so verification is real. */
    private fun bytes(): ByteArray = "needle3-fixture".toByteArray()

    private fun destination(): File =
        File(workspace, "models/${needle.modelId}/${needle.fileName}")

    private fun staging(): File =
        File(destination().parentFile, "${needle.fileName}.part")

    /** Locates a repository file from whichever directory the tests run in. */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.isFile) {
                    throw AssertionError("Missing repository file: $relativePath")
                }
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** An APK asset bundle as a map, so resolution needs no Android runtime. */
    private class FakeAssetSource(
        private val files: Map<String, ByteArray> = emptyMap()
    ) : BundledAssetSource {
        override fun open(path: String): InputStream? =
            files[path]?.let { ByteArrayInputStream(it) }
    }
}
