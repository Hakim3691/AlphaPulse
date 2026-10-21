package com.aistudio.alphapulse.data.model

import android.content.Context
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.db.ModelRegistryDao
import com.aistudio.alphapulse.data.db.ModelRegistryEntity
import com.aistudio.alphapulse.data.db.PredictionDao
import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.db.SessionDao
import com.aistudio.alphapulse.data.db.SessionEntity
import com.aistudio.alphapulse.domain.quant.ModelState
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class VersionInfo(
    val quantEngineVersion: String = "1.0",
    val tsfmVersion: String = "1.0",
    val lobModelVersion: String = "1.0",
    val decisionRouterVersion: String = "1.0",
    val slmVersion: String = "1.0",
    val featureSchemaVersion: String = "1.0"
)

object ModelManager {

    private var context: Context? = null

    private val db by lazy { AppDatabase.getDatabase(context!!) }
    private val modelDao by lazy { db.modelRegistryDao() }
    private val sessionDao by lazy { db.sessionDao() }
    private val predictionDao by lazy { db.predictionDao() }

    /** Initialise with an Application context. Call once before any other method. */
    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    /** Get current version info for all components */
    fun getCurrentVersions(): VersionInfo = VersionInfo(
        quantEngineVersion = modelDao.getModelById("quant")?.version ?: "1.0",
        tsfmVersion = modelDao.getModelById("tsfm")?.version ?: "1.0",
        lobModelVersion = modelDao.getModelById("lob")?.version ?: "1.0",
        decisionRouterVersion = modelDao.getModelById("decision-router")?.version ?: "1.0",
        slmVersion = modelDao.getModelById("slm")?.version ?: "1.0",
        featureSchemaVersion = modelDao.getModelById("feature-schema")?.version ?: "1.0"
    )

    /** Get active model by ID, returns null if not found or not active */
    fun getActiveModel(modelId: String): ModelRegistryEntity? =
        modelDao.getModelById(modelId)?.takeIf { it.isActive }

    /** Mark a model as active and deactivate all others */
    suspend fun activateModel(modelId: String, checksum: String) {
        // Verify checksum first
        val modelFile = ModelFileLocator.locate(modelId, context!!)
        if (modelFile != null && calculateSha256(modelFile) == checksum) {
            // Deactivate all models
            modelDao.getAllModels().forEach { model ->
                modelDao.insert(model.copy(isActive = false))
            }
            // Activate the requested model
            val now = System.currentTimeMillis()
            val activated = modelDao.getModelById(modelId)?.also { model ->
                modelDao.update(
                    model.copy(
                        isActive = true,
                        checksumSha256 = checksum,
                        lastActivated = now
                    )
                )
            }
        }
    }

    /** Record model download with checksum and metadata */
    suspend fun recordDownload(
        modelId: String,
        version: String,
        checksumSha256: String,
        parameterCount: Int,
        sizeBytes: Long,
        quantization: String,
        backend: String
    ) {
        val existing = modelDao.getModelById(modelId)
        val downloaded = existing?.also { model ->
            modelDao.insert(
                model.copy(
                    version = version,
                    checksumSha256 = checksumSha256,
                    parameterCount = parameterCount,
                    sizeBytes = sizeBytes,
                    quantization = quantization,
                    backend = backend,
                    downloadTimestamp = System.currentTimeMillis()
                )
            )
        } ?: run {
            ModelRegistryEntity(
                modelId = modelId,
                version = version,
                checksumSha256 = checksumSha256,
                parameterCount = parameterCount,
                sizeBytes = sizeBytes,
                quantization = quantization,
                backend = backend,
                latencyMs = 0,
                accuracyPct = 0.0
            )
        }
        modelDao.insert(downloaded)
    }

    /** Generate session on service start, persist state for recovery */
    suspend fun recordSessionStart(
        weights: String,
        predictions: String,
        orderBookState: String,
        symbol: String,
        regime: String
    ) {
        val session = SessionEntity(
            sessionId = "current_${System.currentTimeMillis()}",
            startedAt = System.currentTimeMillis(),
            lastActive = System.currentTimeMillis(),
            weightsSnapshot = weights,
            predictionsSnapshot = predictions,
            orderBookStateSnapshot = orderBookState,
            regimeState = regime,
            symbol = symbol
        )
        sessionDao.insertSession(session)
    }

