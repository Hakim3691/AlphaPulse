package com.aistudio.alphapulse.ui

/**
 * §72 / Contract C-22 — the six primary tabs and the one symbol the whole workspace
 * shares.
 *
 * Why this exists
 * ---------------
 * The ProactiveMS workstation is not six screens that happen to live in one app: it is
 * one selected asset seen six ways. Choosing a coin in `AlphaPulse` has to change the
 * dashboard, the guardrail readout, the replay window and the audit log together. When
 * each screen kept its own selection the tabs disagreed about which coin was being
 * inspected, which is the one state a desk cannot tolerate.
 *
 * So the selection is a single immutable value and the tabs are an ordered enum, not a
 * list of strings scattered across the UI. The order is part of the contract: it is what
 * the bottom bar renders and what the UI test compares against.
 *
 * The legacy `Screener` tab is retired. It listed the same coins the workspace already
 * had, under a `$50M–$750M` mid-cap band that C-17 removed, and it made a second coin
 * selector that could disagree with AlphaPulse's. AlphaPulse's institutional screen is
 * now the only selector: every other tab works from the screened coins, predictions,
 * patterns and strategies it produces.
 *
 * Spec Ref: §72 Unified Tab Navigation, §65, C-22
 */

/** The six primary bottom-navigation tabs, in the order the bar renders them. */
enum class ProactiveMsTab(val label: String) {
    ALPHA_PULSE("AlphaPulse"),
    DASHBOARD("Dashboard"),
    TELEMETRY("Telemetry"),
    GUARDRAILS("Guardrails"),
    REPLAY("Replay"),
    AUDIT("Audit")
}

/**
 * The shared workspace selection.
 *
 * @param activeTab the tab the workspace is showing; AlphaPulse, the selector, first
 * @param activeSymbol the coin every tab is showing; null before the first selection
 */
data class ProactiveMsWorkspaceState(
    val activeTab: ProactiveMsTab = ProactiveMsTab.ALPHA_PULSE,
    val activeSymbol: String? = null
) {

    /** Switching tabs never changes which coin is being inspected. */
    fun selectTab(tab: ProactiveMsTab): ProactiveMsWorkspaceState = copy(activeTab = tab)

    /** Selecting a coin in AlphaPulse is selecting it in all six tabs. */
    fun selectSymbol(symbol: String): ProactiveMsWorkspaceState {
        require(symbol.isNotBlank()) { "A workspace symbol cannot be blank" }
        return copy(activeSymbol = symbol)
    }

    companion object {
        /** The rendered tab order, for the bottom bar and for the UI contract test. */
        val TAB_ORDER: List<ProactiveMsTab> = ProactiveMsTab.entries.toList()
    }
}
