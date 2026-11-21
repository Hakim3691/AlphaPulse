package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.engine.proactive.ProductionExecutionConfig

/**
 * Phase 1 §7 / Phase 2 §32 — what each ProactiveMS tab actually shows.
 *
 * The shipped host used to raise a `Toast` with the tab's own label, which meant the
 * workspace selection the contract requires was never rendered anywhere. Keeping the
 * rendering here (in the base module, beside [ProactiveMsWorkspaceState]) makes every tab
 * a pure function of the live state and the shared selection, so it can be driven by
 * `ProactiveTabsBddSpec` and reused by the feature host without the feature owning any
 * layout resources.
 *
 * Spec Ref: Phase 1 §7, Phase 2 §32, C-22
 */
object ProactiveTabRenderer {

    fun lines(
        tab: ProactiveMsTab,
        state: MainUiState,
        workspace: ProactiveMsWorkspaceState,
        config: ProductionExecutionConfig = ProductionExecutionConfig.PRODUCTION
    ): List<String> = when (tab) {
        ProactiveMsTab.SCREENER -> screener(workspace)
        ProactiveMsTab.ALPHA_PULSE -> alphaPulse(state, workspace)
        ProactiveMsTab.DASHBOARD -> dashboard(state)
        ProactiveMsTab.TELEMETRY -> telemetry(state)
        ProactiveMsTab.GUARDRAILS -> guardrails(state, config)
        ProactiveMsTab.REPLAY -> replay(state)
        ProactiveMsTab.AUDIT -> audit(state)
    }

    private fun screener(workspace: ProactiveMsWorkspaceState): List<String> = buildList {
        add("Verified universe (${workspace.availableSymbols.size})")
        if (workspace.availableSymbols.isEmpty()) {
            add("No coin has been screened yet")
        } else {
            workspace.availableSymbols.forEach { add("• $it") }
        }
        add(
            workspace.activeSymbol?.let { "Active: $it" }
                ?: "Active: No coin selected"
        )
    }

    private fun alphaPulse(
        state: MainUiState,
        workspace: ProactiveMsWorkspaceState
    ): List<String> = buildList {
        val asset = state.selectedAsset
        if (asset == null) {
            add(workspace.activeSymbol?.let { "Active: $it" } ?: "Active: No coin selected")
            add("AlphaPulse is waiting for the first evaluation")
        } else {
            add("${asset.symbol} · ${asset.regime.label}")
            add("Last: ${asset.lastPrice}")
            add("Pain path: ${asset.primaryPainPath}")
            add("24h: ${asset.priceChange24h}% · spread ${asset.orderBookSpreadPct}%")
        }
    }

    private fun dashboard(state: MainUiState): List<String> = buildList {
        val campaigns = state.campaigns
        add("Campaigns: ${campaigns.size}")
        if (campaigns.isEmpty()) {
            add("No campaigns staged")
        } else {
            campaigns.take(6).forEach { campaign ->
                add(
                    "${campaign.asset} · ${campaign.family.displayName} · " +
                        "${campaign.status.label} · score ${campaign.invalidationScore}"
                )
            }
        }
    }

    private fun telemetry(state: MainUiState): List<String> = buildList {
        val health = state.venueHealth
        add("Venue: ${if (health.isHealthy) "HEALTHY" else "DEGRADED"}")
        add("Binance ping: ${health.binancePingMs}ms · CoinGlass ${health.coinglassPingMs}ms")
        add("Feed: ${if (health.isFeedStale) "STALE (${health.feedStalenessSec}s)" else "LIVE"}")
        add("Book spread: ${health.spreadBps}bps · rejects ${health.orderRejectRatePct}%")
        add("Universe: ${if (state.isLiveUniverse) "live" else "seed"}")
    }

    private fun guardrails(
        state: MainUiState,
        config: ProductionExecutionConfig
    ): List<String> = buildList {
        val risk = state.riskEnvelope
        add("Mode: ${state.executionMode.name} · ${state.executionMode.displayName}")
        add("Kill switch: ${if (risk.isKillSwitchEngaged) "ENGAGED" else "off"}")
        add("Risk per campaign: ${config.accountRiskPerCampaignPct}%")
        add("Daily loss stop: ${config.maxDailyLossR}R")
        add("Re-entry lockout: ${config.reentryCooldownMinutes} min")
        add("Soft/Hard invalidation: ${config.softInvalidationThreshold}/${config.hardInvalidationThreshold}")
        add("Spread ceiling: ${config.maxSpreadPct}%")
    }

    private fun replay(state: MainUiState): List<String> = buildList {
        if (state.isReplayRunning) add("Replay running…")
        val replay = state.lastReplay
        if (replay == null && !state.isReplayRunning) {
            add("No replay has run")
        } else if (replay != null) {
            add("Ticks replayed: ${replay.totalTicks}")
            add("Campaigns: ${replay.metrics.replayedCampaigns}")
            add("Total PnL: ${replay.metrics.totalPnlUsd}")
            if (replay.degradedFeeds) add("Degraded feeds: crowding/liquidity absent")
        }
    }

    private fun audit(state: MainUiState): List<String> = buildList {
        add("Audit ledger (latest ${state.auditLogs.size})")
        if (state.auditLogs.isEmpty()) {
            add("No audit decisions recorded")
        } else {
            state.auditLogs.take(8).forEach { entry ->
                add("${entry.asset} · ${entry.action.name} · ${entry.reasonCode}")
            }
        }
    }
}
