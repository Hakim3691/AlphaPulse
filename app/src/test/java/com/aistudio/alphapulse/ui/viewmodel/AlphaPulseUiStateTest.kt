package com.aistudio.alphapulse.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-state invariants for the always-visible workspace panels (spec section 39)
 * that can be verified on the JVM without Android or a database.
 */
class AlphaPulseUiStateTest {

    @Test
    fun `default state loads with pipeline banner and empty selection`() {
        val state = AlphaPulseUiState()
        assertTrue(state.pipelineRunning)
        assertTrue(state.pipelineStepTitle.contains("Step 1"))
        assertFalse(state.isReady)
        assertEquals(0, state.screenedCoins.size)
        assertEquals("ALL", state.horizonFilter)
    }
}
