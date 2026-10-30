package com.aistudio.alphapulse.ui

/**
 * The main screen of the app: a single scrolling workspace.
 *
 * It lays the header on top, then the workspace cards, then the expandable
 * explanation panels, and wires them all to one shared view model.
 *
 * Spec Ref: §39 User Interface, §40 Primary Dashboard
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.alphapulse.domain.decision.Needle3Engine
import com.aistudio.alphapulse.ui.components.*
import com.aistudio.alphapulse.ui.theme.BrandDark
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseViewModel

@Composable
fun AlphaPulseScreen(
    viewModel: AlphaPulseViewModel,
    /** Campaign engine + live order routing; absent only in previews/tests. */
    campaignViewModel: MainViewModel? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val campaignState = campaignViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Ask the vendored engine itself whether it can run the checkpoint the dock
    // mounted, so the screen can never claim a capability this build does not have.
    val needle3Status = remember(uiState.needle3WeightsPath) {
        Needle3Engine.shared.status(uiState.needle3WeightsPath)
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

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
            // Sticky Top Header
            HeaderBar(
                sessionElapsedSeconds = uiState.sessionElapsedSeconds,
                isLTFReady = uiState.isLTFReady,
                isMTFReady = uiState.isMTFReady,
                isHTFReady = uiState.isHTFReady,
                pipelineRunning = uiState.pipelineRunning,
                pipelineSecondsRemaining = uiState.pipelineSecondsRemaining,
                onRefreshClick = { viewModel.showRefreshDialog(true) }
            )

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
                    decision = uiState.decision
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
                    onExportClick = { viewModel.exportAndShareAuditCsv() },
                    onExportUniverseClick = { viewModel.export4TabUniverseCsv() },
                    decisionHistory = uiState.decisionHistory
                )

                // Campaign boards plus their live entry / take-profit orders. This is
                // the only place an operator can see or cancel a staged Binance order.
                if (campaignViewModel != null && campaignState != null) {
                    CampaignWorkspaceSection(
                        campaigns = campaignState.campaigns,
                        selectedCampaignId = campaignState.selectedCampaign?.id,
                        executionMode = campaignState.executionMode,
                        liveOrderRecords = campaignState.liveOrderRecords,
                        openClientOrderIds = campaignState.liveOpenClientIds,
                        lastReconcile = campaignState.lastReconcile,
                        onSelectCampaign = { campaignViewModel.selectCampaign(it) },
                        onOpenOverride = { campaignViewModel.openManualOverrideDialog(it) },
                        onSoftCancel = { campaignViewModel.triggerSoftCancel(it) },
                        onHardFlatten = { campaignViewModel.triggerHardFlatten(it) },
                        onCancelLiveOrder = { campaignViewModel.cancelLiveOrder(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Compact TSFM model dock — mounts models into the live ensemble
                ModelDownloadSection(
                    states = uiState.modelDownloads,
                    onDownload = { viewModel.downloadModel(it) }
                )

                // Needle 3 is on-device, so its gap is explained here: the weights
                // may be downloaded and still have no engine to run them on (an
                // emulator, or a checkpoint the engine rejected), or no checkpoint at
                // all. Renders nothing once the engine is ready.
                Needle3StatusBanner(status = needle3Status)
            }
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
