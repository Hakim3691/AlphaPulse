package com.aistudio.alphapulse.ui

/**
 * The main screen of the app: a single scrolling workspace.
 *
 * It lays the header on top, then the workspace cards, then the expandable
 * explanation panels, and wires them all to one shared view model.
 *
 * Scope: this screen renders AlphaPulse only. The ProactiveMS campaign and live-order
 * workspace is a different product surface and is deliberately not mounted here (C-22);
 * a tab must never show another workstation's boards inside the AlphaPulse ledger flow.
 *
 * There are two entries into the same analysis surface:
 * - [AlphaPulseScreen] is the standalone screen: it owns its `Scaffold`, its header and
 *   the safe-drawing insets, which is what `AppShell` mounts.
 * - [AlphaPulseScreenContent] is the body only, for a host that already owns a header and
 *   a single set of window insets — today the ProactiveMS workspace's AlphaPulse tab.
 *   Mounting the full screen inside that Scaffold used to nest a second one, draw a
 *   second header under the workspace's, and pay the status/navigation-bar insets twice,
 *   which squeezed the cards on a phone.
 *
 * Spec Ref: §39 User Interface, §40 Primary Dashboard, §72 / C-22
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.alphapulse.domain.catalogue.TradeIdeasCatalog
import com.aistudio.alphapulse.domain.decision.Needle3Engine
import com.aistudio.alphapulse.ui.components.*
import com.aistudio.alphapulse.ui.theme.BrandDark
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseUiState
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseViewModel

@Composable
fun AlphaPulseScreen(
    viewModel: AlphaPulseViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // C-62/A4: the pipeline boots when this screen is actually shown.
    LaunchedEffect(Unit) { viewModel.ensureBooted() }

    AlphaPulseMessages(viewModel, uiState.snackbarMessage, snackbarHostState)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BrandDark,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BrandDark)
        ) {
            // The one shared header. The base app passes no VIP block, so unlocking
            // the feature still adds nothing to this screen (C-33).
            AlphaPulseHeader(
                sessionElapsedSeconds = uiState.sessionElapsedSeconds,
                isLTFReady = uiState.isLTFReady,
                isMTFReady = uiState.isMTFReady,
                isHTFReady = uiState.isHTFReady,
                pipelineRunning = uiState.pipelineRunning,
                pipelineSecondsRemaining = uiState.pipelineSecondsRemaining,
                onRefreshClick = { viewModel.showRefreshDialog(true) }
            )

            AlphaPulseAnalysis(viewModel = viewModel, uiState = uiState)
        }

        // Manual Refresh Confirmation Modal
        if (uiState.showRefreshDialog) {
            RefreshConfirmDialog(
                onDismiss = { viewModel.showRefreshDialog(false) },
                onConfirm = { viewModel.confirmManualRefresh() }
            )
        }
    }
}

/**
 * The AlphaPulse analysis surface without a `Scaffold`, a header or window insets.
 *
 * Used where the surrounding host already supplies exactly one of each — the ProactiveMS
 * workspace's AlphaPulse tab. Everything an operator does is the same as on
 * [AlphaPulseScreen]: the same cards, ledgers, accordions, snackbar messages and refresh
 * dialog. Only the chrome is the host's.
 */
@Composable
fun AlphaPulseScreenContent(
    viewModel: AlphaPulseViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // C-62/A4: opening the tab is what starts the screen, not constructing the host.
    LaunchedEffect(Unit) { viewModel.ensureBooted() }

    AlphaPulseMessages(viewModel, uiState.snackbarMessage, snackbarHostState)

    Box(modifier = Modifier.fillMaxSize().background(BrandDark)) {
        AlphaPulseAnalysis(viewModel = viewModel, uiState = uiState)

        // The host's Scaffold has no snackbar host of its own, so the screen keeps
        // showing its messages instead of silently dropping them.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )

        // Manual Refresh Confirmation Modal
        if (uiState.showRefreshDialog) {
            RefreshConfirmDialog(
                onDismiss = { viewModel.showRefreshDialog(false) },
                onConfirm = { viewModel.confirmManualRefresh() }
            )
        }
    }
}

/** Drains the view model's one-shot message onto whichever snackbar host is showing. */
@Composable
private fun AlphaPulseMessages(
    viewModel: AlphaPulseViewModel,
    message: String?,
    snackbarHostState: SnackbarHostState
) {
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }
}

/**
 * The screen's content: the dynamic screener banner and the scrolling workspace of cards,
 * accordions and ledgers. Shared by both entries so they can never drift apart.
 */
