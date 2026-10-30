package com.aistudio.alphapulse.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.alphapulse.BuildConfig
import com.aistudio.alphapulse.data.local.AppDatabase
import com.aistudio.alphapulse.data.local.AssetEntity
import com.aistudio.alphapulse.data.local.CampaignEntity
import com.aistudio.alphapulse.data.db.AppDatabase as AlphaDatabase
import com.aistudio.alphapulse.data.local.DatabaseRepository
import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import com.aistudio.alphapulse.data.model.BacktestMetrics
import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CandidatePath
import com.aistudio.alphapulse.data.model.CoinCalibration
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.data.remote.BinanceSignedClient
import com.aistudio.alphapulse.data.remote.BinanceUserDataClient
import com.aistudio.alphapulse.data.remote.CoinGlassClient
import com.aistudio.alphapulse.engine.CampaignEngine
import com.aistudio.alphapulse.engine.BinanceSignedOrderPort
import com.aistudio.alphapulse.engine.ClientOrderIds
import com.aistudio.alphapulse.engine.LiveOrderCoordinator
import com.aistudio.alphapulse.engine.OrderStager
import com.aistudio.alphapulse.engine.ExecutionEngine
import com.aistudio.alphapulse.engine.ExecutionGuardrails
import com.aistudio.alphapulse.engine.FeatureEngine
import com.aistudio.alphapulse.engine.HardwareDiagnosticsProvider
import com.aistudio.alphapulse.engine.InvalidationAction
import com.aistudio.alphapulse.engine.InvalidationEngine
import com.aistudio.alphapulse.engine.ListenKeySession
import com.aistudio.alphapulse.engine.LiveMarketDataProvider
import com.aistudio.alphapulse.engine.MarketDataRepository
import com.aistudio.alphapulse.engine.OrphanInterceptDecision
import com.aistudio.alphapulse.engine.OrphanInterceptPolicy
import com.aistudio.alphapulse.engine.OrderBookSnapshot
import com.aistudio.alphapulse.engine.PaperPnl
import com.aistudio.alphapulse.engine.OverrideWarning
import com.aistudio.alphapulse.engine.PathEngine
import com.aistudio.alphapulse.engine.ReplayEngine
import com.aistudio.alphapulse.engine.ReplayEngine.ReplayResult
import com.aistudio.alphapulse.engine.RiskEngine
import com.aistudio.alphapulse.engine.ScreenedUniverseBridge
import com.aistudio.alphapulse.engine.SessionSecurityManager
import com.aistudio.alphapulse.engine.SimulationHarness
import com.aistudio.alphapulse.engine.SimulationScenario
import com.aistudio.alphapulse.engine.UniverseScreener
import com.aistudio.alphapulse.engine.UserDataOrderUpdate
import com.aistudio.alphapulse.engine.UserDataStreamLoop
import com.aistudio.alphapulse.engine.UniverseSnapshot
import com.aistudio.alphapulse.engine.StructureEngine
import com.aistudio.alphapulse.engine.TakerTrade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Arrays

/** Raised for a -1125 frame so the stream loop re-creates the listen key. */
private class ListenKeyExpiredException : IllegalStateException("listen key expired")

