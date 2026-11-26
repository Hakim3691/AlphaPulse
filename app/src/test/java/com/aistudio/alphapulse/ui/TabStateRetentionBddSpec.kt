package com.aistudio.alphapulse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C-62 — the workspace's tab and coin survive recreation.
 *
 * The remembered workspace state could not cross an activity recreation, so a rotation
 * put the desk back on AlphaPulse with no coin. The state is now one saveable value.
 *
 * Spec Ref: §72, C-62
 */
class TabStateRetentionBddSpec {

    @Test
    fun `given a coin is being inspected on Audit when the workspace is saved and restored then the same tab and coin return`() {
        val before = ProactiveMsWorkspaceState(activeTab = ProactiveMsTab.AUDIT)
            .selectSymbol("PAXGUSDT")

        val restored = ProactiveMsWorkspaceState.fromSaveable(before.toSaveable())

        assertEquals(ProactiveMsTab.AUDIT, restored.activeTab)
        assertEquals("PAXGUSDT", restored.activeSymbol)
    }

    @Test
    fun `given no coin has been selected when the workspace is restored then no symbol is invented`() {
        val restored = ProactiveMsWorkspaceState.fromSaveable(
            ProactiveMsWorkspaceState(activeTab = ProactiveMsTab.REPLAY).toSaveable()
        )

        assertEquals(ProactiveMsTab.REPLAY, restored.activeTab)
        assertNull(restored.activeSymbol)
    }

    @Test
    fun `given a saved state from an older build with an unknown tab name when restored then it falls back to AlphaPulse`() {
        val restored = ProactiveMsWorkspaceState.fromSaveable(listOf("RETIRED_TAB", null))

        assertEquals(ProactiveMsTab.ALPHA_PULSE, restored.activeTab)
        assertNull(restored.activeSymbol)
    }

    @Test
    fun `given the selected campaign when the universe is reapplied then it stays selected, and a vanished campaign falls back to the first board`() {
        // The live wiring keeps the inspected board by id; this pins the rule it applies.
        assertEquals("BOARD_B", WorkspaceSelection.keepCampaign("BOARD_B", listOf("BOARD_A", "BOARD_B")))
        assertEquals("BOARD_A", WorkspaceSelection.keepCampaign("BOARD_GONE", listOf("BOARD_A", "BOARD_B")))
        assertEquals("BOARD_A", WorkspaceSelection.keepCampaign(null, listOf("BOARD_A", "BOARD_B")))
        assertNull(WorkspaceSelection.keepCampaign(null, emptyList()))
    }
}
