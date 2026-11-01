package com.aistudio.alphapulse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §72 / Contract C-22 — the unified seven-tab navigation and its shared active symbol.
 *
 * The tab order and the workspace state live in production
 * (`ui/ProactiveMsNavigation.kt`); this spec drives them, so dropping a tab, reordering
 * the bar, or letting a tab keep its own symbol breaks the build.
 *
 * Spec Ref: §72, C-22
 */
class ProactiveMsNavigationBddSpec {

    @Test
    fun `given the ProactiveMS bottom navigation when inspected then exactly seven tabs are mounted in the specified order`() {
        assertEquals(
            listOf(
                "Screener",
                "AlphaPulse",
                "Dashboard",
                "Telemetry",
                "Guardrails",
                "Replay",
                "Audit"
            ),
            ProactiveMsWorkspaceState.TAB_ORDER.map { it.label }
        )
        assertEquals(7, ProactiveMsTab.entries.size)
    }

    @Test
    fun `given a coin is selected in the Screener tab when the operator moves to the Audit tab then the same symbol is active with no re-selection`() {
        var workspace = ProactiveMsWorkspaceState(
            verifiedSymbols = listOf("BTCUSDT", "PAXGUSDT", "SOPHUSDT")
        )

        workspace = workspace.selectSymbol("PAXGUSDT")
        workspace = workspace.selectTab(ProactiveMsTab.AUDIT)

        assertEquals(ProactiveMsTab.AUDIT, workspace.activeTab)
        assertEquals("PAXGUSDT", workspace.activeSymbol)

        // Every other tab reports the same selection: it is one value, not seven copies.
        for (tab in ProactiveMsTab.entries) {
            assertEquals("PAXGUSDT", workspace.selectTab(tab).activeSymbol)
        }
    }

    @Test
    fun `given the plus BTC Only lock is enabled when the operator inspects the universe then only BTCUSDT remains selectable and active`() {
        val workspace = ProactiveMsWorkspaceState(
            activeTab = ProactiveMsTab.SCREENER,
            activeSymbol = "PAXGUSDT",
            verifiedSymbols = listOf("BTCUSDT", "PAXGUSDT", "SOPHUSDT")
        ).setBtcOnly(true)

        assertEquals(listOf("BTCUSDT"), workspace.availableSymbols)
        assertEquals("BTCUSDT", workspace.activeSymbol)

        // Lifting the lock restores the full verified universe and keeps the BTC selection.
        val unlocked = workspace.setBtcOnly(false)
        assertEquals(3, unlocked.availableSymbols.size)
        assertEquals("BTCUSDT", unlocked.activeSymbol)
    }

    @Test
    fun `given no coin has been selected yet when the workspace is read then no symbol is invented for the tabs`() {
        val workspace = ProactiveMsWorkspaceState()

        assertNull(workspace.activeSymbol)
        assertEquals(ProactiveMsTab.SCREENER, workspace.activeTab)
        assertTrue(workspace.availableSymbols.isEmpty())
    }
}
