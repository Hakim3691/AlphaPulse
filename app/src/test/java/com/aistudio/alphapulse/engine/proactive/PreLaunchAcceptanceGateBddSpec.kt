package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.ExecutionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §38 / Contract C-46 — the mandatory pre-launch gate before Scaled Live.
 *
 * Spec Ref: Phase 2 §38
 */
class PreLaunchAcceptanceGateBddSpec {

    private val gate = PreLaunchAcceptanceGate()

    private fun evidence(
        consecutivePaperTrades: Int = 100,
        unhandledExceptions: Int = 0,
        cancellationLatencyMsP95: Long = 120L,
        minimizeEventsTested: Int = 100,
        minimizeEventsWarnedAndWiped: Int = 100,
        baseViewRegressionDetected: Boolean = false
    ) = PreLaunchEvidence(
        consecutivePaperTrades = consecutivePaperTrades,
        unhandledExceptions = unhandledExceptions,
        cancellationLatencyMsP95 = cancellationLatencyMsP95,
        minimizeEventsTested = minimizeEventsTested,
        minimizeEventsWarnedAndWiped = minimizeEventsWarnedAndWiped,
        baseViewRegressionDetected = baseViewRegressionDetected
    )

    @Test
    fun `given every section 38 proof is met when evaluated then scaled live unlocks and no other mode is affected`() {
        val verdict = gate.evaluate(evidence())

        assertTrue(verdict.unlocked)
        assertTrue(verdict.failures.isEmpty())
        assertTrue(verdict.unlocks(ExecutionMode.SCALED_LIVE))
        assertTrue(verdict.unlocks(ExecutionMode.CAPPED_LIVE))
        assertTrue(verdict.unlocks(ExecutionMode.PAPER))
        assertTrue(verdict.unlocks(ExecutionMode.SHADOW))
    }

    @Test
    fun `given fewer than 100 consecutive paper trades when evaluated then scaled live stays locked`() {
        val verdict = gate.evaluate(evidence(consecutivePaperTrades = 99))

        assertFalse(verdict.unlocked)
        assertTrue(verdict.failures.any { it.contains("100") })
        assertTrue(verdict.summary.contains("Scaled Live locked"))
    }

    @Test
    fun `given one unhandled exception or a 180ms cancellation when evaluated then scaled live stays locked`() {
        assertFalse(gate.evaluate(evidence(unhandledExceptions = 1)).unlocked)
        assertFalse(
            "The criterion is under 180ms, so exactly 180ms does not pass",
            gate.evaluate(evidence(cancellationLatencyMsP95 = 180L)).unlocked
        )
        assertTrue(gate.evaluate(evidence(cancellationLatencyMsP95 = 179L)).unlocked)
    }

    @Test
    fun `given a foreground intercept that missed a minimize event when evaluated then scaled live stays locked`() {
        val missedOne = gate.evaluate(
            evidence(minimizeEventsTested = 100, minimizeEventsWarnedAndWiped = 99)
        )
        assertFalse(missedOne.unlocked)
        assertTrue(missedOne.failures.any { it.contains("100/100") || it.contains("99/100") })

        val untested = gate.evaluate(evidence(minimizeEventsTested = 0, minimizeEventsWarnedAndWiped = 0))
        assertFalse("An untested intercept cannot pass", untested.unlocked)
    }

    @Test
    fun `given the cancellation latency was never measured when evaluated then scaled live stays locked`() {
        val verdict = gate.evaluate(evidence(cancellationLatencyMsP95 = -1L))

        assertFalse("A missing benchmark is not a pass", verdict.unlocked)
        assertTrue(verdict.failures.any { it.contains("no cancellation latency measured") })
    }

    @Test
    fun `given the base AlphaPulse view regressed when evaluated then scaled live stays locked`() {
        val verdict = gate.evaluate(evidence(baseViewRegressionDetected = true))

        assertFalse(verdict.unlocked)
        assertTrue(verdict.failures.any { it.contains("regression") })
    }
}
