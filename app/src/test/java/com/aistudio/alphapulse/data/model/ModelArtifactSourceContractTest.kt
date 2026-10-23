package com.aistudio.alphapulse.data.model

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Contract check between the pinned [ModelArtifacts] manifest and the publisher.
 *
 * Skipped in normal builds (no network in unit tests) and enabled in CI with
 * `VERIFY_ARTIFACT_NETWORK=true`, so a renamed, deleted or republished artifact
 * is caught before a release ships a download that cannot verify on device.
 */
class ModelArtifactSourceContractTest {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    @Test
    fun `given the pinned manifest when checked against the publisher then artifacts resolve with the declared size`() {
        assumeTrue(
            "Set VERIFY_ARTIFACT_NETWORK=true to run the live artifact contract check",
            System.getProperty("verifyArtifactNetwork") == "true"
        )

        ModelArtifacts.ALL.forEach { source ->
            val request = Request.Builder().url(source.downloadUrl).head().build()
            client.newCall(request).execute().use { response ->
                assertEquals(
                    "${source.displayName} (${source.downloadUrl}) must resolve",
                    200,
                    response.code
                )
                val publishedSize = response.header("Content-Length")?.toLongOrNull()
                assertEquals(
                    "${source.displayName} published size drifted from the pinned manifest",
                    source.sizeBytes,
                    publishedSize
                )
                assertTrue(
                    "${source.displayName} must pin a SHA-256 digest",
                    source.sha256.matches(Regex("[0-9a-f]{64}"))
                )
            }
        }
    }

    @Test
    fun `given the published Chronos artifact when installed then the real bytes verify`() = runTest {
        assumeTrue(
            "Set VERIFY_ARTIFACT_NETWORK=true to run the live artifact contract check",
            System.getProperty("verifyArtifactNetwork") == "true"
        )

        val workDir = Files.createTempDirectory("alphapulse-contract").toFile()
        try {
            val source = ModelArtifacts.CHRONOS
            val installed = ModelArtifactInstaller().install(source, File(workDir, source.fileName))

            assertEquals(source.sizeBytes, installed.length())
        } finally {
            workDir.deleteRecursively()
        }
    }
}