data class MainUiState(
    val assets: List<CryptoAsset> = emptyList(),
    val selectedAsset: CryptoAsset? = null,
    val levelMap: LevelMap? = null,
    /** §10 live per-coin calibration for the selected asset (null until candles load). */
    val calibration: CoinCalibration? = null,
    val paths: List<CandidatePath> = emptyList(),
    val campaigns: List<Campaign> = emptyList(),
    val selectedCampaign: Campaign? = null,
    val liveEvidence: EvidenceFrame? = null,
    val orderBook: OrderBookSnapshot? = null,
    val takerTrades: List<TakerTrade> = emptyList(),
    val riskEnvelope: RiskEnvelope = RiskEnvelope(),
    val venueHealth: VenueHealth = VenueHealth(),
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val auditLogs: List<AuditLogEntry> = emptyList(),
    val backtestMetrics: BacktestMetrics = BacktestMetrics(),
    /** Phase 5 (§37): result of the latest historical replay, null until one runs. */
    val lastReplay: ReplayResult? = null,
    /** True while the §37 replay is computing. */
    val isReplayRunning: Boolean = false,
    val selectedScenario: SimulationScenario? = null,
    val isSessionActive: Boolean = false,
    val isOrphanedOrderWarningOpen: Boolean = false,
    val isOverrideDialogOpen: Boolean = false,
    val overrideWarnings: List<OverrideWarning> = emptyList(),
    /** True while a pre-flight hardware/network diagnostics sample is being taken (Spec §2). */
    val isDiagnosticsRunning: Boolean = false,
    /** True once a real diagnostics sample has populated venueHealth (vs. built-in defaults). */
    val hasLiveDiagnostics: Boolean = false,
    /** True when the current universe came from live exchange discovery (vs offline seed). */
    val isLiveUniverse: Boolean = false,
    /** §36: user tapped a live mode while no API session is unlocked — show the gate dialog. */
    val isLiveModeBlocked: Boolean = false,
    /** Real Binance futures wallet (USDT) once a live session is unlocked; null in paper. */
    val liveWalletUsd: Double? = null,
    /** Per-client-order display records reported by the live coordinator. */
    val liveOrderRecords: Map<String, LiveOrderRecord> = emptyMap(),
    /** Client order ids the venue currently holds, as last reconciled. */
    val liveOpenClientIds: Set<String> = emptySet(),
    /** Outcome of the most recent REST reconcile, so a stream gap stays visible. */
    val lastReconcile: ReconcileOutcome? = null
 )

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = DatabaseRepository(db)

    /** Paper-wallet config persistence (non-sensitive numbers only; Spec §5). */
    private val paperPrefs = application.getSharedPreferences("paper_wallet_prefs", Context.MODE_PRIVATE)

    private val marketDataRepo = MarketDataRepository()
    private val universeScreener = UniverseScreener(db.candleDao())
    private val liveProvider = LiveMarketDataProvider()

    /**
     * AlphaPulse's screened universe and per-horizon forecasts are the campaign
     * engine's only coin source. Crypto Trader Pro's own Binance discovery is gone.
     */
    private val universeBridge = ScreenedUniverseBridge()

    /** Latest forecast ladder per symbol, refreshed with each universe load. */
    @Volatile
    private var forecastsBySymbol: Map<String, List<ForecastTarget>> = emptyMap()
    private val featureEngine = FeatureEngine()
    private val structureEngine = StructureEngine()
    private val pathEngine = PathEngine(featureEngine)
    private val campaignEngine = CampaignEngine()
    private val invalidationEngine = InvalidationEngine()
    private val riskEngine = RiskEngine()
    private val sessionManager = SessionSecurityManager()
    private val simulationHarness = SimulationHarness()
    private val replayEngine = ReplayEngine()

    /** Phase 4 (§36): execution gate + live order routing state. */
    private val executionEngine = ExecutionEngine(riskEngine)

    /** §22/§27 live path: guardrail-gated staging plus cancel and reconcile. */
    private val orderStager = OrderStager(riskEngine)
    private val liveOrders = LiveOrderCoordinator(BinanceSignedOrderPort)
    private val consecutiveRejectsBySymbol = mutableMapOf<String, Int>()
    private var userDataJob: Job? = null
    private var lastPositionCheckMs = 0L
    private val routedClientIds = mutableSetOf<String>()

    /** Display records for staged orders, keyed by client order id (§22). */
    private val liveOrderRecords = mutableMapOf<String, LiveOrderRecord>()

    /** Phase 1 (Spec §2): real OS diagnostics; no-arg constructor stays test-friendly. */
    private val diagnosticsProvider: HardwareDiagnosticsProvider? =
        runCatching { HardwareDiagnosticsProvider(application) }.getOrNull()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var activeDeltaJob: Job? = null
    private var orderBookJob: Job? = null
    private var takerFlowJob: Job? = null
    private var liveWalletJob: Job? = null

    /** §5 Paper wallet: cumulative realized PnL today (USD), booked at flatten events. */
    private var paperRealizedPnlUsd = 0.0

    init {
        // Optional CoinGlass key from the secrets-managed environment (never persisted).
        runCatching {
            CoinGlassClient.apiKey = BuildConfig.COINGLASS_API_KEY
        }
        applyStoredPaperWallet()
        loadInitialData()
        observeAuditLogs()
        runPreFlightDiagnostics()
        startDormantUniversePolling()
        startDiagnosticsPolling()
    }

    /**
     * AlphaPulse's final filtered coin list plus its forecast ladder, read from the
     * shared store. This replaces Crypto Trader Pro's own Binance discovery as the
     * campaign engine's universe source.
     */
    private suspend fun alphaPulseUniverseSnapshot(): UniverseSnapshot {
        val alphaDb = AlphaDatabase.getDatabase(getApplication<Application>())
        val screened = runCatching { alphaDb.screenedCoinDao().getAllScreenedCoins() }
            .getOrDefault(emptyList())
        if (screened.isEmpty()) {
            // No AlphaPulse screen yet: show the labelled seed placeholder instead of
            // inventing a universe.
            return UniverseSnapshot(marketDataRepo.getInitialUniverse(), isLive = false)
        }
        val forecasts = runCatching {
            screened.associate { row ->
                row.symbol to alphaDb.predictionDao().getPredictionsBySymbol(row.symbol)
            }
        }.getOrDefault(emptyMap())
        forecastsBySymbol = forecasts.mapValues { (_, rows) -> universeBridge.forecastTargetsFor(rows) }
        return liveProvider.universeFrom(screened, forecasts)
    }

    private fun loadInitialData() {
        // §5 Heavy Data: pull the real (or cached) universe in the background, but paint
        // the UI immediately from the offline seed so first frame is never blank.
        val initialAssets = marketDataRepo.getInitialUniverse()
        val defaultAsset = initialAssets.first()

        viewModelScope.launch {
            val snapshot = runCatching { liveProvider.loadUniverseOnce { alphaPulseUniverseSnapshot() } }
                .getOrDefault(UniverseSnapshot(initialAssets, isLive = false))
            val chosen = snapshot.assets.firstOrNull() ?: defaultAsset
            applyUniverseAndRecompute(snapshot.assets, chosen, snapshot.isLive)
        }

        val map = structureEngine.buildLevelMap(defaultAsset)
        val candidatePaths = pathEngine.rankCandidatePaths(defaultAsset, map)
        val initialCampaigns = campaignEngine.planCampaigns(
            defaultAsset,
            map,
            candidatePaths,
            forecasts = forecastsBySymbol[defaultAsset.symbol].orEmpty()
        )
        // §4: deterministic evidence even for the seed placeholder — no Random values
        // are ever painted as if they were market data.
        val initialEvidence = liveProvider.buildEvidenceFrame(defaultAsset)

        _uiState.value = _uiState.value.copy(
            assets = initialAssets,
            selectedAsset = defaultAsset,
            levelMap = map,
            paths = candidatePaths,
            campaigns = initialCampaigns,
            selectedCampaign = initialCampaigns.firstOrNull(),
            liveEvidence = initialEvidence,
            selectedScenario = simulationHarness.availableScenarios.first()
        )

        // Sync with local Room persistence
        viewModelScope.launch {
            val entities = initialAssets.map {
                AssetEntity(
                    symbol = it.symbol,
                    baseAsset = it.baseAsset,
                    lastPrice = it.lastPrice,
                    priceChange24h = it.priceChange24h,
                    quoteVolume24h = it.quoteVolume24h,
                    orderBookSpreadPct = it.orderBookSpreadPct,
                    fundingRatePct = it.fundingRatePct,
                    openInterestUsd = it.openInterestUsd,
                    basisPremiumPct = it.basisPremiumPct,
                    atr5m = it.atr5m,
                    regime = it.regime.name,
                    tacticalBias = it.tacticalBias,
                    strategicBias = it.strategicBias,
                    primaryPainPath = it.primaryPainPath
                )
            }
            repository.saveAssets(entities)

            // Log startup
            repository.logAction(
                asset = defaultAsset.symbol,
                campaignId = "SYS_BOOT",
                action = AuditLogAction.CAMPAIGN_STAGED,
                reasonCode = "SYSTEM_INITIALIZED",
                message = "Proactive Market Structure Engine initialized in ${_uiState.value.executionMode.displayName} mode"
            )
        }

        startActiveDeltaMode(defaultAsset)
    }

    /** Applies a freshly-screened universe and recomputes analytics for the chosen symbol. */
    private suspend fun applyUniverseAndRecompute(
        universe: List<CryptoAsset>,
        chosen: CryptoAsset,
        isLive: Boolean
    ) {
        // §8: build the level map from real swing structure when candle history exists;
        // fall back to the ATR-offset synthesis only when no candles are available.
        val timeframes = runCatching { universeScreener.loadTimeframes(chosen.symbol) }.getOrNull()
        val map = if (timeframes != null && timeframes.candles1h.isNotEmpty()) {
            structureEngine.buildLevelMap(
                chosen,
                timeframes.candles4h,
                timeframes.candles1h,
                timeframes.candles5m
            )
        } else {
            structureEngine.buildLevelMap(chosen)
        }
        val candidatePaths = pathEngine.rankCandidatePaths(chosen, map)
        // §10: live per-coin calibration from real 1h/5m history.
        val calibration = runCatching { universeScreener.loadCalibration(chosen) }.getOrNull()
        val planned = campaignEngine.planCampaigns(
            chosen,
            map,
            candidatePaths,
            calibration = calibration,
            forecasts = forecastsBySymbol[chosen.symbol].orEmpty()
        )
        val evidence = liveProvider.buildEvidenceFrame(chosen)
        // Phase 4: preserve reconciled fills, then run the staging gate (§22 step 7, §36).
        val merged = mergeFillState(planned, _uiState.value.campaigns)
        val gatedCampaigns = gateAndLog(merged)
        _uiState.value = _uiState.value.copy(
            assets = universe,
            selectedAsset = chosen,
            levelMap = map,
            paths = candidatePaths,
            campaigns = gatedCampaigns,
            selectedCampaign = gatedCampaigns.firstOrNull(),
            liveEvidence = evidence,
            calibration = calibration,
            isLiveUniverse = isLive
        )
        routeLiveBoards(gatedCampaigns)
        startUserDataStreamIfNeeded()
        startActiveDeltaMode(chosen)
    }

    private fun observeAuditLogs() {
        viewModelScope.launch {
            repository.latestAuditLogs.collect { logs ->
                _uiState.value = _uiState.value.copy(auditLogs = logs)
            }
        }
        viewModelScope.launch {
            sessionManager.venueHealth.collect { health ->
                _uiState.value = _uiState.value.copy(venueHealth = health)
            }
        }
        viewModelScope.launch {
            sessionManager.isSessionActive.collect { active ->
                _uiState.value = _uiState.value.copy(isSessionActive = active)
            }
        }
    }

    /**
     * Phase 1 (Spec §2): sample real device state and mandatory-feed latency, then feed
     * the authoritative thresholds in SessionSecurityManager. Safe no-op if the provider
     * is unavailable (e.g. unit-test environments without Android framework bindings).
     */
    fun runPreFlightDiagnostics() {
        val provider = diagnosticsProvider ?: return
        if (_uiState.value.isDiagnosticsRunning) return

        _uiState.value = _uiState.value.copy(isDiagnosticsRunning = true)
        viewModelScope.launch {
            try {
                val sample = provider.sample()
                sessionManager.updateHardwareDiagnostics(
                    pingBinance = sample.binancePingMs,
                    pingCoinGlass = sample.coinglassPingMs,
                    isLowPower = sample.isLowPowerMode,
                    isThrottled = sample.isThermallyThrottled,
                    availableRamMb = sample.availableRamMb
                )
                _uiState.value = _uiState.value.copy(
                    isDiagnosticsRunning = false,
                    hasLiveDiagnostics = true
                )
                repository.logAction(
                    asset = "PORTFOLIO",
                    campaignId = "SYS_DIAGNOSTICS",
                    action = AuditLogAction.CAMPAIGN_STAGED,
                    reasonCode = if (sessionManager.venueHealth.value.isHealthy)
                        "PREFLIGHT_PASSED" else "PREFLIGHT_GUARDRAIL_TRIPPED",
                    message = "Pre-flight diagnostics: Binance ${sample.binancePingMs}ms, CoinGlass ${sample.coinglassPingMs}ms, " +
                        "RAM ${sample.availableRamMb}MB, battery ${sample.batteryLevelPct}%"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isDiagnosticsRunning = false)
                repository.logAction(
                    asset = "PORTFOLIO",
                    campaignId = "SYS_DIAGNOSTICS",
                    action = AuditLogAction.CAMPAIGN_STAGED,
                    reasonCode = "PREFLIGHT_ERROR",
                    message = "Pre-flight diagnostics failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun selectAsset(symbol: String) {
        val asset = _uiState.value.assets.find { it.symbol == symbol } ?: return
        val map = structureEngine.buildLevelMap(asset)
        val candidatePaths = pathEngine.rankCandidatePaths(asset, map)
        val planned = campaignEngine.planCampaigns(
            asset,
            map,
            candidatePaths,
            forecasts = forecastsBySymbol[asset.symbol].orEmpty()
        )
        val evidence = liveProvider.buildEvidenceFrame(asset)
        val gatedPlanned = gateAndLog(mergeFillState(planned, _uiState.value.campaigns))

        _uiState.value = _uiState.value.copy(
            selectedAsset = asset,
            levelMap = map,
            paths = candidatePaths,
            campaigns = gatedPlanned,
            selectedCampaign = gatedPlanned.firstOrNull(),
            liveEvidence = evidence
        )

        startActiveDeltaMode(asset)

        // Phase 3 (§8/§10): upgrade to the candle-driven level map + coin calibration
        // once loaded; the synchronous paint above keeps first response instant.
        viewModelScope.launch {
            if (_uiState.value.selectedAsset?.symbol == asset.symbol) {
                applyUniverseAndRecompute(_uiState.value.assets, asset, _uiState.value.isLiveUniverse)
            }
        }

        viewModelScope.launch {
            repository.logAction(
                asset = asset.symbol,
                campaignId = "ACTIVE_SWITCH",
                action = AuditLogAction.CAMPAIGN_STAGED,
                reasonCode = "SYMBOL_SELECTED",
                message = "Active delta stream routed to ${asset.symbol} (Regime: ${asset.regime.label})"
            )
        }
    }

    /** §5 Dormant Polling: refresh the screener universe on a light REST cadence. */
    private fun startDormantUniversePolling() {
        dormantPollingJob?.cancel()
        dormantPollingJob = viewModelScope.launch {
            liveProvider.dormantUniversePolling(load = { alphaPulseUniverseSnapshot() }).collect { snapshot ->
                val currentSymbol = _uiState.value.selectedAsset?.symbol
                val chosen = snapshot.assets.find { it.symbol == currentSymbol } ?: snapshot.assets.firstOrNull()
                if (chosen != null) {
                    applyUniverseAndRecompute(snapshot.assets, chosen, snapshot.isLive)
                }
            }
        }
    }

    private var dormantPollingJob: Job? = null

    fun selectCampaign(campaign: Campaign) {        _uiState.value = _uiState.value.copy(selectedCampaign = campaign)
    }

    fun dismissLiveModeGate() {
        _uiState.value = _uiState.value.copy(isLiveModeBlocked = false)
    }

    fun setExecutionMode(mode: ExecutionMode) {
        val isLiveMode = mode == ExecutionMode.CAPPED_LIVE || mode == ExecutionMode.SCALED_LIVE
        // §36: live modes require an unlocked in-memory API session. Gate with a
        // dialog instead of switching silently into a mode that cannot route orders.
        if (isLiveMode && !sessionManager.hasValidActiveSession()) {
            _uiState.value = _uiState.value.copy(isLiveModeBlocked = true)
            viewModelScope.launch {
                repository.logAction(
                    asset = _uiState.value.selectedAsset?.symbol ?: "ALL",
                    campaignId = "MODE_SWITCH",
                    action = AuditLogAction.CAMPAIGN_STAGED,
                    reasonCode = "LIVE_MODE_GATE",
                    message = "${mode.displayName} requires an active API session. Open Guardrails to enter your key."
                )
            }
            return
        }
        _uiState.value = _uiState.value.copy(executionMode = mode)
        if (isLiveMode) startLiveWalletPolling()
        viewModelScope.launch {
            repository.logAction(
                asset = _uiState.value.selectedAsset?.symbol ?: "ALL",
                campaignId = "MODE_SWITCH",
                action = AuditLogAction.CAMPAIGN_STAGED,
                reasonCode = "MODE_CHANGED",
                message = "Operational mode changed to ${mode.displayName}"
            )
            // §36: re-gate boards under the new mode and adjust live plumbing.
            val regated = gateAndLog(_uiState.value.campaigns)
            _uiState.value = _uiState.value.copy(campaigns = regated)
            if (mode == ExecutionMode.CAPPED_LIVE || mode == ExecutionMode.SCALED_LIVE) {
                routeLiveBoards(regated)
                startUserDataStreamIfNeeded()
            } else {
                userDataJob?.cancel()
                userDataJob = null
            }
        }
    }

    fun toggleKillSwitch() {
        val current = _uiState.value.riskEnvelope.isKillSwitchEngaged
        val updated = !current
        _uiState.value = _uiState.value.copy(
            riskEnvelope = _uiState.value.riskEnvelope.copy(isKillSwitchEngaged = updated),
            campaigns = if (updated) {
                // Flatten and suppress all active campaigns. §5 Paper mode: the kill
                // switch also closes open paper positions at the last traded price.
                val flattenPrice = _uiState.value.selectedAsset?.lastPrice ?: 0.0
                _uiState.value.campaigns.map { c ->
                    // §22/§27: engagement halts staging, so no slice may keep reading as
                    // resting — the venue sweep below cancels every order that exists.
                    var flattened = c.copy(
                        status = CampaignState.CANCELLED,
                        entryLadder = c.entryLadder.map { it.copy(isResting = false) }
                    )
                    if (_uiState.value.executionMode == ExecutionMode.PAPER &&
                        c.entryLadder.any { slice -> slice.isFilled }
                    ) {
                        paperRealizedPnlUsd += PaperPnl.realizedPnlAtFlatten(c, flattenPrice)
                        flattened = flattened.copy(
                            entryLadder = c.entryLadder.map { slice ->
                                slice.copy(isFilled = false, isResting = false)
                            }
                        )
                    }
                    flattened
                }
            } else _uiState.value.campaigns
        )
        if (updated) {
            // §22/§27: engagement is not just local — pull every venue order too, and
            // record what each cancel did, so no board keeps claiming an order rests.
            viewModelScope.launch {
                val creds = sessionManager.copyCredentials()
                if (creds != null) {
                    try {
                        for (asset in _uiState.value.campaigns.map { it.asset }.distinct()) {
                            val ids = routedClientIds.filter { it.startsWith(asset) }
                            if (ids.isNotEmpty()) {
                                liveOrderRecords.putAll(
                                    LiveOrderViewState.cancelAll(
                                        coordinator = liveOrders,
                                        symbol = asset,
                                        clientOrderIds = ids,
                                        apiKey = creds.first,
                                        secret = creds.second
                                    )
                                )
                            }
                            // Anything we did not stage ourselves still leaves the book.
                            liveOrders.cancelSymbol(asset, creds.first, creds.second)
                        }
                    } finally {
                        Arrays.fill(creds.first, '\u0000')
                        Arrays.fill(creds.second, '\u0000')
                    }
                }
                userDataJob?.cancel()
                userDataJob = null
                routedClientIds.clear()
                publishLiveOrderRecords()
            }
        }

        viewModelScope.launch {
            repository.logAction(
                asset = "PORTFOLIO",
                campaignId = "GLOBAL_KILL",
                action = AuditLogAction.EMERGENCY_KILL,
                reasonCode = if (updated) "KILL_SWITCH_ENGAGED" else "KILL_SWITCH_RESET",
                message = if (updated) "EMERGENCY KILL-SWITCH ENGAGED. All resting orders cancelled & staging halted."
                else "Kill-Switch disengaged. Normal supervisor rules restored."
            )
        }
    }

    fun triggerSoftCancel(campaignId: String) {
        val currentCampaigns = _uiState.value.campaigns.toMutableList()
        val index = currentCampaigns.indexOfFirst { it.id == campaignId }
        if (index != -1) {
            val c = currentCampaigns[index]
            val updated = c.copy(
                status = CampaignState.CANCELLED,
                entryLadder = c.entryLadder.map { it.copy(isResting = false) },
                invalidationScore = maxOf(48, c.invalidationScore)
            )
            currentCampaigns[index] = updated
            _uiState.value = _uiState.value.copy(
                campaigns = currentCampaigns,
                selectedCampaign = if (_uiState.value.selectedCampaign?.id == campaignId) updated else _uiState.value.selectedCampaign
            )

            viewModelScope.launch { cancelStagedOrders(c) }
            viewModelScope.launch {
                repository.logAction(
                    asset = c.asset,
                    campaignId = c.id,
                    action = AuditLogAction.SOFT_CANCEL,
                    reasonCode = "OPERATOR_SOFT_CANCEL",
                    message = "Unfilled resting slices cancelled for ${c.family.displayName} (Soft threshold hit)"
                )
            }
        }
    }

    fun triggerHardFlatten(campaignId: String) {
        val currentCampaigns = _uiState.value.campaigns.toMutableList()
        val index = currentCampaigns.indexOfFirst { it.id == campaignId }
        if (index != -1) {
            val c = currentCampaigns[index]
            // §5 Paper mode: an operator flatten closes the paper position — book
            // realized PnL at the hard stop before the fills are cleared below.
            if (_uiState.value.executionMode == ExecutionMode.PAPER &&
                c.entryLadder.any { it.isFilled }
            ) {
                val realized = PaperPnl.realizedPnlAtFlatten(c, c.stopLogic.hardStopPrice)
                paperRealizedPnlUsd += realized
                viewModelScope.launch {
                    repository.logAction(
                        asset = c.asset,
                        campaignId = c.id,
                        action = AuditLogAction.HARD_FLATTEN,
                        reasonCode = "PAPER_FLATTEN",
                        message = "${c.family.displayName}: operator flatten booked realized PnL $${"%.2f".format(realized)}"
                    )
                }
            }
            val updated = c.copy(
                status = CampaignState.CANCELLED,
                entryLadder = c.entryLadder.map { it.copy(isResting = false, isFilled = false) },
                invalidationScore = 85,
                cooldownUntilTs = System.currentTimeMillis() + (15 * 60 * 1000)
            )
            currentCampaigns[index] = updated
            _uiState.value = _uiState.value.copy(
                campaigns = currentCampaigns,
                selectedCampaign = if (_uiState.value.selectedCampaign?.id == campaignId) updated else _uiState.value.selectedCampaign
            )

            viewModelScope.launch {
                repository.logAction(
                    asset = c.asset,
                    campaignId = c.id,
                    action = AuditLogAction.HARD_FLATTEN,
                    reasonCode = "OPERATOR_HARD_FLATTEN",
                    message = "Campaign ${c.family.displayName} flattened immediately. Cooldown lockout armed (15m)."
                )
            }
        }
    }

    fun openManualOverrideDialog(campaign: Campaign) {
        _uiState.value = _uiState.value.copy(
            selectedCampaign = campaign,
            isOverrideDialogOpen = true,
            overrideWarnings = emptyList()
        )
    }

    fun closeManualOverrideDialog() {
        _uiState.value = _uiState.value.copy(
            isOverrideDialogOpen = false,
            overrideWarnings = emptyList()
        )
    }

    fun applyManualOverride(campaignId: String, newStopPrice: Double, newSizeBudget: Double) {
        val c = _uiState.value.campaigns.find { it.id == campaignId } ?: return

        // Evaluate guardrails
        val warnings = riskEngine.checkManualOverrideGuardrails(
            engineCalculatedStop = c.stopLogic.hardStopPrice,
            userStopPrice = newStopPrice,
            isLong = c.isLong,
            engineCalculatedSize = c.sizeBudgetUsd,
            userSizeBudget = newSizeBudget
        )

        val updated = c.copy(
            sizeBudgetUsd = newSizeBudget,
            stopLogic = c.stopLogic.copy(hardStopPrice = newStopPrice)
        )

        val updatedCampaigns = _uiState.value.campaigns.map { if (it.id == campaignId) updated else it }

        _uiState.value = _uiState.value.copy(
            campaigns = updatedCampaigns,
            selectedCampaign = updated,
            overrideWarnings = warnings,
            isOverrideDialogOpen = warnings.isNotEmpty() // keep open if persistent warnings need displaying
        )

        viewModelScope.launch {
            repository.logAction(
                asset = c.asset,
                campaignId = c.id,
                action = AuditLogAction.MANUAL_OVERRIDE,
                reasonCode = if (warnings.isNotEmpty()) "GUARDRAIL_OVERRIDE_WARNED" else "OVERRIDE_ACCEPTED",
                message = "Parameters manually overridden for ${c.family.displayName}: Stop=$newStopPrice, Size=$$newSizeBudget. Warnings: ${warnings.size}"
            )
        }
    }

    fun openOrphanedOrderWarning() {
        _uiState.value = _uiState.value.copy(isOrphanedOrderWarningOpen = true)
    }

    fun dismissOrphanedOrderWarning() {
        _uiState.value = _uiState.value.copy(isOrphanedOrderWarningOpen = false)
    }

    fun acknowledgeOrphanedOrderWarning() {
        _uiState.value = _uiState.value.copy(isOrphanedOrderWarningOpen = false)
        // Suspension wipe: immediately wipe API keys from RAM
        sessionManager.terminateSessionAndWipeRam()
        viewModelScope.launch {
            repository.logAction(
                asset = _uiState.value.selectedAsset?.symbol ?: "PORTFOLIO",
                campaignId = "SYS_SECURITY",
                action = AuditLogAction.ORPHANED_ALERT,
                reasonCode = "SUSPENSION_WIPE",
                message = "App lifecycle suspended. API keys securely wiped from RAM per Zero-Persistence mandate."
            )
        }
    }

    /**
     * Phase 1 (Spec §3 Orphaned Order OS Intercept): invoked by MainActivity's lifecycle
     * observer when the app is leaving the foreground with live API credentials.
     *
     * Decision logic is delegated to [OrphanInterceptPolicy] (pure, unit-tested):
     *  - No active session → nothing to orphan; no action.
     *  - Session active but no resting orders → wipe keys immediately (§3 suspension policy).
     *  - Session active with resting orders → full-screen intercept is mandatory.
     */
    fun onAppBackgrounded() {
        when (
            OrphanInterceptPolicy.decide(
                hasActiveSession = sessionManager.hasValidActiveSession(),
                hasRestingOrders = OrphanInterceptPolicy.hasRestingOrLiveOrders(_uiState.value.campaigns)
            )
        ) {
            OrphanInterceptDecision.SHOW_INTERCEPT -> openOrphanedOrderWarning()
            OrphanInterceptDecision.WIPE_ONLY -> {
                sessionManager.terminateSessionAndWipeRam()
                viewModelScope.launch {
                    repository.logAction(
                        asset = "PORTFOLIO",
                        campaignId = "SYS_SECURITY",
                        action = AuditLogAction.ORPHANED_ALERT,
                        reasonCode = "SUSPENSION_WIPE",
                        message = "App backgrounded without resting orders. Session keys wiped from RAM."
                    )
                }
            }
            OrphanInterceptDecision.NO_ACTION -> Unit
        }
    }

    fun startApiSession(key: String, secret: String) {
        sessionManager.startSession(key, secret)
        // Real Binance wallet becomes visible as soon as a session is unlocked.
        startLiveWalletPolling()
        viewModelScope.launch {
            repository.logAction(
                asset = "PORTFOLIO",
                campaignId = "AUTH_SESSION",
                action = AuditLogAction.CAMPAIGN_STAGED,
                reasonCode = "SESSION_STARTED",
                message = "Encrypted in-memory trade session unlocked. Zero disk persistence confirmed."
            )
        }
    }

    fun terminateApiSession() {
        liveWalletJob?.cancel()
        liveWalletJob = null
        sessionManager.terminateSessionAndWipeRam()
        _uiState.value = _uiState.value.copy(liveWalletUsd = null)
        viewModelScope.launch {
            repository.logAction(
                asset = "PORTFOLIO",
                campaignId = "AUTH_SESSION",
                action = AuditLogAction.CAMPAIGN_STAGED,
                reasonCode = "SESSION_TERMINATED",
                message = "API credentials expunged from memory."
            )
        }
    }

    /**
     * §37: scenario runs are derived from real candle history through the deterministic
     * ReplayEngine — never from invented literals. When persisted history is
     * insufficient the run degrades to the scenario's documented posture and says so;
     * it never fabricates a live reading.
     */
    fun runSimulationScenario(scenario: SimulationScenario) {
        if (_uiState.value.isReplayRunning) return
        _uiState.value = _uiState.value.copy(selectedScenario = scenario, isReplayRunning = true)
        viewModelScope.launch {
            try {
                val asset = _uiState.value.selectedAsset
                val candles = asset?.let {
                    runCatching { universeScreener.loadTimeframes(it.symbol).candles1h }.getOrDefault(emptyList())
                }.orEmpty()
                val hadHistory = asset != null && candles.size >= MIN_SCENARIO_CANDLES
                val metrics = if (hadHistory && asset != null) {
                    val calibration = runCatching { universeScreener.loadCalibration(asset) }.getOrNull()
                    replayEngine.replay(
                        asset = asset,
                        candles = candles,
                        fundingRatePct = asset.fundingRatePct,
                        spreadPct = asset.orderBookSpreadPct,
                        calibration = calibration
                    ).metrics
                } else {
                    // §37 honest degradation: no persisted history → no invented numbers.
                    // The audit trail records exactly why the metrics stay neutral.
                    BacktestMetrics()
                }
                _uiState.value = _uiState.value.copy(backtestMetrics = metrics, isReplayRunning = false)
                repository.logAction(
                    asset = _uiState.value.selectedAsset?.symbol ?: "SIMULATION",
                    campaignId = scenario.id,
                    action = AuditLogAction.CAMPAIGN_STAGED,
                    reasonCode = if (hadHistory) "SCENARIO_EXECUTED" else "SCENARIO_DEGRADED",
                    message = if (hadHistory) {
                            "Scenario replayed over ${candles.size} persisted 1h candles: ${scenario.name}. " +
                                "Captured move: ${metrics.capturedMoveBeforeConfirmationPct}%"
                        } else {
                            "Scenario ${scenario.name} had insufficient candle history " +
                                "(${candles.size} < $MIN_SCENARIO_CANDLES); metrics stayed neutral " +
                                "instead of inventing numbers (§37)"
                        }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isReplayRunning = false)
                logLiveNote(
                    scenario.id,
                    "SCENARIO_FAILED",
                    "Scenario replay failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * §2/§33: hardware + mandatory-feed latency are re-sampled on a fixed cadence so
     * stale-feed, latency, RAM, thermal and low-power guardrails always act on live
     * health instead of a startup-only snapshot. feedStaleness is derived from the
     * latest probes inside SessionSecurityManager.
     */
    private fun startDiagnosticsPolling() {
        viewModelScope.launch {
            while (true) {
                delay(DIAGNOSTICS_RE_SAMPLE_MS)
                runPreFlightDiagnostics()
            }
        }
    }

    private fun startActiveDeltaMode(asset: CryptoAsset) {
        activeDeltaJob?.cancel()
        orderBookJob?.cancel()
        takerFlowJob?.cancel()

        // 1. Live price and micro-delta updates (real WS with simulated fallback)
        activeDeltaJob = viewModelScope.launch {
            liveProvider.activeDeltaStream(asset).collect { updatedAsset ->
                val previousCampaigns = _uiState.value.campaigns
                val currentLevelMap = _uiState.value.levelMap ?: structureEngine.buildLevelMap(updatedAsset)
                val evidence = liveProvider.buildEvidenceFrame(updatedAsset)

                // Run live invalidation evaluations across all staged/active campaigns
                val updatedCampaigns = _uiState.value.campaigns.map { campaign ->
                    val result = invalidationEngine.evaluateCampaign(
                        campaign,
                        updatedAsset,
                        evidence,
                        currentLevelMap
                    )
                    if (result.triggerAction != InvalidationAction.NO_ACTION) {
                        repository.logAction(
                            asset = updatedAsset.symbol,
                            campaignId = campaign.id,
                            action = if (result.triggerAction == InvalidationAction.HARD_FLATTEN_COOLDOWN)
                                AuditLogAction.HARD_FLATTEN else AuditLogAction.SOFT_CANCEL,
                            reasonCode = result.triggerAction.name,
                            message = "${campaign.family.displayName}: ${result.primaryReason}"
                        )
                    }
                    result.updatedCampaign
                }

                // §36 Paper mode: internal fill simulation of resting slices vs live price.
                var working = updatedCampaigns
                if (_uiState.value.executionMode == ExecutionMode.PAPER) {
                    working = working.map { c ->
                        val filled = executionEngine.simulatePaperFills(c, updatedAsset.lastPrice)
                        if (filled != c) {
                            repository.logAction(
                                asset = c.asset,
                                campaignId = c.id,
                                action = AuditLogAction.SLICE_FILLED,
                                reasonCode = "PAPER_FILL",
                                message = "${c.family.displayName}: slice filled internally at ${updatedAsset.lastPrice}"
                            )
                        }
                        filled
                    }

                    // §5 Paper wallet accounting: a board that just went CANCELLED while
                    // still holding fills was hard-flattened by the invalidation engine —
                    // book realized PnL at the hard stop and release its exposure. The
                    // realized R total feeds the daily-loss stop (currentRealizedLossR).
                    working = working.map { c ->
                        val before = previousCampaigns.find { it.id == c.id }
                        val hadFills = before?.entryLadder?.any { it.isFilled } == true
                        if (hadFills && c.status == CampaignState.CANCELLED &&
                            c.entryLadder.any { it.isFilled }
                        ) {
                            val realized = PaperPnl.realizedPnlAtFlatten(c, c.stopLogic.hardStopPrice)
                            paperRealizedPnlUsd += realized
                            repository.logAction(
                                asset = c.asset,
                                campaignId = c.id,
                                action = AuditLogAction.HARD_FLATTEN,
                                reasonCode = "PAPER_FLATTEN",
                                message = "${c.family.displayName}: paper position flattened at stop ${c.stopLogic.hardStopPrice}; realized PnL $${"%.2f".format(realized)}"
                            )
                            c.copy(entryLadder = c.entryLadder.map { it.copy(isFilled = false, isResting = false) })
                        } else c
                    }
                }

                // §26 Board promotion: a dead primary upgrades healthy secondaries.
                val primaryDied = previousCampaigns.any { prior ->
                    prior.role == BoardRole.PRIMARY &&
                        prior.status != CampaignState.CANCELLED &&
                        working.find { it.id == prior.id }?.status == CampaignState.CANCELLED
                }
                if (primaryDied) {
                    working = working.map { c ->
                        val promoted = invalidationEngine.checkBoardPromotion(c, isPrimaryDead = true)
                        if (promoted != c) {
                            repository.logAction(
                                asset = c.asset,
                                campaignId = c.id,
                                action = AuditLogAction.BOARD_PROMOTED,
                                reasonCode = "SECONDARY_PROMOTED",
                                message = "${c.family.displayName} promoted to Primary after primary board decayed"
                            )
                        }
                        promoted
                    }
                }

                // §26 Rearm: cooldown lapsed and the level still exists -> re-arm.
                val envelopeAllowsRearm = !_uiState.value.riskEnvelope.isKillSwitchEngaged &&
                    !_uiState.value.riskEnvelope.isDailyLossExceeded
                if (envelopeAllowsRearm) {
                    working = working.map { c ->
                        val rearm = invalidationEngine.evaluateRearm(c, currentLevelMap, updatedAsset)
                        if (rearm.triggerAction == InvalidationAction.REARM_CAMPAIGN) {
                            repository.logAction(
                                asset = c.asset,
                                campaignId = c.id,
                                action = AuditLogAction.CAMPAIGN_STAGED,
                                reasonCode = "REARM_CAMPAIGN",
                                message = "${c.family.displayName}: ${rearm.primaryReason}"
                            )
                        }
                        rearm.updatedCampaign
                    }
                }

                // §28 Mirror activation: a hard-invalidated board wakes its suppressed mirror.
                working = working.map { c ->
                    val mirrorId = c.mirrorCampaignId ?: return@map c
                    val mirror = working.find { it.id == mirrorId } ?: return@map c
                    val activated = invalidationEngine.activateMirrorOnTrap(c, mirror) ?: return@map c
                    repository.logAction(
                        asset = activated.asset,
                        campaignId = activated.id,
                        action = AuditLogAction.DEFENSIVE_SCALED,
                        reasonCode = "MIRROR_ACTIVATED",
                        message = "${activated.family.displayName} activated defensively after mirror hard invalidation"
                    )
                    activated
                }

                val worstCaseR = riskEngine.computeWorstCaseStopOutR(
                    working,
                    _uiState.value.riskEnvelope.accountEquityUsd
                )

                // §5 Paper wallet: exposure + PnL derived from the actual ladder fills
                // (never invented). Realized losses convert to R against the configured
                // per-campaign risk budget so the daily-loss stop (isDailyLossExceeded)
                // becomes a live gate in paper mode.
                val envelope = _uiState.value.riskEnvelope
                val updatedEnvelope = if (_uiState.value.executionMode == ExecutionMode.PAPER) {
                    val realizedR = PaperPnl.pnlToR(
                        paperRealizedPnlUsd,
                        envelope.accountEquityUsd,
                        envelope.riskPerCampaignPct
                    )
                    envelope.copy(
                        currentGrossExposureUsd = PaperPnl.openExposureUsd(working),
                        dailyPnLUsd = paperRealizedPnlUsd +
                            PaperPnl.unrealizedPnlUsd(working, updatedAsset.lastPrice),
                        currentRealizedLossR = Math.round(minOf(0.0, realizedR) * 100.0) / 100.0,
                        worstCaseStopOutR = worstCaseR
                    )
                } else {
                    envelope.copy(worstCaseStopOutR = worstCaseR)
                }

                _uiState.value = _uiState.value.copy(
                    selectedAsset = updatedAsset,
                    liveEvidence = evidence,
                    campaigns = working,
                    selectedCampaign = working.find { it.id == _uiState.value.selectedCampaign?.id } ?: working.firstOrNull(),
                    riskEnvelope = updatedEnvelope
                )

                // §22/§33: push cancellations to the venue and reconcile the live position.
                handleLiveExecution(previousCampaigns, updatedAsset)
            }
        }

        // 2. Order book stream (real best bid/ask with synthesized depth ladder)
        orderBookJob = viewModelScope.launch {
            liveProvider.orderBookStream(asset).collect { ob ->
                _uiState.value = _uiState.value.copy(orderBook = ob)
            }
        }

        // 3. Taker trades stream (real aggTrade tape with simulated fallback)
        takerFlowJob = viewModelScope.launch {
            val list = mutableListOf<TakerTrade>()
            liveProvider.takerTradeStream(asset).collect { trade ->
                list.add(0, trade)
                if (list.size > 20) list.removeAt(list.size - 1)
                _uiState.value = _uiState.value.copy(takerTrades = list.toList())
            }
        }
    }

    // ------------------------------------------------------------- Phase 4 execution

    /** Runs the staging gate (risk validation + ExecutionMode + §33 rejects), logging notes. */
    private fun gateAndLog(campaigns: List<Campaign>): List<Campaign> {
        val state = _uiState.value
        val gated = executionEngine.gateStaging(
            campaigns,
            state.executionMode,
            state.riskEnvelope,
            state.venueHealth,
            sessionManager.hasValidActiveSession()
        )
        val feedStale = ExecutionGuardrails.staleFeedAction(state.venueHealth) ==
            ExecutionGuardrails.StaleFeedAction.SUPPRESS_AND_TRIM
        val latencySpike = state.venueHealth.isLatencySpike
        val spreadBlown = state.selectedAsset?.let { ExecutionGuardrails.isSpreadBlowout(it) } == true
        return gated.map { g ->
            var campaign = g.campaign
            var note = g.note
            // §33: order-reject threshold pauses staging for that asset.
            if (ExecutionGuardrails.shouldPauseVenue(consecutiveRejectsBySymbol[campaign.asset] ?: 0)) {
                campaign = campaign.copy(status = CampaignState.SUPPRESSED)
                note = "Venue paused for ${campaign.asset}: order-reject threshold exceeded"
            }
            // §33: stale feed suppresses staging and cancels nonessential resting orders.
            if (feedStale && campaign.status == CampaignState.STAGED) {
                campaign = ExecutionGuardrails.trimNonessentialRestingOrders(listOf(campaign)).first()
                note = "Data feed stale: staging suppressed, nonessential resting orders trimmed"
            }
            // §33: exchange latency spike pauses stop-entry arming (stop ladders stay down).
            if (latencySpike && campaign.status == CampaignState.STAGED &&
                campaign.entryLadder.any { it.orderType != OrderType.PASSIVE_LIMIT }
            ) {
                campaign = campaign.copy(status = CampaignState.SUPPRESSED)
                note = "Exchange latency spike: stop-entry arming paused"
            }
            // §33: spread above the volatility-adjusted limit disables passive staging.
            if (spreadBlown && campaign.status == CampaignState.STAGED &&
                campaign.entryLadder.isNotEmpty() &&
                campaign.entryLadder.all { it.orderType == OrderType.PASSIVE_LIMIT }
            ) {
                campaign = campaign.copy(status = CampaignState.SUPPRESSED)
                note = "Spread exceeds volatility-adjusted limit: passive staging disabled"
            }
            if (note != null) {
                viewModelScope.launch {
                    repository.logAction(
                        asset = campaign.asset,
                        campaignId = campaign.id,
                        action = AuditLogAction.CAMPAIGN_STAGED,
                        reasonCode = "STAGING_GATE_${state.executionMode.name}",
                        message = "${campaign.family.displayName}: $note"
                    )
                }
            }
            campaign
        }
    }

    /** Preserves exchange/paper-reconciled fill state across level-map recomputes (§33). */
    private fun mergeFillState(fresh: List<Campaign>, previous: List<Campaign>): List<Campaign> =
        fresh.map { c ->
            val prior = previous.find { it.id == c.id }
            if (prior == null || prior.entryLadder.none { it.isFilled }) {
                c
            } else {
                val mergedLadder = c.entryLadder.map { slice ->
                    val priorSlice = prior.entryLadder.find { it.sliceIndex == slice.sliceIndex }
                    if (priorSlice != null && priorSlice.isFilled) {
                        slice.copy(isFilled = true, isResting = false)
                    } else slice
                }
                val anyFilled = mergedLadder.any { it.isFilled }
                c.copy(
                    entryLadder = mergedLadder,
                    status = if (anyFilled) executionEngine.statusAfterFills(mergedLadder) else c.status
                )
            }
        }

    /** §22/§36: stages and routes resting slices for live modes with a valid session. */
    private suspend fun routeLiveBoards(campaigns: List<Campaign>) {
        val mode = _uiState.value.executionMode
        if (mode != ExecutionMode.CAPPED_LIVE && mode != ExecutionMode.SCALED_LIVE) return
        if (!sessionManager.hasValidActiveSession()) return
        val toRoute = campaigns.filter { it.status == CampaignState.STAGED }
        if (toRoute.isEmpty()) return

        val plan = orderStager.plan(
            campaigns = toRoute,
            mode = mode,
            envelope = _uiState.value.riskEnvelope,
            venueHealth = _uiState.value.venueHealth,
            hasActiveSession = true,
            alreadyStaged = routedClientIds
        )
        // §33: a guardrail refusal is a recorded decision, never a silent drop.
        plan.rejections.forEach { rejection ->
            liveOrderRecords[rejection.clientOrderId] = LiveOrderRecord(
                clientOrderId = rejection.clientOrderId,
                leg = LiveOrderViewState.legOf(rejection.clientOrderId),
                status = LiveOrderStatus.REJECTED,
                reason = rejection.reason
            )
            logLiveNote(
                rejection.campaignId,
                "GUARDRAIL_BLOCKED",
                "slice ${rejection.sliceIndex} not staged: ${rejection.reason}"
            )
        }
        if (plan.isEmpty) return

        val creds = sessionManager.copyCredentials() ?: return
        var rejects = 0
        try {
            val submissions = liveOrders.submit(plan, creds.first, creds.second)
            for (submission in submissions) {
                val intent = submission.intent
                liveOrderRecords[intent.clientOrderId] =
                    LiveOrderViewState.recordFor(intent, submission.ok, submission.message)
                if (submission.ok) {
                    routedClientIds += intent.clientOrderId
                    logLiveNote(
                        intent.campaignId,
                        "ORDER_PLACED",
                        "${intent.side} slice ${intent.sliceIndex} routed at ${intent.price} " +
                            "(client ${intent.clientOrderId}, TP ${intent.takeProfitPrice})"
                    )
                } else {
                    rejects++
                    logLiveNote(
                        intent.campaignId,
                        "ORDER_REJECTED",
                        "slice ${intent.sliceIndex} rejected: ${submission.message ?: "unknown"}"
                    )
                }
            }
        } finally {
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
        }
        publishLiveOrderRecords()
        val symbol = toRoute.first().asset
        consecutiveRejectsBySymbol[symbol] =
            if (rejects > 0) (consecutiveRejectsBySymbol[symbol] ?: 0) + rejects else 0
    }

    /** §27: cancels this campaign's staged orders, resolving the fill-beats-cancel race. */
    private suspend fun cancelStagedOrders(campaign: Campaign) {
        if (!sessionManager.hasValidActiveSession()) return
        val creds = sessionManager.copyCredentials() ?: return
        try {
            for (slice in campaign.entryLadder.filter { !it.isFilled }) {
                val entryId = ClientOrderIds.forSlice(campaign.id, slice.sliceIndex)
                val tpId = ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex)
                val legs = buildList {
                    if (slice.isResting) add(entryId to LiveLeg.ENTRY)
                    // Only an exit we actually staged is worth cancelling.
                    if (tpId in routedClientIds) add(tpId to LiveLeg.TAKE_PROFIT)
                }
                for ((clientId, leg) in legs) {
                    when (val outcome = liveOrders.cancelOne(campaign.asset, clientId, creds.first, creds.second)) {
                        is LiveOrderCoordinator.CancelOutcome.Cancelled -> {
                            routedClientIds -= clientId
                            liveOrderRecords[clientId] =
                                LiveOrderRecord(clientId, leg, LiveOrderStatus.CANCELLED, "Cancelled at venue")
                            logLiveNote(campaign.id, "ORDER_CANCELLED", "$clientId cancelled at venue")
                        }
                        is LiveOrderCoordinator.CancelOutcome.AlreadyFilled -> {
                            routedClientIds -= clientId
                            liveOrderRecords[clientId] = LiveOrderRecord(
                                clientId,
                                leg,
                                LiveOrderStatus.FILLED,
                                LiveOrderViewState.FILLED_BEFORE_CANCEL
                            )
                            logLiveNote(campaign.id, "FILL_BEATS_CANCEL", "$clientId filled before cancellation")
                        }
                        is LiveOrderCoordinator.CancelOutcome.Failed -> {
                            liveOrderRecords[clientId] = LiveOrderRecord(
                                clientId,
                                leg,
                                LiveOrderStatus.CANCEL_FAILED,
                                "Cancel refused: ${outcome.reason}"
                            )
                            logLiveNote(campaign.id, "ORDER_CANCEL_FAILED", "$clientId: ${outcome.reason}")
                        }
                    }
                }
            }
        } finally {
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
            publishLiveOrderRecords()
        }
    }

    /**
     * §27: operator cancel for one staged order from the campaign card.
     *
     * Routed through the same [LiveOrderCoordinator.cancelOne]; a cancel that loses
     * the race to a fill is booked as a fill, not as a cancellation or an error.
     */
    fun cancelLiveOrder(clientOrderId: String) {
        val record = liveOrderRecords[clientOrderId]
            ?: LiveOrderRecord(clientOrderId, LiveOrderViewState.legOf(clientOrderId), LiveOrderStatus.STAGED)
        val campaign = _uiState.value.campaigns.firstOrNull { clientOrderId.startsWith(it.id) }
            ?: return
        viewModelScope.launch {
            if (!sessionManager.hasValidActiveSession()) {
                logLiveNote(campaign.id, "CANCEL_NO_SESSION", "Cannot cancel $clientOrderId: no API session")
                return@launch
            }
            val creds = sessionManager.copyCredentials() ?: return@launch
            try {
                val outcome = LiveOrderViewState.cancel(
                    coordinator = liveOrders,
                    symbol = campaign.asset,
                    clientOrderId = clientOrderId,
                    leg = record.leg,
                    apiKey = creds.first,
                    secret = creds.second
                )
                liveOrderRecords[clientOrderId] = outcome
                when (outcome.status) {
                    LiveOrderStatus.CANCELLED -> {
                        routedClientIds -= clientOrderId
                        logLiveNote(campaign.id, "ORDER_CANCELLED", "$clientOrderId cancelled at venue")
                    }
                    LiveOrderStatus.FILLED -> {
                        routedClientIds -= clientOrderId
                        markSliceFilled(campaign, clientOrderId, record.leg)
                        logLiveNote(campaign.id, "FILL_BEATS_CANCEL", "$clientOrderId filled before cancellation")
                    }
                    LiveOrderStatus.CANCEL_FAILED ->
                        logLiveNote(campaign.id, "ORDER_CANCEL_FAILED", "$clientOrderId: ${outcome.reason}")
                    else -> Unit
                }
            } finally {
                Arrays.fill(creds.first, '\u0000')
                Arrays.fill(creds.second, '\u0000')
                publishLiveOrderRecords()
            }
        }
    }

    /** Books a fill the venue reported while we asked to cancel (§27). */
    private fun markSliceFilled(campaign: Campaign, clientOrderId: String, leg: LiveLeg) {
        val sliceIndex = campaign.entryLadder.firstOrNull { slice ->
            val id = if (leg == LiveLeg.TAKE_PROFIT) {
                ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex)
            } else {
                ClientOrderIds.forSlice(campaign.id, slice.sliceIndex)
            }
            id == clientOrderId
        }?.sliceIndex ?: return
        val ladder = campaign.entryLadder.map { slice ->
            when {
                slice.sliceIndex != sliceIndex -> slice
                leg == LiveLeg.TAKE_PROFIT ->
                    slice.copy(isFilled = true, isResting = false, isClosedAtTarget = true)
                else -> slice.copy(isFilled = true, isResting = false)
            }
        }
        val filled = ladder.filter { it.isFilled }
        val status = when {
            filled.isEmpty() -> campaign.status
            filled.all { it.isClosedAtTarget } -> CampaignState.COMPLETED
            else -> CampaignState.PARTIALLY_FILLED
        }
        val updated = campaign.copy(entryLadder = ladder, status = status)
        _uiState.value = _uiState.value.copy(
            campaigns = _uiState.value.campaigns.map { if (it.id == campaign.id) updated else it },
            selectedCampaign = if (_uiState.value.selectedCampaign?.id == campaign.id) {
                updated
            } else {
                _uiState.value.selectedCampaign
            }
        )
    }

    /** Publishes the coordinator records the campaign card renders (idempotent). */
    private fun publishLiveOrderRecords() {
        _uiState.value = _uiState.value.copy(
            liveOrderRecords = liveOrderRecords.toMap(),
            liveOpenClientIds = routedClientIds.toSet()
        )
    }

    /**
     * §25: operator-triggered reconcile, so venue state is recoverable on demand and
     * not only after the user-data stream drops.
     */
    fun resyncLiveOrders() {
        val mode = _uiState.value.executionMode
        if (mode != ExecutionMode.CAPPED_LIVE && mode != ExecutionMode.SCALED_LIVE) return
        viewModelScope.launch { reconcileAllLiveSymbols() }
    }

    /** §25: recovers order state from REST after a listen-key gap or ambiguous cancel. */
    private suspend fun reconcileStagedOrders(symbol: String) {
        if (!sessionManager.hasValidActiveSession()) return
        val creds = sessionManager.copyCredentials() ?: return
        try {
            val result = liveOrders.reconcile(
                symbol = symbol,
                campaigns = _uiState.value.campaigns,
                apiKey = creds.first,
                secret = creds.second,
                staged = routedClientIds.toSet()
            )
            routedClientIds.clear()
            routedClientIds.addAll(result.openClientOrderIds)
            liveOrderRecords.putAll(LiveOrderViewState.reconcileRecords(result))
            _uiState.value = _uiState.value.copy(
                lastReconcile = ReconcileOutcome(
                    symbol = symbol,
                    openCount = result.openClientOrderIds.size,
                    vanishedCount = result.vanishedClientOrderIds.size,
                    closedAtTargetCount = result.closedClientOrderIds.size,
                    atMillis = System.currentTimeMillis()
                )
            )
            if (result.closedClientOrderIds.isNotEmpty()) {
                logLiveNote(
                    symbol,
                    "TAKE_PROFIT_FILLED",
                    "${result.closedClientOrderIds.size} take-profit(s) closed at target after stream gap"
                )
            }
            _uiState.value = _uiState.value.copy(campaigns = result.campaigns)
            if (result.vanishedClientOrderIds.isNotEmpty()) {
                logLiveNote(
                    symbol,
                    "ORDER_STATE_RECONCILED",
                    "${result.vanishedClientOrderIds.size} order(s) no longer resting after stream gap"
                )
            }
            publishLiveOrderRecords()
        } finally {
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
        }
    }

    /** §25: opens the user-data stream so exchange fills reach the resync reconcile path. */
    private fun startUserDataStreamIfNeeded() {
        val mode = _uiState.value.executionMode
        if (mode != ExecutionMode.CAPPED_LIVE && mode != ExecutionMode.SCALED_LIVE) return
        if (!sessionManager.hasValidActiveSession()) return
        if (userDataJob != null) return
        userDataJob = viewModelScope.launch {
            val loop = UserDataStreamLoop(
                orders = liveOrders,
                stream = { listenKey -> BinanceUserDataClient.streamUserEvents(listenKey) },
                shouldRun = {
                    val current = _uiState.value.executionMode
                    (current == ExecutionMode.CAPPED_LIVE || current == ExecutionMode.SCALED_LIVE) &&
                        sessionManager.hasValidActiveSession()
                },
                credentials = { sessionManager.copyCredentials() },
                reconcileSymbol = { symbol -> reconcileStagedOrders(symbol) },
                reconcileAll = { reconcileAllLiveSymbols() },
                note = { code, message -> logLiveNote("PORTFOLIO", code, message) }
            )
            try {
                loop.run()
            } finally {
                // §25: a mode change, a sign-out or a teardown stops the stream, so the
                // venue-side key is released here instead of being left to expire.
                withContext(NonCancellable) { loop.releaseKey() }
            }
        }
    }

    /** §25: REST reconcile over every symbol the boards touch; stream gaps and resync share it. */
    private suspend fun reconcileAllLiveSymbols() {
        _uiState.value.campaigns.map { it.asset }.distinct().forEach { symbol ->
            reconcileStagedOrders(symbol)
        }
    }

    /** §22/§33: venue cancels for newly-dead boards + throttled position reconciliation. */
    private suspend fun handleLiveExecution(previous: List<Campaign>, asset: CryptoAsset) {
        val mode = _uiState.value.executionMode
        if (mode != ExecutionMode.CAPPED_LIVE && mode != ExecutionMode.SCALED_LIVE) return
        if (!sessionManager.hasValidActiveSession()) return

        for (c in _uiState.value.campaigns) {
            val prior = previous.find { it.id == c.id } ?: continue
            val wasLive = prior.status == CampaignState.STAGED ||
                prior.status == CampaignState.PARTIALLY_FILLED ||
                prior.status == CampaignState.ACTIVE
            val nowDead = c.status == CampaignState.CANCELLED || c.status == CampaignState.SUPPRESSED
            if (wasLive && nowDead && prior.entryLadder.any { it.isResting && !it.isFilled }) {
                val creds = sessionManager.copyCredentials() ?: continue
                val cancelled = BinanceSignedClient.cancelAllOpenOrders(c.asset, creds.first, creds.second)
                Arrays.fill(creds.first, '\u0000')
                Arrays.fill(creds.second, '\u0000')
                logLiveNote(
                    c.id,
                    if (cancelled) "VENUE_CANCELLED" else "VENUE_CANCEL_FAILED",
                    "${c.family.displayName}: resting orders ${if (cancelled) "cancelled at venue" else "cancel failed at venue"}"
                )
            }
        }

        // §33: unexpected position mismatch check, throttled to one poll per 30 s.
        val now = System.currentTimeMillis()
        if (now - lastPositionCheckMs > 30_000) {
            lastPositionCheckMs = now
            val creds = sessionManager.copyCredentials()
            if (creds != null) {
                val exchange = BinanceSignedClient.fetchPositionRisk(asset.symbol, creds.first, creds.second)
                Arrays.fill(creds.first, '\u0000')
                Arrays.fill(creds.second, '\u0000')
                if (exchange != null) {
                    val expected = ExecutionGuardrails.expectedPositionQty(_uiState.value.campaigns, asset.lastPrice)
                    if (ExecutionGuardrails.positionMismatchAction(expected, exchange.positionAmt) ==
                        ExecutionGuardrails.PositionMismatchAction.FLATTEN_AND_LOCK
                    ) {
                        flattenAndLock(asset, expected, exchange.positionAmt)
                    }
                }
            }
        }
    }

    /** §33: unexpected position mismatch — flatten symbol, lock until reconciliation. */
    private suspend fun flattenAndLock(asset: CryptoAsset, expectedQty: Double, exchangeQty: Double) {
        val creds = sessionManager.copyCredentials()
        if (creds != null) {
            BinanceSignedClient.cancelAllOpenOrders(asset.symbol, creds.first, creds.second)
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
        }
        _uiState.value = _uiState.value.copy(
            riskEnvelope = _uiState.value.riskEnvelope.copy(isKillSwitchEngaged = true),
            campaigns = _uiState.value.campaigns.map { campaign ->
                if (campaign.asset == asset.symbol) {
                    campaign.copy(
                        status = CampaignState.CANCELLED,
                        entryLadder = campaign.entryLadder.map { it.copy(isResting = false) }
                    )
                } else campaign
            }
        )
        repository.logAction(
            asset = asset.symbol,
            campaignId = "POSITION_GUARD",
            action = AuditLogAction.EMERGENCY_KILL,
            reasonCode = "POSITION_MISMATCH_LOCK",
            message = "Unexpected position mismatch (expected $expectedQty, exchange $exchangeQty). Symbol flattened and locked until reconciliation."
        )
    }

    /**
     * Phase 5 (§37): deterministic replay of the proactive pipeline over persisted
     * candle history for the selected asset. `baseline = true` reruns it with the
     * asymmetric overlay disabled to quantify the proactive edge.
     */
    fun runReplay(baseline: Boolean = false) {
        val asset = _uiState.value.selectedAsset ?: return
        if (_uiState.value.isReplayRunning) return
        _uiState.value = _uiState.value.copy(isReplayRunning = true)
        viewModelScope.launch {
            try {
                val timeframes = runCatching { universeScreener.loadTimeframes(asset.symbol) }.getOrNull()
                val candles = timeframes?.candles1h.orEmpty()
                if (candles.size < 30) {
                    logLiveNote(
                        "REPLAY",
                        "REPLAY_INSUFFICIENT_DATA",
                        "Replay needs >=30 persisted 1h candles for ${asset.symbol}; found ${candles.size}"
                    )
                    _uiState.value = _uiState.value.copy(isReplayRunning = false)
                    return@launch
                }
                val calibration = runCatching { universeScreener.loadCalibration(asset) }.getOrNull()
                var result = replayEngine.replay(
                    asset = asset,
                    candles = candles,
                    fundingRatePct = asset.fundingRatePct,
                    spreadPct = asset.orderBookSpreadPct,
                    calibration = calibration,
                    baselineMode = baseline
                )
                // §37: quantify the proactive edge against the symmetric-only baseline.
                if (!baseline) {
                    val symmetric = replayEngine.replay(
                        asset = asset,
                        candles = candles,
                        fundingRatePct = asset.fundingRatePct,
                        spreadPct = asset.orderBookSpreadPct,
                        calibration = calibration,
                        baselineMode = true
                    )
                    val base = symmetric.metrics.totalPnlUsd
                    val edge = if (Math.abs(base) > 0.01) {
                        (result.metrics.totalPnlUsd - base) / Math.abs(base) * 100.0
                    } else 0.0
                    result = result.copy(
                        metrics = result.metrics.copy(proactiveEdgeVsBaselinePct = Math.round(edge * 10.0) / 10.0)
                    )
                }
                _uiState.value = _uiState.value.copy(
                    lastReplay = result,
                    backtestMetrics = result.metrics,
                    isReplayRunning = false
                )
                repository.logAction(
                    asset = asset.symbol,
                    campaignId = if (baseline) "REPLAY_BASELINE" else "REPLAY",
                    action = AuditLogAction.CAMPAIGN_STAGED,
                    reasonCode = "REPLAY_EXECUTED",
                    message = "Historical replay over ${candles.size} 1h candles: ${result.metrics.replayedCampaigns} boards, " +
                        "PnL $${result.metrics.totalPnlUsd}, miss rate ${result.metrics.orderMissRatePct}%"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isReplayRunning = false)
                logLiveNote(
                    "REPLAY",
                    "REPLAY_FAILED",
                    "Replay failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    // ------------------------------------------------------- paper wallet & live wallet

    /** Loads the persisted paper-wallet config; resets the fake demo PnL/exposure to zero. */
    private fun applyStoredPaperWallet() {
        _uiState.value = _uiState.value.copy(
            riskEnvelope = RiskEnvelope(
                accountEquityUsd = paperPrefs.getFloat(KEY_PAPER_EQUITY, 50_000f).toDouble(),
                riskPerCampaignPct = paperPrefs.getFloat(KEY_PAPER_RISK_PCT, 0.5f).toDouble(),
                maxGrossExposureUsd = paperPrefs.getFloat(KEY_PAPER_MAX_GROSS, 100_000f).toDouble(),
                dailyLossLimitR = paperPrefs.getFloat(KEY_PAPER_DAILY_LOSS_R, 2.0f).toDouble(),
                // No invented demo exposure/PnL — the paper wallet starts clean.
                currentGrossExposureUsd = 0.0,
                dailyPnLUsd = 0.0,
                currentRealizedLossR = 0.0,
                worstCaseStopOutR = 0.0
            )
        )
    }

    /**
     * §5 paper mode: the user-configured wallet drives sizing and worst-case stop-out
     * math while trade outcomes stay simulated. Values persist across app restarts.
     */
    fun updatePaperWallet(
        equityUsd: Double,
        riskPerCampaignPct: Double,
        maxGrossExposureUsd: Double,
        dailyLossLimitR: Double
    ) {
        if (equityUsd <= 0.0) return
        val risk = riskPerCampaignPct.coerceIn(0.05, 1.0)
        val lossLimit = dailyLossLimitR.coerceIn(0.5, 5.0)
        paperPrefs.edit()
            .putFloat(KEY_PAPER_EQUITY, equityUsd.toFloat())
            .putFloat(KEY_PAPER_RISK_PCT, risk.toFloat())
            .putFloat(KEY_PAPER_MAX_GROSS, maxGrossExposureUsd.coerceAtLeast(0.0).toFloat())
            .putFloat(KEY_PAPER_DAILY_LOSS_R, lossLimit.toFloat())
            .apply()
        _uiState.value = _uiState.value.copy(
            riskEnvelope = _uiState.value.riskEnvelope.copy(
                accountEquityUsd = equityUsd,
                riskPerCampaignPct = risk,
                maxGrossExposureUsd = maxGrossExposureUsd.coerceAtLeast(0.0),
                dailyLossLimitR = lossLimit
            )
        )
        viewModelScope.launch {
            repository.logAction(
                asset = "PORTFOLIO",
                campaignId = "PAPER_WALLET",
                action = AuditLogAction.CAMPAIGN_STAGED,
                reasonCode = "PAPER_WALLET_UPDATED",
                message = "Paper wallet set: equity $${equityUsd.toInt()}, risk $risk%/campaign, maxGross $${maxGrossExposureUsd.toInt()}, daily stop ${lossLimit}R"
            )
        }
    }

    /** §36 live mode: poll the real Binance futures wallet while the session is unlocked. */
    private fun startLiveWalletPolling() {
        if (!sessionManager.hasValidActiveSession()) return
        liveWalletJob?.cancel()
        liveWalletJob = viewModelScope.launch {
            while (true) {
                refreshLiveWallet()
                delay(60_000)
            }
        }
    }

    private suspend fun refreshLiveWallet() {
        val creds = sessionManager.copyCredentials() ?: return
        try {
            val balance = BinanceSignedClient.fetchWalletBalance(creds.first, creds.second)
            if (balance != null) {
                _uiState.value = _uiState.value.copy(liveWalletUsd = balance)
            }
        } finally {
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
        }
    }

    private suspend fun logLiveNote(campaignId: String, reasonCode: String, message: String) {
        repository.logAction(
            asset = _uiState.value.selectedAsset?.symbol ?: "PORTFOLIO",
            campaignId = campaignId,
            action = AuditLogAction.CAMPAIGN_STAGED,
            reasonCode = reasonCode,
            message = message
        )
    }

    override fun onCleared() {
        super.onCleared()
        activeDeltaJob?.cancel()
        orderBookJob?.cancel()
        takerFlowJob?.cancel()
        dormantPollingJob?.cancel()
        userDataJob?.cancel()
        liveWalletJob?.cancel()
        sessionManager.terminateSessionAndWipeRam()
    }

    private companion object {
        const val KEY_PAPER_EQUITY = "paper_equity_usd"
        const val KEY_PAPER_RISK_PCT = "paper_risk_pct"
        const val KEY_PAPER_MAX_GROSS = "paper_max_gross_usd"
        const val KEY_PAPER_DAILY_LOSS_R = "paper_daily_loss_r"
        /** Minimum persisted 1h candles a scenario replay needs to count as history-driven. */
        const val MIN_SCENARIO_CANDLES = 30
        /** §2/§33 diagnostics cadence: re-sample hardware + feed latency every minute. */
        const val DIAGNOSTICS_RE_SAMPLE_MS = 60_000L
    }
}
