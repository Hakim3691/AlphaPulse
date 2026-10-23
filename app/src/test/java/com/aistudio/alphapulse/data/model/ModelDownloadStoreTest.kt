package com.aistudio.alphapulse.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Download state machine: idle -> downloading (0..1 progress) -> ready,
 * with the READY set persisted across a simulated process restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelDownloadStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("alphapulse_model_downloads", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ModelDownloadStore.init(context)
    }

    @Test
    fun `download streams to ready and persists across restart`() = runTest {
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("chronos").status)
        assertTrue(ModelDownloadStore.readyModelIds().isEmpty())

        ModelDownloadStore.download("chronos")

        val state = ModelDownloadStore.stateOf("chronos")
        assertEquals(ModelLoadStatus.READY, state.status)
        assertEquals(1f, state.progress)
        assertEquals(listOf("chronos"), ModelDownloadStore.readyModelIds())

        // Simulated process restart: READY is restored from disk.
        ModelDownloadStore.init(context)
        assertEquals(ModelLoadStatus.READY, ModelDownloadStore.stateOf("chronos").status)
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("timesfm").status)
    }

    @Test
    fun `both models reach ready in canonical order`() = runTest {
        ModelDownloadStore.download("timesfm")
        ModelDownloadStore.download("chronos")

        assertEquals(
            listOf("chronos", "timesfm"),
            ModelDownloadStore.readyModelIds()
        )
    }

    @Test
    fun `downloading an already mounted model is a no-op`() = runTest {
        ModelDownloadStore.download("chronos")
        ModelDownloadStore.download("chronos")

        assertEquals(ModelLoadStatus.READY, ModelDownloadStore.stateOf("chronos").status)
        assertEquals(1f, ModelDownloadStore.stateOf("chronos").progress)
    }

    @Test
    fun `unknown model ids are ignored`() = runTest {
        ModelDownloadStore.download("not-a-model")

        assertTrue(ModelDownloadStore.readyModelIds().isEmpty())
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("not-a-model").status)
    }
}
