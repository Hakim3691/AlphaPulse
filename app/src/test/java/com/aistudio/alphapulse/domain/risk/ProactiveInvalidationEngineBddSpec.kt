package com.aistudio.alphapulse.domain.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §69 / Contract C-20 — ProactiveMS walk-forward cancellation-first invalidation
 * (0.50R default candidate, fixed 2.00R backstop).
 *
 * The engine under test lives in production (`domain/risk/ProactiveInvalidationEngine.kt`);
 * this spec drives it, so deleting or weakening the decision breaks the build instead of
 * passing against a copy declared inside the test.
 *
 * Spec Ref: §69, C-20
 */
class ProactiveInvalidationEngineBddSpec {

    @Test
    fun `given valid model thesis with insignificant intrabar noise when evaluated then engine returns HOLD`() {
        val engine = ProactiveInvalidationEngine(InvalidationConfig(proactiveMaxLossR = 0.50))
        val input = PositionEvaluationInput(
            currentPnlR = -0.20,
            coneBreachZScore = 2.10,
            // One bar only: the breach has not persisted, so this is intrabar noise.
            coneBreachPersistenceBars = 1,
            sgdRankBefore = 1,
            sgdRankAfter = 2
        )

        val decision = engine.evaluate(input)

        assertEquals(TriggerType.NONE, decision.triggerType)
        assertEquals(ProactiveAction.HOLD, decision.action)
    }

    @Test
    fun `given statistically significant cone breach when loss is 0_35R within default 0_50R tolerance then engine triggers PROACTIVE_CANCEL_AND_EXIT`() {
        val engine = ProactiveInvalidationEngine(InvalidationConfig(proactiveMaxLossR = 0.50))
        val input = PositionEvaluationInput(
            currentPnlR = -0.35,
            coneBreachZScore = 2.45,
            coneBreachPersistenceBars = 2,
            sgdRankBefore = 2,
            sgdRankAfter = 2
        )

        val decision = engine.evaluate(input)

        assertEquals(TriggerType.STATISTICALLY_SIGNIFICANT_CONE_BREACH, decision.triggerType)
        assertEquals(ProactiveAction.PROACTIVE_CANCEL_AND_EXIT, decision.action)
        assertTrue(decision.exitedBefore05R)
        assertTrue(decision.exitedBefore10R)
    }

    @Test
    fun `given qualifying SGD reranking shift when loss is 0_45R within 0_50R tolerance then engine triggers PROACTIVE_CANCEL_AND_EXIT`() {
        val engine = ProactiveInvalidationEngine(
            InvalidationConfig(proactiveMaxLossR = 0.50, minSgdRankDrop = 3)
        )
        val input = PositionEvaluationInput(
            currentPnlR = -0.45,
            coneBreachZScore = 0.80,
            coneBreachPersistenceBars = 0,
            sgdRankBefore = 1,
            // Fell four places, past the three-place threshold.
            sgdRankAfter = 5
        )

        val decision = engine.evaluate(input)

        assertEquals(TriggerType.SGD_RERANKING_SHIFT, decision.triggerType)
        assertEquals(ProactiveAction.PROACTIVE_CANCEL_AND_EXIT, decision.action)
        assertTrue(decision.exitedBefore05R)
    }

    @Test
    fun `given cone breach when loss is 0_80R exceeding 0_50R proactive tolerance but below 2_00R hard stop then engine continues risk evaluation without overriding hard stop`() {
        val engine = ProactiveInvalidationEngine(InvalidationConfig(proactiveMaxLossR = 0.50))
        val input = PositionEvaluationInput(
            currentPnlR = -0.80,
            coneBreachZScore = 2.60,
            coneBreachPersistenceBars = 3,
            sgdRankBefore = 1,
            sgdRankAfter = 4
        )

        val decision = engine.evaluate(input)

        assertEquals(TriggerType.DUAL_CONE_AND_SGD_SHIFT, decision.triggerType)
        assertEquals(
            ProactiveAction.CONTINUE_RISK_EVALUATION_NO_HARD_STOP_OVERRIDE,
            decision.action
        )
        assertFalse(decision.exitedBefore05R)
    }

    @Test
    fun `given loss reaches or exceeds 2_00R across any candidate proactive threshold from 0_25R to 1_50R then engine unconditionally forces 2_00R hard stop`() {
        InvalidationConfig.SUPPORTED_PROACTIVE_THRESHOLDS.forEach { candidateR ->
            val engine = ProactiveInvalidationEngine(
                InvalidationConfig(proactiveMaxLossR = candidateR)
            )
            val input = PositionEvaluationInput(
                currentPnlR = -2.00,
                coneBreachZScore = 0.50,
                coneBreachPersistenceBars = 0,
                sgdRankBefore = 1,
                sgdRankAfter = 1
            )

            val decision = engine.evaluate(input)

            assertEquals(TriggerType.HARD_STOP_BACKSTOP, decision.triggerType)
            assertEquals(ProactiveAction.FORCE_HARD_STOP_2R, decision.action)
        }
    }

    @Test
    fun `given a configuration that widens the hard stop or picks an unvalidated threshold when constructed then it is rejected rather than run`() {
        var widenedHardStop = false
        try {
            InvalidationConfig(proactiveMaxLossR = 0.50, hardStopMaxLossR = 3.00)
        } catch (expected: IllegalArgumentException) {
            widenedHardStop = true
            assertTrue(expected.message!!.contains("2.0"))
        }
        assertTrue("A candidate must never widen the 2.00R backstop", widenedHardStop)

        var unvalidatedThreshold = false
        try {
            // 0.60R was never in the walk-forward sweep, so it cannot run in production.
            InvalidationConfig(proactiveMaxLossR = 0.60)
        } catch (expected: IllegalArgumentException) {
            unvalidatedThreshold = true
            assertTrue(expected.message!!.contains("walk-forward"))
        }
        assertTrue("Only swept candidates may run", unvalidatedThreshold)
    }
}
