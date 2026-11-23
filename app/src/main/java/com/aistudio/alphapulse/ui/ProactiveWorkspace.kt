package com.aistudio.alphapulse.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.engine.SimulationScenario
import com.aistudio.alphapulse.engine.proactive.ProductionExecutionConfig
import com.aistudio.alphapulse.ui.components.AlphaPulseHeader
import com.aistudio.alphapulse.ui.components.ManualOverrideDialog
import com.aistudio.alphapulse.ui.components.VipHeaderState
import com.aistudio.alphapulse.ui.screens.DashboardScreen
import com.aistudio.alphapulse.ui.screens.RiskGuardrailsScreen
import com.aistudio.alphapulse.ui.screens.SimulationScreen
import com.aistudio.alphapulse.ui.theme.BrandDark
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.MyApplicationTheme
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseViewModel

/**
 * §72 / C-22 — the ProactiveMS workspace, rendered with AlphaPulse's own design system.
 *
 * The feature host used to build the screen from raw `TextView`s: a cyan heading, the
 * symbol labels and one joined string per tab. Every ProactiveMS surface therefore looked
 * nothing like the AlphaPulse screen next to it, and the AlphaPulse tab was a four-line
 * summary instead of the analysis screen the contract names.
 *
 * The workspace is composed here, in the base module, for the same reason
 * [ProactiveMsWorkspaceState] lives here: the feature module owns no layout resources, and
 * every tab is a deterministic function of the live [MainUiState] and the one shared
 * selection. The AlphaPulse tab is the *actual* AlphaPulse analysis surface, injected as a
 * slot so this surface never re-implements it — its body only (`AlphaPulseScreenContent`),
 * because the workspace already supplies the header and the window insets.
 *
 * Spec Ref: Phase 1 §7, Phase 2 §32, §72, C-22
 */

/** Everything the workspace can ask the engine to do. Defaults keep previews/tests quiet. */
data class ProactiveWorkspaceActions(
    val onSelectTab: (ProactiveMsTab) -> Unit,
    val onToggleKillSwitch: () -> Unit = {},
    val onModeChange: (ExecutionMode) -> Unit = {},
    val onOpenOrphanWarning: () -> Unit = {},
    val onSelectCampaign: (Campaign) -> Unit = {},
    val onOpenOverride: (Campaign) -> Unit = {},
    val onSoftCancel: (String) -> Unit = {},
    val onHardFlatten: (String) -> Unit = {},
    val onCloseOverride: () -> Unit = {},
    val onApplyOverride: (campaignId: String, stop: Double, size: Double) -> Unit =
        { _, _, _ -> },
    val onStartSession: (key: String, secret: String) -> Unit = { _, _ -> },
    val onTerminateSession: () -> Unit = {},
    val onUpdatePaperWallet: (equity: Double, riskPct: Double, gross: Double, lossR: Double) -> Unit =
        { _, _, _, _ -> },
    val onRunDiagnostics: () -> Unit = {},
    val onRunScenario: (SimulationScenario) -> Unit = {},
    val onRunReplay: (baseline: Boolean) -> Unit = {}
)

/**
 * The AlphaPulse chrome the workspace header shows on every tab: the same session
 * clock, readiness badges and screening status the analysis screen shows. It is passed
 * in explicitly so the workspace never invents a second timer or a second readiness
 * reading.
 */
data class AlphaPulseHeaderChrome(
    val sessionElapsedSeconds: Long = 0,
    val isLTFReady: Boolean = false,
    val isMTFReady: Boolean = false,
    val isHTFReady: Boolean = false,
    val pipelineRunning: Boolean = false,
    val pipelineSecondsRemaining: Int = 0,
    val onRefreshClick: () -> Unit = {}
)

/** Test tags the guard specs address instead of matching raw label strings. */
object ProactiveWorkspaceTags {
    const val WORKSPACE = "proactive_workspace"
    const val BOTTOM_BAR = "proactive_bottom_bar"
    const val PRE_LAUNCH_GATE = "proactive_pre_launch_gate"

    fun tab(tab: ProactiveMsTab): String = "proactive_tab_${tab.name.lowercase()}"
}