    /** Update session on activity, persist for recovery */
    suspend fun recordSessionActivity(
        weights: String,
        predictions: String,
        orderBookState: String
    ) {
        val sessionId = "current_${System.currentTimeMillis()}"
        sessionDao.getSession(sessionId)?.let { session ->
            sessionDao.insertSession(
                session.copy(
                    lastActive = System.currentTimeMillis(),
                    weightsSnapshot = weights,
                    predictionsSnapshot = predictions,
                    orderBookStateSnapshot = orderBookState
                )
            )
        }

        // Also try to find latest active session
        sessionDao.getLatestSession()?.let { latest ->
            sessionDao.insertSession(
                latest.copy(
                    lastActive = System.currentTimeMillis(),
                    weightsSnapshot = weights,
                    predictionsSnapshot = predictions,
                    orderBookStateSnapshot = orderBookState
                )
            )
        }
    }

    /** Recover session state on app restart - full recovery per §54 */
    suspend fun recoverSession(): RecoveryData? {
        val session = sessionDao.getLatestSession()
        return session?.let { session ->
            // Recover active predictions associated with this session
            val predictions = predictionDao.getPredictionsBySymbol(session.symbol)
                .filter { it.issuedTimestamp >= session.startedAt && it.issuedTimestamp <= session.lastActive }
                .sortedByDescending { it.issuedTimestamp }

            // Recover ensemble weights from session
            val weights = parseEnsembleWeights(session.weightsSnapshot)
                ?: ModelState()

            // Recover order-book state snapshot
            val orderBookState = session.orderBookStateSnapshot

            RecoveryData(
                weights = weights,
                predictions = predictions,
                orderBookState = orderBookState,
                regimeState = session.regimeState,
                symbol = session.symbol
            )
        }
    }

    /** Parse ensemble weights from string snapshot */
    private fun parseEnsembleWeights(weightsStr: String): ModelState? {
        try {
            // Expected format: "weightK=0.30;weightP=0.35;weightAlpha=0.35;mode=Baseline Init"
            val parts = weightsStr.split(";").associate { kv -> kv.split("=").let { it[0] to it[1] } }
            return ModelState(
                weightK = parts["weightK"]?.toDouble() ?: 0.30,
                weightP = parts["weightP"]?.toDouble() ?: 0.35,
                weightAlpha = parts["weightAlpha"]?.toDouble() ?: 0.35,
                mode = parts["mode"] ?: "Baseline Init"
            )
        } catch (e: Exception) {
            return null
        }
    }

    /** Save ensemble weights to string snapshot */
    private fun ensembleWeightsToString(weights: ModelState): String {
        return "weightK=${weights.weightK};weightP=${weights.weightP};weightAlpha=${weights.weightAlpha};mode=${weights.mode}"
    }

    /** Recover all state needed for app restart per §54 */
    data class RecoveryData(
        val weights: ModelState,
        val predictions: List<PredictionEntity>,
        val orderBookState: String,
        val regimeState: String,
        val symbol: String
    )

    /** Calculate SHA-256 checksum for a file */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            val fileInput = FileInputStream(file)
            try {
                this.update(fileInput.readBytes())
            } finally {
                fileInput.close()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Get version info linked to a specific prediction */
    suspend fun getVersionForPrediction(predictionId: String): VersionInfo {
        // Read from prediction entity's linked version info
        // In full implementation, PredictionEntity would have a versionTag field
        // For now, return current system versions
        return getCurrentVersions()
    }

    /** Validate that a model version is compatible with current backend */
    suspend fun isModelCompatible(modelId: String, backend: String): Boolean {
        val model = modelDao.getModelById(modelId)
        return model?.let { it.isActive && (it.backend == backend || it.backend == "AUTO") } ?: false
    }
}

/** Helper object to locate model files on device storage */
object ModelFileLocator {

    fun locate(modelId: String, context: Context): File? {
        val baseDir = context.getExternalFilesDir(null)
        if (baseDir == null) return null
        val dir = File(baseDir, "models/$modelId")
        val files = dir.listFiles()
        return if (dir.exists() && dir.isDirectory && files != null && files.isNotEmpty()) {
            files.lastOrNull()
        } else null
    }

    fun getModelDirectory(modelId: String, context: Context): File {
        val baseDir = context.getExternalFilesDir(null)
        val dir = File(baseDir ?: context.cacheDir, "models/$modelId")
        dir.mkdirs()
        return dir
    }
}
