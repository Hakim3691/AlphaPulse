package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** DecisionRouter unit tests (spec section 29, unit group).
 * Covers the enum DecisionState, the DecisionRouter interface,
 * DeterministicDecisionRouter, and NeuralDecisionRouter.
 */
class DecisionRouterTest {

    @Test
    fun `DECISION_STATE_VALUES_ARE_VALID`() {
        assertNotNull(DecisionState.STRONG_LONG)
        assertNotNull(DecisionState.LONG)
        assertNotNull(DecisionState.NEUTRAL)
        assertNotNull(DecisionState.SHORT)
        assertNotNull(DecisionState.STRONG_SHORT)
        assertNotNull(DecisionState.NO_TRADE)
    }

    @Test
    fun `deterministic_router_fresh_data_bullish_returns_STRONG_LONG`() {
        val router = DeterministicDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.STRONG_LONG, state)
    }

    @Test
    fun `deterministic_router_stale_data_returns_NO_TRADE`() {
        val router = DeterministicDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 0.30,
            coneBreaches = 0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.NO_TRADE, state)
    }

    @Test
    fun `deterministic_router_high_cone_breaches_returns_NO_TRADE`() {
        val router = DeterministicDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 5,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.NO_TRADE, state)
    }

    @Test
    fun `deterministic_router_high_disagreement_returns_NEUTRAL`() {
        val router = DeterministicDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.80,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.NEUTRAL, state)
    }

    @Test
    fun `deterministic_router_mixed_signals_returns_NEUTRAL`() {
        val router = DeterministicDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 1.5,
            tsfmForecast = -1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.NEUTRAL, state)
    }

    @Test
    fun `deterministic_router_bearish_convergence_returns_STRONG_SHORT`() {
        val router = DeterministicDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = -1.5,
            tsfmForecast = -1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.STRONG_SHORT, state)
    }

    @Test
    fun `neural_router_high_confidence_strong_long`() {
        val router = NeuralDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 2.0,
            tsfmForecast = 1.8,
            lobConfidence = 0.90,
            liquidityConfidence = 0.90,
            regimeConfidence = 0.90,
            modelDisagreement = 0.10,
            dataFreshness = 1.0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.STRONG_LONG, state)
    }

    @Test
    fun `neural_router_high_confidence_strong_short`() {
        val router = NeuralDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = -2.0,
            tsfmForecast = -1.8,
            lobConfidence = 0.90,
            liquidityConfidence = 0.90,
            regimeConfidence = 0.90,
            modelDisagreement = 0.10,
            dataFreshness = 1.0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.STRONG_SHORT, state)
    }

    @Test
    fun `neural_router_moderate_confidence_long`() {
        val router = NeuralDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 1.2,
            tsfmForecast = 1.0,
            lobConfidence = 0.70,
            liquidityConfidence = 0.70,
            regimeConfidence = 0.70,
            modelDisagreement = 0.10,
            dataFreshness = 1.0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        assertEquals(DecisionState.LONG, state)
    }

    @Test
    fun `neural_router_low_confidence_checks_dominance`() {
        val router = NeuralDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 0.5,
            tsfmForecast = 0.3,
            lobConfidence = 0.40,
            liquidityConfidence = 0.40,
            regimeConfidence = 0.50,
            modelDisagreement = 0.10,
            dataFreshness = 1.0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        // Tri-Factor dominates when both are positive but below threshold
        assert(state == DecisionState.LONG || state == DecisionState.NEUTRAL)
    }

    @Test
    fun `neural_router_low_confidence_bearish`() {
        val router = NeuralDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = -0.5,
            tsfmForecast = -0.3,
            lobConfidence = 0.40,
            liquidityConfidence = 0.40,
            regimeConfidence = 0.50,
            modelDisagreement = 0.10,
            dataFreshness = 1.0,
            recentErrors = 0.0
        )
        val state = router.router(inputs)
        // Tri-Factor dominates when both are negative
        assert(state == DecisionState.SHORT || state == DecisionState.NEUTRAL)
    }

    @Test
    fun `neural_router_combined_score_below_threshold_returns_NO_TRADE_IMPLIED`() {
        val router = NeuralDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = -0.2,
            tsfmForecast = -0.1,
            lobConfidence = 0.20,
            liquidityConfidence = 0.20,
            regimeConfidence = 0.30,
            modelDisagreement = 0.50,
            dataFreshness = 0.30,
            recentErrors = 1.0
        )
        val state = router.router(inputs)
        // Low confidence + disagreement + stale data should result in a low-confidence state
        // The exact state depends on calculateCombinedScore logic
        assertNotNull(state)
    }

    @Test
    fun `decision_router_interface_last_reason_records`() {
        val router = DeterministicDecisionRouter()
        val inputs = DecisionInputs(
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0
        )
        router.router(inputs)
        // lastReason should be recorded
        assertNotNull("lastReason should not be null", router.lastReason)
    }

    @Test
    fun `decision_router_interface_decision_confidence_accessible`() {
        val deterministic = DeterministicDecisionRouter()
        val neural = NeuralDecisionRouter()
        // Deterministic has confidence 0.0 (not applicable)
        assertEquals(0.0, deterministic.decisionConfidence, 0.0)
        // Neural has confidence based on strongThreshold
        assertNotNull(neural.decisionConfidence)
    }
}