@Composable
private fun AlphaPulseAnalysis(
    viewModel: AlphaPulseViewModel,
    uiState: AlphaPulseUiState
) {
    val scrollState = rememberScrollState()

    // Ask the vendored engine itself whether it can run the checkpoint the dock
    // mounted, so the screen can never claim a capability this build does not have.
    val needle3Status = remember(uiState.needle3WeightsPath) {
        Needle3Engine.shared.status(uiState.needle3WeightsPath)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Dynamic Screener Progress Banner
        if (uiState.pipelineRunning) {
            ScannerProgressBanner(
                secondsRemaining = uiState.pipelineSecondsRemaining,
                stepTitle = uiState.pipelineStepTitle,
                stepSubtitle = uiState.pipelineStepSubtitle,
                progress = uiState.pipelineProgress
            )
        }

        // Main Scrollable Workspace
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val filteredCoins = viewModel.getFilteredCoins()
            val selectedCoin = uiState.screenedCoins.find { it.symbol == uiState.selectedSymbol }

            // Top Workspace: Asset Selector, Live Spot, ML Ensemble Weights (Always Visible)
            TopWorkspaceCards(
                screenedCoins = filteredCoins,
                selectedSymbol = uiState.selectedSymbol,
                under10Only = uiState.under10Only,
                onSelectCoin = { viewModel.selectCoin(it) },
                onToggleUnder10 = { viewModel.toggleUnder10Only(it) },
                spotPrice = uiState.spotPrice,
                spotChange = uiState.spotChange,
                high24h = uiState.high24h,
                low24h = uiState.low24h,
                modelState = uiState.modelState,
                totalSettled = uiState.totalSettledForCoin,
                coneHitRate = uiState.coneHitRateForCoin,
                mountedModelIds = uiState.mountedModelIds,
                decision = uiState.decision,
                strategySignals = uiState.strategySignals,
                painPathPrior = uiState.painPathPrior,
                onExportUniverseClick = { viewModel.export4TabUniverseCsv() },
                modelStates = uiState.modelDownloads,
                onDownloadModel = { viewModel.downloadModel(it) }
            )

            // Collapsible Accordions (Risk Adjusted, Technical Microstructure, Institutional Filters, Forecasts, Canvas Chart, Cognitive Brief)
            AccordionsSection(
                selectedCoin = selectedCoin,
                rsi = uiState.rsi,
                macdSignal = uiState.macdSignal,
                markovRegime = uiState.markovRegime,
                netImbalanceUsdt = uiState.netImbalanceUsdt,
                bidRatioPct = uiState.bidRatioPct,
                heatmapSource = uiState.heatmapSource,
                forecastTargets = uiState.forecastTargets,
                alphaPoc = uiState.alphaPoc,
                alphaVah = uiState.alphaVah,
                alphaVal = uiState.alphaVal,
                chartPoints = uiState.chartPoints,
                mountedModelIds = uiState.mountedModelIds
            )

            // Prediction Audit Ledger (Always Visible)
            PredictionLedgerSection(
                predictions = uiState.predictions,
                horizonFilter = uiState.horizonFilter,
                onFilterChange = { viewModel.setHorizonFilter(it) },
                onClearClick = { viewModel.clearLedger() },
                onExportClick = { viewModel.exportAndShareAuditCsv() }
            )

            // §73 Secondary Pattern Prediction & Learning Ledger. Mounted directly
            // below the price ledger, sharing its asset scope and horizon filter so
            // both tables count down to the same phase-locked boundaries.
            PatternLedgerSection(
                entries = uiState.patternLedger,
                horizonFilter = uiState.horizonFilter,
                onFilterChange = { viewModel.setHorizonFilter(it) },
                onExportClick = { viewModel.exportPatternLedgerCsv() },
                onClearClick = { viewModel.clearPatternLedger() }
            )

            // Decisions Ledger — the §29 verdicts, graded against the price ledger's
            // settlement (C-29). It replaced the five-row copy that lived inside the
            // price ledger, so there is exactly one decisions table.
            DecisionsLedgerSection(
                rows = uiState.decisionLedger,
                onClearClick = { viewModel.clearLedger() },
                horizonFilter = uiState.horizonFilter,
                onFilterChange = { viewModel.setHorizonFilter(it) },
                onExportClick = { viewModel.exportDecisionLedgerCsv() }
            )

            // §74 Auxiliary Confluence Screeners & Quant Models. Evaluated on the same
            // bars as the ledgers above, so the board never disagrees about history.
            QuantStrategyBoardSection(
                screenerSignals = uiState.screenerSignals,
                strategySignals = uiState.strategySignals
            )

            // §3.1: the taxonomy the engine selects from, browsable by its four
            // categories with a persisted Favorites tab.
            TradeIdeasCatalogSection(ideas = TradeIdeasCatalog.all)

            Spacer(modifier = Modifier.height(16.dp))

            // The bottom TSFM dock is gone: both artifacts are downloaded from the
            // two header quick actions, and Needle 3 ships inside the APK.

            // Needle 3 is on-device, so its gap is explained here: the weights
            // may be downloaded and still have no engine to run them on (an
            // emulator, or a checkpoint the engine rejected), or no checkpoint at
            // all. Renders nothing once the engine is ready.
            Needle3StatusBanner(status = needle3Status)
        }
    }
}