/** The stateless workspace: one tab body, one shared symbol, one bottom bar. */
@Composable
fun ProactiveWorkspaceScreen(
    state: MainUiState,
    workspace: ProactiveMsWorkspaceState,
    actions: ProactiveWorkspaceActions,
    alphaPulseTab: @Composable () -> Unit,
    preLaunchVerdict: String = "",
    headerChrome: AlphaPulseHeaderChrome = AlphaPulseHeaderChrome(),
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(ProactiveWorkspaceTags.WORKSPACE),
        containerColor = BrandDark,
        // One inset set for the whole surface, and the chrome owns it: the shared
        // `AlphaPulseHeader` pads itself below the status bar, the single shared tab bar
        // carries the navigation-bar inset, and no tab body applies window insets of its own. The
        // AlphaPulse tab in particular mounts `AlphaPulseScreenContent`, not the full
        // `AlphaPulseScreen`, whose Scaffold would otherwise add safe-drawing padding a
        // second time and leave the cards squeezed on a phone.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AlphaPulseTabBar(
                activeTab = workspace.activeTab,
                onSelectTab = actions.onSelectTab,
                tag = ProactiveWorkspaceTags.BOTTOM_BAR
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BrandDark)
        ) {
            // Exactly one header for the whole app, on every tab: the same
            // `AlphaPulseHeader` the analysis screen mounts, with the workspace's VIP
            // controls added as its optional block. The AlphaPulse tab used to hide the
            // header and mount the full `AlphaPulseScreen`, which drew a second one.
            AlphaPulseHeader(
                sessionElapsedSeconds = headerChrome.sessionElapsedSeconds,
                isLTFReady = headerChrome.isLTFReady,
                isMTFReady = headerChrome.isMTFReady,
                isHTFReady = headerChrome.isHTFReady,
                pipelineRunning = headerChrome.pipelineRunning,
                pipelineSecondsRemaining = headerChrome.pipelineSecondsRemaining,
                onRefreshClick = headerChrome.onRefreshClick,
                vip = VipHeaderState(
                    executionMode = state.executionMode,
                    riskEnvelope = state.riskEnvelope,
                    venueHealth = state.venueHealth,
                    isSessionActive = state.isSessionActive,
                    isLiveUniverse = state.isLiveUniverse,
                    onModeChange = actions.onModeChange,
                    onToggleKillSwitch = actions.onToggleKillSwitch,
                    onOpenOrphanWarning = actions.onOpenOrphanWarning
                )
            )

            // C-22: there is no second coin selector. AlphaPulse's institutional screen
            // picks the coin for every tab, so the workspace carries no symbol strip and
            // the retired `Screener` tab's mid-cap list is gone with it.
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when (workspace.activeTab) {
                    // C-22: the AlphaPulse tab is AlphaPulse, not a summary of it — its
                    // analysis body, under this workspace's single header and insets.
                    ProactiveMsTab.ALPHA_PULSE -> alphaPulseTab()

                    ProactiveMsTab.DASHBOARD -> DashboardScreen(
                        asset = state.selectedAsset,
                        levelMap = state.levelMap,
                        campaigns = state.campaigns,
                        selectedCampaign = state.selectedCampaign,
                        liveEvidence = state.liveEvidence,
                        onSelectCampaign = actions.onSelectCampaign,
                        onOpenOverride = actions.onOpenOverride,
                        onSoftCancel = actions.onSoftCancel,
                        onHardFlatten = actions.onHardFlatten
                    )

                    ProactiveMsTab.TELEMETRY -> ProactiveTelemetryTab(state = state)

                    ProactiveMsTab.GUARDRAILS -> ProactiveGuardrailsTab(
                        state = state,
                        preLaunchVerdict = preLaunchVerdict,
                        actions = actions
                    )

                    ProactiveMsTab.REPLAY -> SimulationScreen(
                        metrics = state.backtestMetrics,
                        selectedScenario = state.selectedScenario,
                        onRunScenario = actions.onRunScenario,
                        lastReplay = state.lastReplay,
                        isReplayRunning = state.isReplayRunning,
                        onRunReplay = actions.onRunReplay
                    )

                    ProactiveMsTab.AUDIT -> ProactiveAuditTab(entries = state.auditLogs)
                }
            }
        }
    }

    ManualOverrideDialog(
        isOpen = state.isOverrideDialogOpen,
        campaign = state.selectedCampaign,
        warnings = state.overrideWarnings,
        onApplyOverride = { stop, size ->
            state.selectedCampaign?.let { campaign ->
                actions.onApplyOverride(campaign.id, stop, size)
            }
        },
        onDismiss = actions.onCloseOverride
    )
}

