package com.aistudio.alphapulse.data.model

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Behaviour of the real artifact installer, specified as Given/When/Then.
 * A local HTTP server stands in for the artifact host; the production code
 * under test takes the same HTTPS streaming path it uses on device.
 */
class ModelArtifactInstallerBddSpec {

    private lateinit var server: MockWebServer
    private lateinit var workDir: File
    private lateinit var installer: ModelArtifactInstaller

    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val targetName = "model_quantized.onnx"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        workDir = Files.createTempDirectory("alphapulse-artifacts").toFile()
        installer = ModelArtifactInstaller(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
        workDir.deleteRecursively()
    }

    @Test
    fun `given a published artifact when installed then it is stored verbatim with monotonic progress`() =
        runTest {
            // Given a published artifact served with its exact size and digest
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
            val source = source()
            val target = File(workDir, targetName)
            val progress = mutableListOf<Float>()

            // When the installer streams it to local storage
            val installed = installer.install(source, target) { progress.add(it) }

            // Then the exact bytes are on disk, hashed, and progress ran 0 -> 1
            assertEquals(target, installed)
            assertTrue("artifact must exist", target.isFile)
            assertArrayEquals(payload, installed.readBytes())
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue("progress must never go backwards", progress.zipWithNext().all { (a, b) -> b >= a })
            assertTrue("progress must include intermediate values", progress.any { it > 0f && it < 1f })
        }

    @Test
    fun `given an artifact host error when installed then nothing is mounted`() = runTest {
        // Given the host rejects the request
        server.enqueue(MockResponse().setResponseCode(503))
        val target = File(workDir, targetName)

        // When the download is attempted
        try {
            installer.install(source(), target)
            fail("Expected an IOException for HTTP 503")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("503"))
        }

        // Then no artifact and no partial file remain
        assertFalse(target.exists())
        assertFalse(File(workDir, "$targetName.part").exists())
    }

    @Test
    fun `given a corrupted artifact when installed then the checksum rejects it`() = runTest {
        // Given the host serves bytes that no longer match the pinned digest
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val target = File(workDir, targetName)
        val tampered = source(sha256 = sha256Of("different-artifact".toByteArray()))

        // When the download completes
        try {
            installer.install(tampered, target)
            fail("Expected the checksum check to reject the artifact")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("checksum"))
        }

        // Then the corrupt artifact is discarded
        assertFalse(target.exists())
        assertFalse(File(workDir, "$targetName.part").exists())
    }

    @Test
    fun `given a truncated artifact when installed then the size check rejects it`() = runTest {
        // Given the release expects more bytes than the host delivers
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val target = File(workDir, targetName)
        val shortRelease = source(sizeBytes = payload.size + 4_096L, sha256 = sha256Of(payload))

        // When the transfer ends early
        try {
            installer.install(shortRelease, target)
            fail("Expected the size check to reject the artifact")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("size mismatch", ignoreCase = true))
        }

        // Then the partial download is removed
        assertFalse(target.exists())
        assertFalse(File(workDir, "$targetName.part").exists())
    }

    private fun source(
        sizeBytes: Long = payload.size.toLong(),
        sha256: String = sha256Of(payload)
    ) = ModelArtifactSource(
        modelId = "chronos",
        displayName = "Chronos-Bolt Tiny",
        microcopy = "Zero-shot forecaster",
        repoId = "test/chronos-onnx",
        filePath = "onnx/$targetName",
        sizeBytes = sizeBytes,
        sha256 = sha256,
        baseUrl = server.url("/").toString().trimEnd('/')
    )

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertTrue("downloaded bytes differ from the published artifact", expected.contentEquals(actual))
}
