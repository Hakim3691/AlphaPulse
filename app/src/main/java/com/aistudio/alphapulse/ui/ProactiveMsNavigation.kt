package com.aistudio.alphapulse.ui

/**
 * §72 / Contract C-22 — the seven primary tabs and the one symbol the whole
 * workstation shares.
 *
 * Why this exists
 * ---------------
 * The ProactiveMS workstation is not seven screens that happen to live in one app: it
 * is one selected asset seen seven ways. Choosing a coin in `Screener` has to change the
 * `AlphaPulse` dashboard, the guardrail readout, the replay window and the audit log
 * together. When each screen kept its own selection the tabs disagreed about which coin
 * was being inspected, which is the one state a desk cannot tolerate.
 *
 * So the selection is a single immutable value and the tabs are an ordered enum, not a
 * list of strings scattered across the UI. The order is part of the contract: it is what
 * the bottom bar renders and what the UI test compares against.
 *
 * `+ BTC Only` is modelled here too, because it is a universe decision rather than a
 * screen setting: turning it on collapses the active symbol and the available universe
 * to `BTCUSDT` in both universe tabs at once (§65).
 *
 * Spec Ref: §72 Unified 7-Tab Navigation, §65, C-22
 */

/** The seven primary bottom-navigation tabs, in the order the bar renders them. */
enum class ProactiveMsTab(val label: String) {
    SCREENER("Screener"),
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
 * @param activeSymbol the coin every tab is showing; null before the first selection
 * @param btcOnly the `+ BTC Only` universe lock
 * @param verifiedSymbols the symbols the §65 screener admitted, in its own ranking
 */
data class ProactiveMsWorkspaceState(
    val activeTab: ProactiveMsTab = ProactiveMsTab.SCREENER,
    val activeSymbol: String? = null,
    val btcOnly: Boolean = false,
    val verifiedSymbols: List<String> = emptyList()
) {

    /** Switching tabs never changes which coin is being inspected. */
    fun selectTab(tab: ProactiveMsTab): ProactiveMsWorkspaceState = copy(activeTab = tab)

    /** Selecting a coin anywhere is selecting it in all seven tabs. */
    fun selectSymbol(symbol: String): ProactiveMsWorkspaceState {
        require(symbol.isNotBlank()) { "A workspace symbol cannot be blank" }
        return copy(activeSymbol = symbol)
    }

    /**
     * Turns the `+ BTC Only` lock on or off.
     *
     * Enabling it collapses the active symbol to `BTCUSDT`; disabling it keeps whatever
     * symbol was showing rather than silently jumping back to the first one.
     */
    fun setBtcOnly(enabled: Boolean): ProactiveMsWorkspaceState = copy(
        btcOnly = enabled,
        activeSymbol = if (enabled) BTC_ONLY_SYMBOL else activeSymbol
    )

    /** The universe both universe tabs offer under the current lock. */
    val availableSymbols: List<String>
        get() = if (btcOnly) listOf(BTC_ONLY_SYMBOL) else verifiedSymbols

    companion object {
        const val BTC_ONLY_SYMBOL = "BTCUSDT"

        /** The rendered tab order, for the bottom bar and for the UI contract test. */
        val TAB_ORDER: List<ProactiveMsTab> = ProactiveMsTab.entries.toList()
    }
}