/** The live wiring: observes the engine and hands the workspace one shared selection. */
@Composable
fun ProactiveWorkspaceScreen(
    viewModel: MainViewModel,
    alphaPulseViewModel: AlphaPulseViewModel,
    initialTab: ProactiveMsTab? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // C-57: the shared bar launches a *specific* tab, so the host hands the requested
    // tab in rather than every cross-host launch landing on AlphaPulse.
    var workspace by remember {
        mutableStateOf(ProactiveMsWorkspaceState(activeTab = initialTab ?: ProactiveMsTab.ALPHA_PULSE))
    }

    // The engine is the source of truth for the universe and the inspected coin. The
    // workspace only mirrors it, so a selection can never drift between them.
    LaunchedEffect(state.selectedAsset?.symbol) {
        val symbol = state.selectedAsset?.symbol ?: return@LaunchedEffect
        workspace = workspace.selectSymbol(symbol)
    }

    // C-22: AlphaPulse is the one coin selector. A coin chosen in its institutional
    // screen becomes the workspace's coin *and* the campaign engine's asset, so every
    // tab works from the screened universe rather than from a second picker.
    val alphaPulseState by alphaPulseViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(alphaPulseState.selectedSymbol) {
        val symbol = alphaPulseState.selectedSymbol
        if (symbol.isBlank()) return@LaunchedEffect
        workspace = workspace.selectSymbol(symbol)
        if (state.selectedAsset?.symbol != symbol) viewModel.adoptScreenedUniverse(symbol)
    }

    val preLaunchVerdict = remember(state.executionMode) {
        viewModel.preLaunchVerdict().summary
    }

    ProactiveWorkspaceScreen(
        state = state,
        workspace = workspace,
        preLaunchVerdict = preLaunchVerdict,
        headerChrome = AlphaPulseHeaderChrome(
            sessionElapsedSeconds = alphaPulseState.sessionElapsedSeconds,
            isLTFReady = alphaPulseState.isLTFReady,
            isMTFReady = alphaPulseState.isMTFReady,
            isHTFReady = alphaPulseState.isHTFReady,
            pipelineRunning = alphaPulseState.pipelineRunning,
            pipelineSecondsRemaining = alphaPulseState.pipelineSecondsRemaining,
            onRefreshClick = { alphaPulseViewModel.showRefreshDialog(true) }
        ),
        actions = ProactiveWorkspaceActions(
            onSelectTab = { tab -> workspace = workspace.selectTab(tab) },
            onToggleKillSwitch = viewModel::toggleKillSwitch,
            onModeChange = viewModel::setExecutionMode,
            onOpenOrphanWarning = viewModel::openOrphanedOrderWarning,
            onSelectCampaign = viewModel::selectCampaign,
            onOpenOverride = viewModel::openManualOverrideDialog,
            onSoftCancel = viewModel::triggerSoftCancel,
            onHardFlatten = viewModel::triggerHardFlatten,
            onCloseOverride = viewModel::closeManualOverrideDialog,
            onApplyOverride = { id, stop, size ->
                viewModel.applyManualOverride(id, stop, size)
            },
            onStartSession = viewModel::startApiSession,
            onTerminateSession = viewModel::terminateApiSession,
            onUpdatePaperWallet = { equity, riskPct, gross, lossR ->
                viewModel.updatePaperWallet(equity, riskPct, gross, lossR)
            },
            onRunDiagnostics = viewModel::runPreFlightDiagnostics,
            onRunScenario = viewModel::runSimulationScenario,
            onRunReplay = { baseline -> viewModel.runReplay(baseline) }
        ),
        alphaPulseTab = { AlphaPulseScreenContent(viewModel = alphaPulseViewModel) }
    )
}

/**
 * Mounts the workspace from the `:proactive_trader` host.
 *
 * The host is deliberately a thin activity: it owns no layouts, no resources and no
 * Compose compiler plugin, so the feature's packaging cannot disturb the base module's
 * arm64 resource processing. This is the one entry point it calls.
 */
fun mountProactiveWorkspace(
    activity: ComponentActivity,
    mainViewModel: MainViewModel,
    alphaPulseViewModel: AlphaPulseViewModel,
    initialTab: ProactiveMsTab? = null
) {
    activity.enableEdgeToEdge()
    activity.setContent {
        MyApplicationTheme {
            ProactiveWorkspaceScreen(
                viewModel = mainViewModel,
                alphaPulseViewModel = alphaPulseViewModel,
                initialTab = initialTab
            )
        }
    }
}

/**
 * Guardrails = the production limits the engine has in force (§34) above the live risk
 * envelope and session controls. The §38 pre-launch gate sits on top so the operator can
 * see whether Scaled Live is unlocked before trusting the mode pill.
 */
@Composable
private fun ProactiveGuardrailsTab(
    state: MainUiState,
    preLaunchVerdict: String,
    actions: ProactiveWorkspaceActions
) {
    val config = ProductionExecutionConfig.PRODUCTION
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp)
                .background(CardBackground, RoundedCornerShape(10.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
                .testTag(ProactiveWorkspaceTags.PRE_LAUNCH_GATE)
        ) {
            Text(
                text = "PRODUCTION LIMITS",
                color = SlateTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Risk ${config.accountRiskPerCampaignPct}% per campaign · " +
                    "daily stop ${config.maxDailyLossR}R · " +
                    "re-entry lockout ${config.reentryCooldownMinutes} min",
                color = SlateTextPrimary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Soft / hard invalidation ${config.softInvalidationThreshold}/" +
                    "${config.hardInvalidationThreshold} · " +
                    "spread ceiling ${config.maxSpreadPct}%",
                color = SlateTextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (preLaunchVerdict.isNotBlank()) {
                Text(
                    text = "§38 pre-launch gate: $preLaunchVerdict",
                    color = SlateTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            RiskGuardrailsScreen(
                riskEnvelope = state.riskEnvelope,
            venueHealth = state.venueHealth,
            isSessionActive = state.isSessionActive,
            executionMode = state.executionMode,
            liveWalletUsd = state.liveWalletUsd,
            onToggleKillSwitch = actions.onToggleKillSwitch,
            onStartSession = actions.onStartSession,
            onTerminateSession = actions.onTerminateSession,
            onUpdatePaperWallet = actions.onUpdatePaperWallet,
            isDiagnosticsRunning = state.isDiagnosticsRunning,
                hasLiveDiagnostics = state.hasLiveDiagnostics,
                onRunDiagnostics = actions.onRunDiagnostics
            )
        }
    }
}
