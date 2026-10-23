package com.aistudio.alphapulse.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.io.File

/** Lifecycle of a mountable TSFM artefact. */
enum class ModelLoadStatus { NOT_DOWNLOADED, DOWNLOADING, READY }

data class ModelDownloadState(
    val modelId: String,
    val status: ModelLoadStatus = ModelLoadStatus.NOT_DOWNLOADED,
    /** 0f..1f transfer progress, meaningful while [status] is DOWNLOADING. */
    val progress: Float = 0f
)

/**
 * Downloads the published TSFM artifacts and owns their mount state.
 *
 * Transfers are real HTTPS downloads of the pinned Hugging Face releases (see
 * [ModelArtifacts]); [ModelArtifactInstaller] verifies size and SHA-256 before
 * a model is promoted to READY. READY is persisted and re-validated against the
 * installed file on every [init], so a wiped cache or an interrupted install can
 * never leave a model marked as mounted.
 */
object ModelDownloadStore {

    private const val PREFS_NAME = "alphapulse_model_downloads"
    private const val KEY_READY = "ready_models"

    /** Test seams: specs point these at a local HTTP server and temp storage. */
    internal var sources: List<ModelArtifactSource> = ModelArtifacts.ALL
    internal var client: OkHttpClient = OkHttpClient()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val _states = MutableStateFlow(initialStates(emptySet()))
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    /** Restore persisted mount state, keeping only artifacts actually on disk. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val preferences = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = preferences

        val persisted = preferences.getStringSet(KEY_READY, emptySet()).orEmpty()
        val installed = persisted.filterTo(mutableSetOf()) { installedFile(it)?.isNonEmpty() == true }
        if (installed != persisted) {
            preferences.edit().putStringSet(KEY_READY, installed).apply()
        }
        _states.value = initialStates(installed)
    }

    fun stateOf(modelId: String): ModelDownloadState =
        _states.value[modelId] ?: ModelDownloadState(modelId)

    fun isReady(modelId: String): Boolean = stateOf(modelId).status == ModelLoadStatus.READY

    fun readyModelIds(): List<String> = sources.map { it.modelId }.filter { isReady(it) }

    /**
     * Download and install [modelId] from its published artifact, then mount it.
     * No-op when the model is already READY or already streaming. Failures leave
     * the model unmounted and rethrow so callers can surface the reason.
     */
    suspend fun download(modelId: String) {
        val source = sources.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) } ?: return
        if (stateOf(source.modelId).status != ModelLoadStatus.NOT_DOWNLOADED) return

        val target = installedFile(source.modelId)
            ?: throw IllegalStateException("ModelDownloadStore.init must be called before downloading")

        update(source.modelId, ModelLoadStatus.DOWNLOADING, 0f)
        try {
            ModelArtifactInstaller(client).install(source, target) { progress ->
                update(source.modelId, ModelLoadStatus.DOWNLOADING, progress)
            }
        } catch (e: CancellationException) {
            update(source.modelId, ModelLoadStatus.NOT_DOWNLOADED, 0f)
            throw e
        } catch (e: Exception) {
            update(source.modelId, ModelLoadStatus.NOT_DOWNLOADED, 0f)
            throw e
        }

        markReady(source.modelId)
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

    /** Destination file for [modelId], or null before [init] or for unknown ids. */
    internal fun installedFile(modelId: String): File? {
        val ctx = appContext ?: return null
        val source = sources.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) } ?: return null
        return File(ModelFileLocator.getModelDirectory(source.modelId, ctx), source.fileName)
    }

    private fun File.isNonEmpty(): Boolean = isFile && length() > 0

    private fun initialStates(ready: Set<String>): Map<String, ModelDownloadState> =
        sources.associate { source ->
            source.modelId to ModelDownloadState(
                modelId = source.modelId,
                status = if (source.modelId in ready) ModelLoadStatus.READY else ModelLoadStatus.NOT_DOWNLOADED
            )
        }
}
