package com.aistudio.alphapulse.domain.ensemble

import com.aistudio.alphapulse.domain.quant.ModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of adaptive ensemble learning on settled predictions, specified as
 * Given/When/Then (spec §20 Adaptive Ensemble Learning, §31 Model Ensemble
 * Health): every active member — the core tri-factor and the mounted W4/W5
 * TSFM members alike — receives an SGD gradient, and ΣW stays 1.000.
 */
class AdaptiveEnsembleLearningBddSpec {

    private fun ModelState.sum(): Double =
        weightK + weightP + weightAlpha + weight4 + weight5

    private fun assertNormalised(state: ModelState) {
        assertEquals(1.0, state.sum(), 1e-9)
        assertTrue("W1 must stay non-negative", state.weightK >= 0.0)
        assertTrue("W2 must stay non-negative", state.weightP >= 0.0)
        assertTrue("W3 must stay non-negative", state.weightAlpha >= 0.0)
        assertTrue("W4 must stay non-negative", state.weight4 >= 0.0)
        assertTrue("W5 must stay non-negative", state.weight5 >= 0.0)
    }

    @Test
    fun `given a mounted chronos member when a settled prediction rewards its drift then weight4 rises and sigma stays one`() {
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos"))
        val mountShare = state.weight4

        // Market realised more bullish drift than the ensemble predicted and
        // Chronos pointed the right way (muW4 > 0), so its share must grow.
        EnsembleWeighting.applyAdamWStep(
            state = state,
            errorMu = 1e-3,
            muK = 1e-4,
            muP = 1e-4,
            muAlpha = 1e-4,
            muW4 = 5e-4,
            muW5 = 0.0,
            step = 1
        )

        assertTrue(
            "Chronos share must respond to a rewarded gradient (was $mountShare, now ${state.weight4})",
            state.weight4 > mountShare
        )
        assertNormalised(state)
        assertEquals("Live SGD Active", state.mode)
    }

    @Test
    fun `given a mounted chronos member when a settled prediction penalises its drift then weight4 falls and sigma stays one`() {
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos"))
        val mountShare = state.weight4

        // Market realised less bullish drift than predicted while Chronos was
        // bullish (muW4 > 0, errorMu < 0), so its share must shrink.
        EnsembleWeighting.applyAdamWStep(
            state = state,
            errorMu = -1e-3,
            muK = 1e-4,
            muP = 1e-4,
            muAlpha = 1e-4,
            muW4 = 5e-4,
            muW5 = 0.0,
            step = 1
        )

        assertTrue(
            "Chronos share must respond to a penalised gradient (was $mountShare, now ${state.weight4})",
            state.weight4 < mountShare
        )
        assertNormalised(state)
    }

    @Test
    fun `given a settled prediction where no member carried drift when the ledger settles then every weight keeps its share`() {
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos", "timesfm"))
        val before = listOf(state.weightK, state.weightP, state.weightAlpha, state.weight4, state.weight5)

        EnsembleWeighting.applyAdamWStep(
            state = state,
            errorMu = 5e-4,
            muK = 0.0,
            muP = 0.0,
            muAlpha = 0.0,
            muW4 = 0.0,
            muW5 = 0.0,
            step = 1
        )

        assertEquals(before, listOf(state.weightK, state.weightP, state.weightAlpha, state.weight4, state.weight5))
        assertNormalised(state)
    }

    @Test
    fun `given an unmounted chronos member when sgd settles then weight4 stays zero and the core absorbs the whole budget`() {
        val state = ModelState() // weight4 = 0.0, never mounted

        // The repository records muW4 = 0.0 for a prediction issued while
        // Chronos is unmounted (muChronos falls back to 0.0), so the absent
        // member's share must never leave zero.
        EnsembleWeighting.applySettledSgdStep(
            state = state,
            errorMu = 5e-4,
            muK = 2e-4,
            muP = 1e-4,
            muAlpha = 1e-4,
            muW4 = 0.0,
            muW5 = 0.0
        )

        assertEquals(0.0, state.weight4, 0.0)
        assertEquals(0.0, state.weight5, 0.0)
        assertEquals(1.0, state.weightK + state.weightP + state.weightAlpha, 1e-9)
        assertNormalised(state)
    }

    @Test
    fun `given a non finite error when the ledger settles then the weights are untouched`() {
        for (badError in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val state = ModelState()
            EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos"))
            val before = listOf(state.weightK, state.weightP, state.weightAlpha, state.weight4, state.weight5)

            EnsembleWeighting.applySettledSgdStep(
                state = state,
                errorMu = badError,
                muK = 1e-4,
                muP = 1e-4,
                muAlpha = 1e-4,
                muW4 = 5e-4,
                muW5 = 1e-4
            )

            assertEquals("errorMu=$badError must not move weights", before, listOf(state.weightK, state.weightP, state.weightAlpha, state.weight4, state.weight5))
        }
    }

    @Test
    fun `given a long session of settled predictions when learning runs then the chronos share actually moves and invariants hold`() {
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, listOf("chronos", "timesfm"))
        val start = state.weight4

        // Deterministic session: 200 settlements where Chronos consistently
        // carries twice the core drift with a positive forecast error.
        repeat(200) { i ->
            EnsembleWeighting.applySettledSgdStep(
                state = state,
                errorMu = 5e-4,
                muK = 1e-4,
                muP = 1e-4,
                muAlpha = 1e-4,
                muW4 = 2e-4,
                muW5 = if (i % 2 == 0) 1e-4 else -1e-4
            )
            assertNormalised(state)
        }

        assertTrue(
            "Chronos weight must adapt over a session (started at $start, ended at ${state.weight4})",
            state.weight4 > start + 5e-3
        )
    }
}
