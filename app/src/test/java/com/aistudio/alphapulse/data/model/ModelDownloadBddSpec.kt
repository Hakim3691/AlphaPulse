package com.aistudio.alphapulse.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.domain.ensemble.EnsembleWeighting
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Feature-level behaviour of the model dock, specified as Given/When/Then:
 * downloads are real HTTP transfers, mount state survives restarts only when
 * the artifact is actually on disk, and mounted models expand the ensemble.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelDownloadBddSpec {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var sources: List<ModelArtifactSource>

    private val chronosBytes = ByteArray(150_000) { (it % 241).toByte() }
    private val timesfmBytes = ByteArray(90_000) { (it % 239).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        File(context.getExternalFilesDir(null), "models").deleteRecursively()

        server = MockWebServer()
        server.start()
        sources = listOf(
            source("chronos", "Chronos-Bolt Tiny", chronosBytes),
            source("timesfm", "TimesFM 2.5 200M", timesfmBytes)
        )
        ModelDownloadStore.sources = sources
        ModelDownloadStore.client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
        prefs().edit().clear().commit()
        File(context.getExternalFilesDir(null), "models").deleteRecursively()
        ModelDownloadStore.sources = ModelArtifacts.ALL
        ModelDownloadStore.client = OkHttpClient()
    }

    @Test
    fun `given an unmounted model when the download completes then it is ready and stored`() = runTest {
        // Given a model that is not downloaded
        ModelDownloadStore.init(context)
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("chronos").status)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(chronosBytes)))

        // When the user starts its download
        ModelDownloadStore.download("chronos")

        // Then it is READY, the artifact is on disk, and progress completed
        val state = ModelDownloadStore.stateOf("chronos")
        assertEquals(ModelLoadStatus.READY, state.status)
        assertEquals(1f, state.progress)
        assertEquals(listOf("chronos"), ModelDownloadStore.readyModelIds())
        val installed = ModelDownloadStore.installedFile("chronos")!!
        assertTrue(installed.isFile)
        assertTrue(installed.readBytes().contentEquals(chronosBytes))
    }

    @Test
    fun `given a missing release when the download fails then the model stays unmounted`() = runTest {
        // Given a host that does not serve the artifact
        ModelDownloadStore.init(context)
        server.enqueue(MockResponse().setResponseCode(404))

        // When the download is attempted
        try {
            ModelDownloadStore.download("chronos")
            fail("Expected the download to fail")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("404"))
        }

        // Then nothing is mounted and no file is left behind
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("chronos").status)
        assertTrue(ModelDownloadStore.readyModelIds().isEmpty())
        assertFalse(ModelDownloadStore.installedFile("chronos")!!.exists())
    }

    @Test
    fun `given a mounted model when the app restarts then it is restored from disk`() = runTest {
        // Given a completed download
        ModelDownloadStore.init(context)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(chronosBytes)))
        ModelDownloadStore.download("chronos")

        // When the process restarts and the store re-initialises
        ModelDownloadStore.init(context)

        // Then the installed model is still mounted
        assertEquals(ModelLoadStatus.READY, ModelDownloadStore.stateOf("chronos").status)
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("timesfm").status)
    }

    @Test
    fun `given a mounted model whose artifact vanished when the app restarts then it is unmounted`() =
        runTest {
            // Given a mounted model whose file was wiped
            ModelDownloadStore.init(context)
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(chronosBytes)))
            ModelDownloadStore.download("chronos")
            assertTrue(ModelDownloadStore.installedFile("chronos")!!.delete())

            // When the app restarts
            ModelDownloadStore.init(context)

            // Then the stale mount is dropped instead of being reported READY
            assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("chronos").status)
            assertTrue(ModelDownloadStore.readyModelIds().isEmpty())
        }

    @Test
    fun `given both artifacts mounted then the ensemble expands to five models`() = runTest {
        // Given both published artifacts downloaded
        ModelDownloadStore.init(context)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(chronosBytes)))
        ModelDownloadStore.download("chronos")
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(timesfmBytes)))
        ModelDownloadStore.download("timesfm")

        // When the ensemble resolves its active members
        val ready = ModelDownloadStore.readyModelIds()

        // Then it is a penta-factor ensemble with a normalised weight vector
        assertEquals(listOf("chronos", "timesfm"), ready)
        assertEquals(5, EnsembleWeighting.activeModelCount(ready))
        assertEquals(
            1.0,
            EnsembleWeighting.weightVector(com.aistudio.alphapulse.domain.quant.ModelState(), ready)
                .sumOf { it.weight },
            1e-9
        )
    }

    private fun prefs() =
        context.getSharedPreferences("alphapulse_model_downloads", Context.MODE_PRIVATE)

    private fun source(modelId: String, displayName: String, payload: ByteArray) =
        ModelArtifactSource(
            modelId = modelId,
            displayName = displayName,
            microcopy = "test model",
            repoId = "test/$modelId-onnx",
            filePath = "onnx/model_quantized.onnx",
            sizeBytes = payload.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) },
            baseUrl = server.url("/").toString().trimEnd('/')
        )
}
