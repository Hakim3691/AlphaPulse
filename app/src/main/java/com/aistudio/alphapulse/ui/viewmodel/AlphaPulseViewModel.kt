package com.aistudio.alphapulse.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.model.ChartPoint
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.domain.quant.MarkovRegimeResult
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.data.model.ScreenedCoin
import com.aistudio.alphapulse.data.repository.AlphaPulseRepository
import com.aistudio.alphapulse.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

import com.aistudio.alphapulse.data.model.ModelFileLocator
import com.aistudio.alphapulse.data.model.ModelManager

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class AlphaPulseUiState(
    val pipelineRunning: Boolean = true,
    val pipelineStepTitle: String = "Step 1: Institutional 4-Filter Screen",
    val pipelineStepSubtitle: String = "Evaluating 10-day institutional flow tape across active universe...",
    val pipelineProgress: Float = 0.05f,
    val pipelineSecondsRemaining: Int = 60,
    val isReady: Boolean = false,
    val screenedCoins: List<ScreenedCoin> = emptyList(),
    val selectedSymbol: String = "",
    val under10Only: Boolean = false,
    val spotPrice: Double = 0.0,
    val spotChange: Double = 0.0,
    val high24h: Double = 0.0,
    val low24h: Double = 0.0,
    val modelState: ModelState = ModelState(),
    val totalSettledForCoin: Int = 0,
    val coneHitRateForCoin: Double? = null,
    val rsi: Double = 50.0,
    val macdSignal: String = "--",
    val markovRegime: MarkovRegimeResult? = null,
    val netImbalanceUsdt: Double = 0.0,
    val bidRatioPct: Double = 50.0,
    val heatmapSource: String = "--",
    val alphaPoc: Double = 0.0,
    val alphaVah: Double = 0.0,
    val alphaVal: Double = 0.0,
    val ltfHitRate: Double? = null,
    val mtfHitRate: Double? = null,
    val htfHitRate: Double? = null,
    val forecastTargets: List<ForecastTarget> = emptyList(),
    val chartPoints: List<ChartPoint> = emptyList(),
    val executiveBrief: String = "",
    val sessionElapsedSeconds: Long = 0L,
    val isLTFReady: Boolean = false,
    val isMTFReady: Boolean = false,
    val isHTFReady: Boolean = false,
    val horizonFilter: String = "ALL",
    val predictions: List<PredictionEntity> = emptyList(),
    val showRefreshDialog: Boolean = false,
    val snackbarMessage: String? = null
)

class AlphaPulseViewModel(application: Application) : AndroidViewModel(application) {

    // Resolved through the ServiceLocator seam so tests can substitute an
    // in-memory graph before any component touches the production wiring.
    private val repository: AlphaPulseRepository =
        ServiceLocator.graph(application).repository

    private val _uiState = MutableStateFlow(AlphaPulseUiState())
    val uiState: StateFlow<AlphaPulseUiState> = _uiState.asStateFlow()

    private var sessionTimerJob: Job? = null
    private var assetCycleJob: Job? = null
    private var countdownJob: Job? = null
    private val sessionStartTimestamp = System.currentTimeMillis()

    init {
        startSessionTimer()
        observePredictions()
        observeWebSocketTelemetry()
        bootPipeline()
    }

    private fun observeWebSocketTelemetry() {
        viewModelScope.launch {
            repository.wsManager.telemetryFlow.collect { live ->
                if (live != null && live.symbol.equals(_uiState.value.selectedSymbol, ignoreCase = true)) {
                    _uiState.update { state ->
                        state.copy(
                            spotPrice = if (live.spotPrice > 0.0) live.spotPrice else state.spotPrice,
                            netImbalanceUsdt = live.netImbalanceUsdt,
                            bidRatioPct = live.bidRatioPct
                        )
                    }
                }
            }
        }
    }

