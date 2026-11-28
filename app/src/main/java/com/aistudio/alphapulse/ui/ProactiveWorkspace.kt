package com.aistudio.alphapulse.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.engine.SimulationScenario
import com.aistudio.alphapulse.engine.VenueHealthVerdict
import com.aistudio.alphapulse.domain.campaign.LayeredEvidencePresentation
import com.aistudio.alphapulse.domain.campaign.ThresholdField
import com.aistudio.alphapulse.domain.campaign.ThresholdPresentation
import com.aistudio.alphapulse.engine.proactive.ProductionExecutionConfig
import com.aistudio.alphapulse.ui.components.AlphaPulseHeader
import com.aistudio.alphapulse.ui.components.ManualOverrideDialog
import com.aistudio.alphapulse.ui.components.VipHeaderState
import com.aistudio.alphapulse.ui.screens.DashboardScreen
import com.aistudio.alphapulse.ui.screens.RiskGuardrailsScreen
import com.aistudio.alphapulse.ui.screens.SimulationScreen
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.BrandDark
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
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
    val onRunReplay: (baseline: Boolean) -> Unit = {},
    /** C-60: step one configured threshold by [delta] (ignored for a reset). */
    val onAdjustThreshold: (ThresholdField, Int) -> Unit = { _, _ -> },
    /** C-60: return the configuration to exactly 85.0% / 20 settled samples. */
    val onResetThresholds: () -> Unit = {},
    /** C-63: close a mode refusal after reading why the mode did not switch. */
    val onDismissLiveModeGate: () -> Unit = {},
    /** C-63: take the operator to the Guardrails gate that holds the missing prerequisite. */
    val onOpenGuardrails: () -> Unit = {}
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
    /** C-58: which of PRICE / PATTERN / STRATEGY are live for the inspected coin. */
    const val LAYERED_EVIDENCE = "guardrails_layered_evidence"

    /** C-63: what the active execution mode does, and the advisories in force. */
    const val MODE_BANNER = "mode_banner"

    /** C-63: why a mode switch was refused. */
    const val MODE_REFUSAL = "live_mode_gate_dialog"

    /** C-60: the configured floor and sample floors the gate is applying. */
    const val THRESHOLDS = "guardrails_thresholds"
    const val THRESHOLDS_RESET = "guardrails_thresholds_reset"
    const val THRESHOLDS_REJECTION = "guardrails_thresholds_rejection"

    fun tab(tab: ProactiveMsTab): String = "proactive_tab_${tab.name.lowercase()}"

    /** One layer's row inside [LAYERED_EVIDENCE]. */
    fun layer(name: String): String = "guardrails_layer_${name.lowercase()}"

    /** One layer's row inside [THRESHOLDS]. */
    fun thresholdLayer(name: String): String = "guardrails_threshold_${name.lowercase()}"

    /** One configured limit's stepper, addressed by field and direction. */
    fun thresholdStep(field: ThresholdField, delta: Int): String =
        "guardrails_threshold_step_${field.name.lowercase()}_${if (delta < 0) "down" else "up"}"
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

            // C-63: every mode says what it does. Shadow and Paper used to look like
            // dead switches because nothing on screen changed when they were selected.
            ModeBanner(state = state)

            // C-22: there is no second coin selector. AlphaPulse's institutional screen
            // picks the coin for every tab, so the workspace carries no symbol strip and
            // the retired `Screener` tab's mid-cap list is gone with it.
            val tabStates = rememberSaveableStateHolder()
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                // C-62: each tab keeps its own remembered state — scroll position,
                // expanded card, open detail — across a tab switch, because the body is
                // retained rather than rebuilt from scratch.
                tabStates.SaveableStateProvider(workspace.activeTab.name) {
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
    }

    ModeRefusalDialog(
        isOpen = state.isLiveModeBlocked,
        refusal = state.modeRefusal,
        onDismiss = actions.onDismissLiveModeGate,
        onOpenGuardrails = actions.onOpenGuardrails
    )

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
    // tab in rather than every cross-host launch landing on AlphaPulse. C-62: the tab and
    // the coin are one saveable value, so a rotation no longer strands the desk on
    // AlphaPulse with no coin.
    var workspace by rememberSaveable(
        stateSaver = listSaver(
            save = { value -> value.toSaveable() },
            restore = { saved -> ProactiveMsWorkspaceState.fromSaveable(saved) }
        )
    ) {
        mutableStateOf(ProactiveMsWorkspaceState(activeTab = initialTab ?: ProactiveMsTab.ALPHA_PULSE))
    }

    // C-62: AlphaPulse is the one coin selector and the *only* source the workspace
    // mirrors. The engine's own asset was a second source: that effect re-ran on every
    // mount after the workspace reopened, which is how a tab switch could repoint the
    // coin the operator had chosen.
    val alphaPulseState by alphaPulseViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(alphaPulseState.selectedSymbol) {
        val symbol = alphaPulseState.selectedSymbol
        if (symbol.isBlank()) return@LaunchedEffect
        workspace = workspace.selectSymbol(symbol)
        WorkspaceSelectionStore.shared.select(symbol)
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
            onRunReplay = { baseline -> viewModel.runReplay(baseline) },
            onAdjustThreshold = viewModel::adjustThresholds,
            onResetThresholds = viewModel::resetThresholds,
            onDismissLiveModeGate = viewModel::dismissLiveModeGate,
            onOpenGuardrails = {
                workspace = workspace.selectTab(ProactiveMsTab.GUARDRAILS)
                viewModel.dismissLiveModeGate()
            }
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
 * C-63: what the active mode does, plus the venue advisories in force.
 *
 * The banner is the reason Shadow and Paper no longer look inert: selecting them changes
 * what the desk says it is doing, in the same place every tab can see.
 */
@Composable
private fun ModeBanner(state: MainUiState) {
    val tone = Color(state.executionMode.badgeColorHex)
    val advisories = VenueHealthVerdict.assess(state.venueHealth, state.executionMode).advisories
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp)
            .background(tone.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(ProactiveWorkspaceTags.MODE_BANNER)
    ) {
        Text(
            text = ExecutionModeGate.banner(state.executionMode),
            color = tone,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        advisories.forEach { advisory ->
            Text(
                text = "ADVISORY · $advisory",
                color = AmberWarning,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/** The refused mode switch: the mode did not change, and this says why. */
@Composable
internal fun ModeRefusalDialog(
    isOpen: Boolean,
    refusal: String?,
    onDismiss: () -> Unit,
    onOpenGuardrails: () -> Unit = {}
) {
    if (!isOpen || refusal.isNullOrBlank()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ProactiveWorkspaceTags.MODE_REFUSAL),
        containerColor = CardBackground,
        title = {
            Text(
                text = "MODE NOT AVAILABLE",
                color = AmberWarning,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(text = refusal, color = SlateTextSecondary, fontSize = 11.sp)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenGuardrails()
                }
            ) {
                Text(text = "OPEN GUARDRAILS", color = CyanAccent, fontSize = 11.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "DISMISS", color = SlateTextSecondary, fontSize = 11.sp)
            }
        }
    )
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
        // C-58: the gate's own state, before the limits it acts on: the operator needs to
        // know whether PATTERN and STRATEGY are inputs yet, not only that risk is capped.
        LayeredEvidenceCard(state)
        // C-60: the limits themselves, configured once and read by every gate.
        ThresholdsCard(state = state, actions = actions)
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

/**
 * C-58: which of PRICE, PATTERN and STRATEGY are live for the coin under inspection.
 *
 * PRICE is the only layer that withholds a board, so an unproven price layer reads
 * `BLOCKING`; PATTERN and STRATEGY read `INACTIVE` until their own 85% floor is met and
 * then become additional inputs without ever un-blocking anything. Each row prints the
 * evidence it is being judged on, so "not yet" always names the sample still missing.
 *
 * The projection is [LayeredEvidencePresentation], pinned by its own scenario, so the
 * wording does not depend on this layout.
 */
@Composable
private fun LayeredEvidenceCard(state: MainUiState) {
    val rows = LayeredEvidencePresentation.rows(state.layeredEvidence)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp)
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag(ProactiveWorkspaceTags.LAYERED_EVIDENCE)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LAYERED EVIDENCE GATE",
                color = SlateTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = state.selectedAsset?.let { asset ->
                    "${asset.symbol.replace("USDT", "")} · " +
                        "${state.thresholds.describeFloor()} floor"
                } ?: "no coin selected",
                color = CyanAccent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(ProactiveWorkspaceTags.layer(row.layer.name)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.layer.name,
                    color = SlateTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                val tone = when {
                    row.active -> EmeraldBull
                    row.statusLabel == LayeredEvidencePresentation.BLOCKING -> AmberWarning
                    else -> SlateTextMuted
                }
                Box(
                    modifier = Modifier
                        .background(tone.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .border(1.dp, tone.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = row.statusLabel,
                        color = tone,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                text = row.detail,
                color = SlateTextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Text(
            text = "PRICE holds staging; PATTERN and STRATEGY add inputs only once proven.",
            color = SlateTextMuted,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * C-60: the thresholds the gate is applying, and the values this coin is judged by.
 *
 * The card is the only place these numbers can be changed, and every change goes through
 * `ThresholdConfigService`: persisted, installed for every gate at once, and recorded as a
 * `CONFIG_UPDATE` row in the trade-decisions ledger. A refused candidate leaves the live
 * configuration untouched and prints its reason here rather than silently clamping.
 */
@Composable
private fun ThresholdsCard(state: MainUiState, actions: ProactiveWorkspaceActions) {
    val config = state.thresholds
    val rows = ThresholdPresentation.rows(config, state.layeredEvidence)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp)
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag(ProactiveWorkspaceTags.THRESHOLDS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EVIDENCE THRESHOLDS",
                color = SlateTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${config.describeFloor()} · GLOBAL",
                color = CyanAccent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(ProactiveWorkspaceTags.thresholdLayer(row.layer.name)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.layer.name,
                    color = SlateTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${row.floorLabel} · ${row.samplesLabel}",
                    color = SlateTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = row.effective,
                color = SlateTextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        ThresholdStepper("FLOOR", config.describeFloor(), ThresholdField.FLOOR, actions)
        ThresholdStepper("FAST MIN", config.minSettledFast.toString(), ThresholdField.FAST_SAMPLES, actions)
        ThresholdStepper("SLOW MIN", config.minSettledSlow.toString(), ThresholdField.SLOW_SAMPLES, actions)
        ThresholdStepper(
            "STRATEGY MIN",
            config.minSettledStrategy.toString(),
            ThresholdField.STRATEGY_SAMPLES,
            actions
        )
        ThresholdStepper(
            "IN-FORCE MIN",
            config.describeInForce(),
            ThresholdField.IN_FORCE_MINUTES,
            actions
        )

        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .background(BrandDark, RoundedCornerShape(4.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(4.dp))
                .clickable { actions.onResetThresholds() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(ProactiveWorkspaceTags.THRESHOLDS_RESET)
        ) {
            Text(
                text = "RESET TO 85.0% / 20 / 60 MIN",
                color = SlateTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (state.thresholdRejection != null) {
            Text(
                text = "Refused: ${state.thresholdRejection}",
                color = AmberWarning,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp)
                    .testTag(ProactiveWorkspaceTags.THRESHOLDS_REJECTION)
            )
        }

        Text(
            text = "Floor is clamped to 50–95%. A zero sample floor would leave PRICE " +
                "unproven but unblocking, so it is refused, and every accepted change is " +
                "recorded in the trade-decisions ledger. Evidence stays in force for the " +
                "configured window (5–1440 min), longer for long horizons; stale evidence " +
                "is refused by every gate.",
            color = SlateTextMuted,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** One configured limit, with its own − and + step. */
@Composable
private fun ThresholdStepper(
    label: String,
    value: String,
    field: ThresholdField,
    actions: ProactiveWorkspaceActions
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = SlateTextPrimary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThresholdStepButton("−", ProactiveWorkspaceTags.thresholdStep(field, -1)) {
                actions.onAdjustThreshold(field, -1)
            }
            Text(
                text = value,
                color = CyanAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            ThresholdStepButton("+", ProactiveWorkspaceTags.thresholdStep(field, 1)) {
                actions.onAdjustThreshold(field, 1)
            }
        }
    }
}

@Composable
private fun ThresholdStepButton(label: String, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(BrandDark, RoundedCornerShape(4.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(tag)
    ) {
        Text(
            text = label,
            color = SlateTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
