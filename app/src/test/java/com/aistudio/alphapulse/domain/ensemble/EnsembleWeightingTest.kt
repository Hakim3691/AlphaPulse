package com.aistudio.alphapulse.domain.ensemble

import com.aistudio.alphapulse.domain.quant.ModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleWeightingTest {

    private fun ModelState.sum(): Double =
        weightK + weightP + weightAlpha + weight4 + weight5

    @Test
    fun `baseline ensemble is tri-factor with unit total`() {
        val state = ModelState()

        assertEquals(3, EnsembleWeighting.activeModelCount(emptyList()))
        assertEquals(3, EnsembleWeighting.weightVector(state, emptyList()).size)
        assertEquals(1.0, state.sum(), 1e-9)
        assertEquals("TRI-FACTOR ENSEMBLE TARGET FORECASTS", EnsembleWeighting.forecastTitle(3))
    }

    @Test
    fun `mounting chronos expands to quad-factor and keeps weights normalised`() {
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos"))

        assertEquals(4, EnsembleWeighting.activeModelCount(listOf("chronos")))
        assertEquals(4, EnsembleWeighting.weightVector(state, listOf("chronos")).size)
        assertEquals(1.0, state.sum(), 1e-9)
        assertEquals(0.10, state.weight4, 1e-9)
        assertEquals(0.90, state.weightK + state.weightP + state.weightAlpha, 1e-9)
        // Core split is preserved while the budget shrinks.
        assertEquals(0.30 / 0.35, state.weightK / state.weightP, 1e-9)
        assertEquals("QUAD-FACTOR ENSEMBLE TARGET FORECASTS", EnsembleWeighting.forecastTitle(4))
    }

    @Test
    fun `mounting both models expands to penta-factor`() {
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos", "timesfm"))

        assertEquals(5, EnsembleWeighting.activeModelCount(listOf("chronos", "timesfm")))
        assertEquals(1.0, state.sum(), 1e-9)
        assertEquals(0.10, state.weight4, 1e-9)
        assertEquals(0.10, state.weight5, 1e-9)
        assertEquals(0.80, state.weightK + state.weightP + state.weightAlpha, 1e-9)
        assertEquals("PENTA-FACTOR ENSEMBLE TARGET FORECASTS", EnsembleWeighting.forecastTitle(5))
    }

    @Test
    fun `rebalance is idempotent`() {
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("timesfm"))
        val firstK = state.weightK
        val firstP = state.weightP
        val firstA = state.weightAlpha

        EnsembleWeighting.rebalanceForMountedModels(state, listOf("timesfm"))

        assertEquals(firstK, state.weightK, 1e-9)
        assertEquals(firstP, state.weightP, 1e-9)
        assertEquals(firstA, state.weightAlpha, 1e-9)
        assertEquals(0.0, state.weight4, 1e-9)
        assertEquals(0.10, state.weight5, 1e-9)
    }

    @Test
    fun `mounted ids are canonical regardless of input order or case`() {
        assertEquals(
            listOf("chronos", "timesfm"),
            EnsembleWeighting.mountedExtraIds(listOf("TIMESFM", "Chronos"))
        )
        assertEquals(listOf("timesfm"), EnsembleWeighting.mountedExtraIds(listOf("timesfm", "unknown")))
    }

    @Test
    fun `formula lists every active term`() {
        assertTrue(
            EnsembleWeighting.formula(3)
                .contains("W1·M1(t) + W2·M2(t) + W3·M3(t)")
        )
        assertTrue(
            EnsembleWeighting.formula(5)
                .contains("W1·M1(t) + W2·M2(t) + W3·M3(t) + W4·M4(t) + W5·M5(t)")
        )
        assertTrue(EnsembleWeighting.formula(4).contains("[ΣW = 1.000]"))
        assertTrue(!EnsembleWeighting.formula(4).contains("W5"))
    }

    @Test
    fun `weight vector exposes values keyed to each member`() {
        val state = ModelState(weightK = 0.4, weightP = 0.15, weightAlpha = 0.45)
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos"))
        val vector = EnsembleWeighting.weightVector(state, listOf("chronos"))

        assertEquals(listOf("W1", "W2", "W3", "W4"), vector.map { it.member.weightSymbol })
        assertEquals(0.10, vector.last().weight, 1e-9)
        assertEquals(1.0, vector.sumOf { it.weight }, 1e-9)
        assertEquals("Chronos-Bolt TSFM", vector.last().member.modelLabel)
    }
}
