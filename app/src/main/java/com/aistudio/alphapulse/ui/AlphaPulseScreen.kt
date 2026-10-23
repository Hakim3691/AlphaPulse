package com.aistudio.alphapulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.alphapulse.ui.components.*
import com.aistudio.alphapulse.ui.theme.BrandDark
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseViewModel

@Composable
fun AlphaPulseScreen(
    viewModel: AlphaPulseViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    coneHitRate = uiState.coneHitRateForCoin
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
                    chartPoints = uiState.chartPoints
                )

                // Prediction Audit Ledger (Always Visible)
                PredictionLedgerSection(
                    predictions = uiState.predictions,
                    horizonFilter = uiState.horizonFilter,
                    onFilterChange = { viewModel.setHorizonFilter(it) },
                    onClearClick = { viewModel.clearLedger() },
                    onExportClick = { viewModel.exportAndShareAuditCsv() },
                    onExportUniverseClick = { viewModel.export4TabUniverseCsv() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Model Download Section
                ModelDownloadSection(
                    onDownloadClick = { modelId, version, checksum, backend, sizeBytes, parameterCount, quantization ->
                        viewModel.downloadModel(
                            modelId = modelId,
                            version = version,
                            checksum = checksum,
                            backend = backend,
                            sizeBytes = sizeBytes,
                            parameterCount = parameterCount,
                            quantization = quantization
                        )
                    }
                )
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