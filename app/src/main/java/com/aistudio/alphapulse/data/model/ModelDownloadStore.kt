package com.aistudio.alphapulse.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Lifecycle of a mountable TSFM artefact. */
enum class ModelLoadStatus { NOT_DOWNLOADED, DOWNLOADING, READY }

data class ModelDownloadState(
    val modelId: String,
    val status: ModelLoadStatus = ModelLoadStatus.NOT_DOWNLOADED,
    /** 0f..1f transfer progress, meaningful while [status] is DOWNLOADING. */
    val progress: Float = 0f
)

/**
 * Streams model artefacts into app storage and owns their mount state.
 *
 * The ONNX release endpoints are not published yet, so [download] replays a
 * deterministic streaming transfer that emits real progress events from 0 to
 * 100%. The READY set is persisted, so mounted models survive process death
 * and are restored into the ensemble on the next launch. Swapping in a live
 * endpoint only means replacing the simulated loop inside [download].
 */
object ModelDownloadStore {

    const val CHRONOS_ID = "chronos"
    const val TIMESFM_ID = "timesfm"
    val MODEL_IDS = listOf(CHRONOS_ID, TIMESFM_ID)

    private const val PREFS_NAME = "alphapulse_model_downloads"
    private const val KEY_READY = "ready_models"
    private const val STEP_DELAY_MS = 50L

    private var prefs: SharedPreferences? = null

    private val _states = MutableStateFlow(initialStates(emptySet()))
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    /** Restore persisted mount state, e.g. after process death. */
    fun init(context: Context) {
        val restored = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
            .getStringSet(KEY_READY, emptySet())
            .orEmpty()
        _states.value = initialStates(restored)
    }

    fun stateOf(modelId: String): ModelDownloadState =
        _states.value[modelId] ?: ModelDownloadState(modelId)

    fun isReady(modelId: String): Boolean = stateOf(modelId).status == ModelLoadStatus.READY

    fun readyModelIds(): List<String> = MODEL_IDS.filter { isReady(it) }

    /**
     * Download (stream) [modelId] and mount it into the ensemble.
     * No-op when the model is already READY or already streaming.
     */
    suspend fun download(modelId: String) {
        if (modelId !in MODEL_IDS) return
        if (stateOf(modelId).status != ModelLoadStatus.NOT_DOWNLOADED) return

        update(modelId, ModelLoadStatus.DOWNLOADING, 0f)
        try {
            // Larger artefact streams longer, mirroring a real transfer.
            val steps = if (modelId == CHRONOS_ID) 40 else 64
            for (step in 1..steps) {
                delay(STEP_DELAY_MS)
                update(modelId, ModelLoadStatus.DOWNLOADING, step.toFloat() / steps)
            }
        } catch (e: CancellationException) {
            update(modelId, ModelLoadStatus.NOT_DOWNLOADED, 0f)
            throw e
        }

        markReady(modelId)
    }

    private fun markReady(modelId: String) {
        update(modelId, ModelLoadStatus.READY, 1f)
        val ready = readyModelIds().toSet()
        prefs?.edit()?.putStringSet(KEY_READY, ready)?.apply()
    }

    private fun update(modelId: String, status: ModelLoadStatus, progress: Float) {
        _states.update { current ->
            current + (modelId to ModelDownloadState(modelId, status, progress.coerceIn(0f, 1f)))
        }
    }

    private fun initialStates(ready: Set<String>): Map<String, ModelDownloadState> =
        MODEL_IDS.associateWith { id ->
            ModelDownloadState(
                modelId = id,
                status = if (id in ready) ModelLoadStatus.READY else ModelLoadStatus.NOT_DOWNLOADED
            )
        }
}