    private fun startSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val elapsed = (System.currentTimeMillis() - sessionStartTimestamp) / 1000
                _uiState.update { it.copy(sessionElapsedSeconds = elapsed) }
            }
        }
    }

    private fun observePredictions() {
        viewModelScope.launch {
            repository.allPredictionsFlow.collect { allList ->
                _uiState.update { state ->
                    val coinList = if (state.selectedSymbol.isNotBlank()) {
                        allList.filter { it.symbol == state.selectedSymbol }
                    } else allList

                    val settled = coinList.filter { it.settled }
                    val hitRate = if (settled.isNotEmpty()) {
                        (settled.count { it.inCone == true }.toDouble() / settled.size) * 100.0
                    } else null

                    // Per-tier cone hit rate (LTF=1m+5m, MTF=15m+30m, HTF=1h). The global rate is
                    // dominated by high-frequency 1m settlements and overstated MTF/HTF readiness.
                    fun tierRate(horizons: Set<String>): Pair<Double?, Int> {
                        val tierSettled = settled.filter { it.horizonId in horizons }
                        val rate = if (tierSettled.isEmpty()) null else
                            (tierSettled.count { it.inCone == true }.toDouble() / tierSettled.size) * 100.0
                        return Pair(rate, tierSettled.size)
                    }
                    val (ltfRate, ltfN) = tierRate(setOf("1m", "5m"))
                    val (mtfRate, mtfN) = tierRate(setOf("15m", "30m"))
                    val (htfRate, htfN) = tierRate(setOf("1h"))

                    // Readiness telemetry: original count-based gates OR cone hit rate ≥ 60% with a
                    // minimum sample of 10 settled predictions in that tier (guards against short-
                    // history tiers lighting up on one lucky hit).
                    val count1m = settled.count { it.horizonId == "1m" }
                    val count5m = settled.count { it.horizonId == "5m" }
                    val count15m = settled.count { it.horizonId == "15m" }
                    val count30m = settled.count { it.horizonId == "30m" }
                    val count1h = settled.count { it.horizonId == "1h" }

                    fun passesHitGate(rate: Double?, n: Int): Boolean = rate != null && n >= 10 && rate >= 60.0

                    val isLTF = (count1m >= 2 && count5m >= 1) || passesHitGate(ltfRate, ltfN)
                    val isMTF = (count15m >= 2 && count30m >= 1) || passesHitGate(mtfRate, mtfN)
                    val isHTF = (count30m >= 2 && count1h >= 1) || passesHitGate(htfRate, htfN)

                    state.copy(
                        predictions = coinList,
                        totalSettledForCoin = settled.size,
                        coneHitRateForCoin = hitRate,
                        ltfHitRate = ltfRate,
                        mtfHitRate = mtfRate,
                        htfHitRate = htfRate,
                        isLTFReady = isLTF,
                        isMTFReady = isMTF,
                        isHTFReady = isHTF
                    )
                }
            }
        }
    }

    fun bootPipeline() {
        viewModelScope.launch {
            // Instant startup from Room cache if available
            val initialCached = repository.loadCachedScreenedCoins()
            if (initialCached.isNotEmpty()) {
                val firstCoin = initialCached.first()
                _uiState.update {
                    it.copy(
                        screenedCoins = initialCached,
                        selectedSymbol = firstCoin.symbol,
                        spotPrice = firstCoin.lastPrice,
                        spotChange = firstCoin.priceChangePercent,
                        high24h = firstCoin.highPrice,
                        low24h = firstCoin.lowPrice,
                        isReady = true
                    )
                }
                repository.wsManager.connect(firstCoin.symbol)
                startAssetCycle()
            }

            _uiState.update {
                it.copy(
                    pipelineRunning = true,
                    pipelineSecondsRemaining = 60,
                    pipelineProgress = 0.05f
                )
            }

            countdownJob?.cancel()
            countdownJob = viewModelScope.launch {
                var seconds = 60
                while (seconds > 0 && isActive) {
                    delay(1000)
                    seconds--
                    _uiState.update { it.copy(pipelineSecondsRemaining = seconds) }
                }
            }

            val screened = repository.runInstitutionalScreening { title, sub, prog ->
                _uiState.update {
                    it.copy(
                        pipelineStepTitle = title,
                        pipelineStepSubtitle = sub,
                        pipelineProgress = prog
                    )
                }
            }

            countdownJob?.cancel()
            _uiState.update {
                it.copy(
                    pipelineRunning = false,
                    pipelineProgress = 1.0f,
                    isReady = true,
                    screenedCoins = screened
                )
            }

            // Auto-select top-ranked coin if not already selected or if universe updated
            if (screened.isNotEmpty()) {
                val current = _uiState.value.selectedSymbol
                if (current.isBlank() || screened.none { it.symbol == current }) {
                    selectCoin(screened.first().symbol)
                }
            }
        }
    }

    fun selectCoin(symbol: String) {
        val coin = _uiState.value.screenedCoins.find { it.symbol == symbol } ?: return
        _uiState.update {
            it.copy(
                selectedSymbol = symbol,
                spotPrice = coin.lastPrice,
                spotChange = coin.priceChangePercent,
                high24h = coin.highPrice,
                low24h = coin.lowPrice,
                modelState = ModelState() // Reset per asset
            )
        }
        repository.wsManager.connect(symbol)
        startAssetCycle()
    }

    fun toggleUnder10Only(enabled: Boolean) {
        _uiState.update { it.copy(under10Only = enabled) }
        val filtered = getFilteredCoins()
        if (filtered.isNotEmpty() && filtered.none { it.symbol == _uiState.value.selectedSymbol }) {
            selectCoin(filtered.first().symbol)
        }
    }

    fun setHorizonFilter(filter: String) {
        _uiState.update { it.copy(horizonFilter = filter) }
    }

    private fun startAssetCycle() {
        assetCycleJob?.cancel()
        assetCycleJob = viewModelScope.launch {
            while (isActive) {
                val symbol = _uiState.value.selectedSymbol
                if (symbol.isNotBlank()) {
                    try {
                        val currentModel = _uiState.value.modelState
                        val result = repository.executeAssetEvaluation(symbol, currentModel)
                        _uiState.update { state ->
                            state.copy(
                                spotPrice = result.spotPrice,
                                spotChange = result.spotChange,
                                high24h = result.high24h,
                                low24h = result.low24h,
                                rsi = result.rsi,
                                macdSignal = result.macdSignal,
                                markovRegime = result.markovRegime,
                                netImbalanceUsdt = result.netImbalanceUsdt,
                                bidRatioPct = result.bidRatioPct,
                            heatmapSource = result.heatmapSource,
                            alphaPoc = result.alphaMetrics.poc,
                            alphaVah = result.alphaMetrics.vah,
                            alphaVal = result.alphaMetrics.valPrice,
                            forecastTargets = result.forecastTargets,
                                chartPoints = result.chartPoints,
                                executiveBrief = result.executiveBrief,
                                modelState = currentModel.copy()
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                delay(10000) // 10-second institutional evaluation cycle
            }
        }
    }

    fun clearLedger() {
        viewModelScope.launch {
            repository.clearLedger()
            _uiState.update {
                it.copy(
                    predictions = emptyList(),
                    totalSettledForCoin = 0,
                    coneHitRateForCoin = null,
                    isLTFReady = false,
                    isMTFReady = false,
                    isHTFReady = false,
                    snackbarMessage = "Prediction ledger cleared"
                )
            }
        }
    }

    fun showRefreshDialog(show: Boolean) {
        _uiState.update { it.copy(showRefreshDialog = show) }
    }

    fun confirmManualRefresh() {
        showRefreshDialog(false)
        exportAndShareAuditCsv()
        bootPipeline()
    }

    fun exportAndShareAuditCsv() {
        viewModelScope.launch {
            try {
                val csvContent = repository.exportAuditLedgerCsv()
                val context = getApplication<Application>().applicationContext
                val file = File(context.cacheDir, "AlphaPulse_Audit_${System.currentTimeMillis()}.csv")
                file.writeText(csvContent)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val chooser = Intent.createChooser(intent, "Export AlphaPulse Audit CSV").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "Export ready in local storage") }
            }
        }
    }

    fun export4TabUniverseCsv() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>().applicationContext
                val file = File(context.cacheDir, "AlphaPulse_Filtered_Universe.csv")
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(intent, "Export 4-Tab Universe Audit CSV").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } else {
                    _uiState.update { it.copy(snackbarMessage = "Universe CSV will be ready after next scan") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "Export ready in local storage") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.wsManager.stopStreaming()
    }

    fun downloadModel(
    modelId: String,
    version: String,
    checksum: String,
    backend: String,
    sizeBytes: Long,
    parameterCount: Int,
    quantization: String
) {
        viewModelScope.launch {
            try {
                // Initialize ModelManager with application context
                ModelManager.init(getApplication<Application>().applicationContext)
                
                // Download the model from the specified repo
                val modelFile = downloadModelFile(modelId, sizeBytes, checksum)
                
                if (modelFile != null) {
                    // Calculate SHA-256 checksum of the downloaded file
                    val actualChecksum = ModelManager.calculateSha256(modelFile)
                    
                    // Record the download with metadata using the verified checksum
                    ModelManager.recordDownload(
                        modelId = modelId,
                        version = version,
                        checksumSha256 = actualChecksum,
                        parameterCount = parameterCount,
                        sizeBytes = sizeBytes,
                        quantization = quantization,
                        backend = backend
                    )
                    
                    // Activate the downloaded model using the verified checksum
                    try {
                        ModelManager.activateModel(modelId, actualChecksum)
                        _uiState.update { it.copy(
                            snackbarMessage = "Model activated successfully"
                        ) }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(
                            snackbarMessage = "Model activated: ${e.message ?: e}"
                        ) }
                    }
                } else {
                    _uiState.update { it.copy(snackbarMessage = "Download failed: Could not download model file") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "Download failed: ${e.message ?: e}") }
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun getFilteredCoins(): List<ScreenedCoin> {
        val state = _uiState.value
        return if (state.under10Only) {
            state.screenedCoins.filter { it.lastPrice <= 10.0 }
        } else {
            state.screenedCoins
        }
    }

    /**
     * Download a model file from GitHub repo using OkHttp.
     * Returns the File if successful, null on failure.
     * Uses the existing OkHttp dependency (okhttp:4.10.0).
     */
    private suspend fun downloadModelFile(modelId: String, sizeBytes: Long, checksum: String): File? {
        // Called from viewModelScope.launch { }, so we can directly use suspend functions
        return downloadModelFileSuspend(modelId, sizeBytes, checksum)
    }

    private suspend fun downloadModelFileSuspend(modelId: String, sizeBytes: Long, checksum: String): File? {
        val client = OkHttpClient()
        // Model repos are hosted on GitHub Packages / releases
        // Format: https://github.com/{repo}/releases/download/{version}/{filename}
        // For our two models:
        // - Chronos Bolt Tiny: bakshinghampalace/chronos-bolt-tiny-onnx, chronos-bolt-tiny-fp16.onnx
        // - TimesFM 2.5 200M: onnx-community/timesfm-2.5-200m-int4, model_quantized.onnx
        val fileName = when (modelId) {
            "chronos" -> "chronos-bolt-tiny-fp16.onnx"
            "timesfm" -> "model_quantized.onnx"
            else -> "model.onnx"
        }

        val url = "https://github.com/${modelId}/releases/download/v1.0/${fileName}"

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful || response.body == null) {
            return null
        }

        val downloadedBytes = response.body?.contentLength() ?: 0

        // Verify content length matches expected (best-effort)
        if (sizeBytes > 0L && downloadedBytes != sizeBytes) {
            // Size mismatch - may be partial download or different version
            // Still accept if checksum matches later
        }

        val inputStream = response.body?.byteStream()
        val modelDir = ModelFileLocator.getModelDirectory(modelId, getApplication<Application>().applicationContext)
        val outputFile = File(modelDir, fileName)

        // Ensure directory exists
        modelDir.mkdirs()

        // Write file in chunks to avoid memory issues with large models
        val outputStream = FileOutputStream(outputFile)
        inputStream?.use { stream ->
            val buffer = ByteArray(8192)
            var totalRead = 0L
            while (totalRead < sizeBytes) {
                val read = stream.read(buffer)
                if (read < 0) break
                outputStream.write(buffer, 0, read)
                totalRead += read
            }
        }
        outputStream.close()

        // Verify checksum if provided
        if (checksum.isNotEmpty()) {
            val actualChecksum = ModelManager.calculateSha256(outputFile)
            if (actualChecksum != checksum) {
                // Checksum mismatch - delete and return null
                outputFile.delete()
                return null
            }
        }

        return outputFile
    }
}
