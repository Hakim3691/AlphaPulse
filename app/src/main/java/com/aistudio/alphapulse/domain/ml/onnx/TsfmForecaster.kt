package com.aistudio.alphapulse.domain.ml.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/** Executes mounted TSFM artifacts. */
interface TsfmForecaster {

    /** Load sessions for [modelIds] and release sessions for anything else. */
    suspend fun syncMountedModels(modelIds: List<String>)

    /**
     * Run the mounted model on [closes] (oldest first) and return its quantile
     * forecast. Null when the model is not mounted or inference fails.
     */
    suspend fun forecast(modelId: String, closes: DoubleArray, steps: Int): TsfmForecast?
}

/**
 * On-device ONNX Runtime host for the published TSFM graphs.
 *
 * Sessions are opened once per mounted artifact and closed when the model is
 * unmounted. Every failure path returns null so the ensemble simply excludes a
 * model rather than inventing a signal for it.
 */
class OnnxTsfmForecaster(
    private val artifactProvider: (String) -> File? = { ModelDownloadStore.installedFile(it) }
) : TsfmForecaster {

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession>()
    private val lock = Any()

    override suspend fun syncMountedModels(modelIds: List<String>) = withContext(Dispatchers.IO) {
        val wanted = modelIds.toSet()
        synchronized(lock) {
            val stale = sessions.keys.filter { it !in wanted }
            stale.forEach { sessions.remove(it)?.close() }
        }

        for (modelId in modelIds) {
            if (synchronized(lock) { sessions.containsKey(modelId) }) continue
            val artifact = artifactProvider(modelId)?.takeIf { it.isFile } ?: continue
            try {
                val session = openSession(artifact)
                synchronized(lock) { sessions[modelId] = session }
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot open ONNX session for $modelId: ${t.message}")
            }
        }
    }

    override suspend fun forecast(
        modelId: String,
        closes: DoubleArray,
        steps: Int
    ): TsfmForecast? = withContext(Dispatchers.Default) {
        val session = synchronized(lock) { sessions[modelId] } ?: return@withContext null
        try {
            when (modelId) {
                ModelArtifacts.CHRONOS.modelId -> runChronos(session, closes, steps)
                ModelArtifacts.TIMESFM.modelId -> runTimesFm(session, closes, steps)
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX inference failed for $modelId: ${t.message}")
            null
        }
    }

    private fun openSession(artifact: File): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return try {
            environment.createSession(artifact.absolutePath, options)
        } finally {
            options.close()
        }
    }

    private fun runChronos(
        session: OrtSession,
        closes: DoubleArray,
        steps: Int
    ): TsfmForecast {
        require(closes.isNotEmpty()) { "Chronos needs observed history" }
        val prepared = ChronosBoltAdapter.prepare(closes)
        val shape = longArrayOf(1, ChronosBoltAdapter.CONTEXT_LENGTH.toLong())

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(prepared.context), shape).use { context ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(prepared.mask), shape).use { mask ->
                session.run(mapOf("context" to context, "mask" to mask)).use { result ->
                    val output = result.get(0) as OnnxTensor
                    @Suppress("UNCHECKED_CAST")
                    val quantiles = output.value as Array<Array<FloatArray>>
                    return ChronosBoltAdapter.decode(quantiles, prepared, steps)
                }
            }
        }
    }

    private fun runTimesFm(
        session: OrtSession,
        closes: DoubleArray,
        steps: Int
    ): TsfmForecast {
        require(closes.isNotEmpty()) { "TimesFM needs observed history" }
        val prepared = TimesFmAdapter.prepare(closes)
        val shape = longArrayOf(1, TimesFmAdapter.CONTEXT_LENGTH.toLong())

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(prepared.inputs), shape).use { inputs ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(prepared.masks), shape).use { masks ->
                session.run(mapOf("inputs" to inputs, "masks" to masks)).use { result ->
                    val output = result.get(0) as OnnxTensor
                    @Suppress("UNCHECKED_CAST")
                    val point = output.value as Array<Array<FloatArray>>
                    return TimesFmAdapter.decode(point, prepared, steps)
                }
            }
        }
    }

    private companion object {
        const val TAG = "OnnxTsfmForecaster"
    }
}
