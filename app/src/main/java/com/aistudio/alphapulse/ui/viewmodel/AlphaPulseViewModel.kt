package com.aistudio.alphapulse.ui.viewmodel

/**
 * The bridge between the engine and the screen.
 *
 * It holds the state the UI draws, forwards user actions (refresh, export,
 * research-mode comparison) to the repository, and survives screen rotation.
 *
 * Spec Ref: §39 User Interface, §38 Export System, §60 Research Mode
 */

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.model.ChartPoint
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.data.model.ModelDownloadStore
import com.aistudio.alphapulse.data.model.ModelFileLocator
import com.aistudio.alphapulse.data.model.ModelLoadStatus
import com.aistudio.alphapulse.domain.decision.DecisionReport
import com.aistudio.alphapulse.domain.ensemble.EnsembleWeighting
import com.aistudio.alphapulse.domain.quant.MarkovRegimeResult
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.data.model.ScreenedCoin
import com.aistudio.alphapulse.data.repository.AlphaPulseRepository
import com.aistudio.alphapulse.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Horizon whose calibrated weights feed the live ensemble (the §29 reference horizon). */
private const val CALIBRATION_REFERENCE_HORIZON = "15m"

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

    /** §29 verdict for the selected asset: null until the first evaluation completes. */
    val decision: DecisionReport? = null,

    /**
     * The newest recorded §29 verdicts for the selected asset, newest first.
     *
     * Read from the same table the audit export joins on, so the log on screen and
     * a CSV row attribute their decision to the same record.
     */
    val decisionHistory: List<DecisionRecordEntity> = emptyList(),
    val sessionElapsedSeconds: Long = 0L,
    val isLTFReady: Boolean = false,
    val isMTFReady: Boolean = false,
    val isHTFReady: Boolean = false,
    val horizonFilter: String = "ALL",
    val predictions: List<PredictionEntity> = emptyList(),
    val showRefreshDialog: Boolean = false,
    val snackbarMessage: String? = null,
    val modelDownloads: Map<String, ModelDownloadState> = emptyMap(),
    val mountedModelIds: List<String> = emptyList(),

    /**
     * The mounted Cactus Needle 3 checkpoint, or null when it is not downloaded.
     *
     * The on-device engine (§30) cannot run without this file, so the screen asks the
     * engine whether it can run *this* path rather than assuming a download worked.
     */
    val needle3WeightsPath: String? = null
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
    private var calibrationJob: Job? = null
    private val sessionStartTimestamp = System.currentTimeMillis()

    init {
        ModelDownloadStore.init(application)
        val mounted = EnsembleWeighting.mountedExtraIds(ModelDownloadStore.readyModelIds())
        _uiState.update { state ->
            state.copy(
                modelDownloads = ModelDownloadStore.states.value,
                mountedModelIds = mounted,
                needle3WeightsPath = needle3WeightsPathFor(ModelDownloadStore.states.value),
                modelState = ModelState().also {
                    EnsembleWeighting.rebalanceForMountedModels(it, mounted)
                }
            )
        }
        observeModelDownloads()
        viewModelScope.launch {
            repository.syncMountedTsfmModels(ModelDownloadStore.readyModelIds())
        }
        startSessionTimer()
        observePredictions()
        observeDecisionHistory()
        observeWebSocketTelemetry()
        bootPipeline()
    }

    /**
     * Resolves the Needle 3 checkpoint path once the download is genuinely ready.
     *
     * A download is READY only after its size and SHA-256 were verified, so this
     * hands the engine a file it can trust. Anything less returns null and the run
     * path reports "no checkpoint mounted" instead of trying to execute a partial
     * file.
     */
    private fun needle3WeightsPathFor(downloads: Map<String, ModelDownloadState>): String? {
        if (downloads["needle3"]?.status != ModelLoadStatus.READY) return null
        return ModelFileLocator.locate("needle3", getApplication())?.absolutePath
    }

    /** Mirrors persisted download state into the UI and rebalances on every new mount. */
    private fun observeModelDownloads() {
        viewModelScope.launch {
            var previouslyReady = ModelDownloadStore.readyModelIds().toSet()
            ModelDownloadStore.states.collect { downloads ->
                val readyIds = downloads.values
                    .filter { it.status == ModelLoadStatus.READY }
                    .map { it.modelId }
                    .sorted()
                val mounted = EnsembleWeighting.mountedExtraIds(readyIds)
                val newlyReady = readyIds.toSet() - previouslyReady

                _uiState.update { state ->
                    // Fresh instance so recomposition observes the rebalanced weights.
                    val rebalanced = state.modelState.copy()
                    if (newlyReady.isNotEmpty()) {
                        EnsembleWeighting.rebalanceForMountedModels(rebalanced, mounted)
                    }
                    state.copy(
                        modelDownloads = downloads,
                        mountedModelIds = mounted,
                        needle3WeightsPath = needle3WeightsPathFor(downloads),
                        modelState = rebalanced
                    )
                }

                if (readyIds.toSet() != previouslyReady) {
                    viewModelScope.launch { repository.syncMountedTsfmModels(mounted) }
                }

                if (newlyReady.isNotEmpty()) {
                    val label = newlyReady.first().let { id ->
                        ModelArtifacts.byId(id)?.displayName ?: id
                    }
                    _uiState.update {
                        it.copy(
                            snackbarMessage = "$label mounted — ensemble expanded to " +
                                "${EnsembleWeighting.activeModelCount(mounted)} models"
                        )
                    }

                    // Mounting changes the candidate set, so the active asset is
                    // calibrated again rather than keeping a split that predates
                    // the new model.
                    val active = _uiState.value.selectedSymbol
                    if (active.isNotBlank()) calibrateSelectedCoin(active)
                }
                previouslyReady = readyIds.toSet()
            }
        }
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

    /**
     * §21/§29: follow the selected asset's recorded verdicts.
     *
     * A new symbol cancels the previous query rather than mixing two histories, and
     * a new evaluation re-emits on its own because the DAO is a Flow over the table
     * the repository writes.
     */
    private fun observeDecisionHistory() {
        viewModelScope.launch {
            uiState
                .map { it.selectedSymbol }
                .distinctUntilChanged()
                .collectLatest { symbol ->
                    if (symbol.isBlank()) {
                        _uiState.update { it.copy(decisionHistory = emptyList()) }
                        return@collectLatest
                    }
                    repository.getDecisionHistoryFlow(symbol).collect { records ->
                        _uiState.update { it.copy(decisionHistory = records) }
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
                calibrateSelectedCoin(firstCoin.symbol)
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
                // Reset per asset, then re-establish the mounted-member split.
                modelState = ModelState().also {
                    EnsembleWeighting.rebalanceForMountedModels(it, ModelDownloadStore.readyModelIds())
                }
            )
        }
        repository.wsManager.connect(symbol)
        startAssetCycle()
        calibrateSelectedCoin(symbol)
    }

    /**
     * Start the §20 30-day calibration for the selected asset.
     *
     * Runs off the evaluation cycle: the loop must not wait for a month of
     * history to be fetched. When it finishes, the reference-horizon weights are
     * applied to the live state, so the ensemble immediately trades this coin's
     * measured split instead of the generic mount split. A selection that changes
     * mid-run cancels the old calibration rather than letting it overwrite the
     * new coin's weights.
     */
    private fun calibrateSelectedCoin(symbol: String) {
        calibrationJob?.cancel()
        val ready = ModelDownloadStore.readyModelIds()
        if (EnsembleWeighting.mountedExtraIds(ready).isEmpty()) return

        calibrationJob = viewModelScope.launch {
            val report = runCatching { repository.calibrateCoin(symbol, ready) }.getOrNull()
                ?: return@launch
            if (_uiState.value.selectedSymbol != symbol) return@launch

            val calibrated = report.weightsFor(CALIBRATION_REFERENCE_HORIZON) ?: return@launch
            _uiState.update {
                it.copy(
                    modelState = calibrated,
                    snackbarMessage = "$symbol recalibrated over ${report.windowDays} days — " +
                        "ensemble weights updated"
                )
            }
        }
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
                        val result = repository.executeAssetEvaluation(
                            symbol = symbol,
                            modelState = currentModel,
                            activeExtraModels = ModelDownloadStore.readyModelIds()
                        )
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
                                decision = result.decision,
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
    /**
     * Stream a mountable TSFM artefact and mount it into the live ensemble.
     * Progress is published through [ModelDownloadStore.states].
     */
    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            try {
                ModelDownloadStore.download(modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "Model install failed: ${e.message ?: e}") }
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
}
