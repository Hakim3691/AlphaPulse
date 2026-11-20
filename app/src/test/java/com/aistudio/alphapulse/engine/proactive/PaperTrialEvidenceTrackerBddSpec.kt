package com.aistudio.alphapulse.engine.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §38 / Contract C-47 — the paper-trading evidence source.
 *
 * Spec Ref: Phase 2 §38
 */
class PaperTrialEvidenceTrackerBddSpec {

    private fun trackerWithPassingPaperTrial(): PaperTrialEvidenceTracker {
        val tracker = PaperTrialEvidenceTracker()
        repeat(100) { tracker.recordCompletedPaperTrade() }
        repeat(100) { tracker.recordCancellationLatency(120L) }
        repeat(100) { tracker.recordMinimizeEvent(warnedAndWiped = true) }
        tracker.recordBaseViewObservation(regressionDetected = false)
        return tracker
    }

    @Test
    fun `given paper trading meets all four criteria when evidence is read then the verdict unlocks scaled live`() {
        val tracker = trackerWithPassingPaperTrial()

        val evidence = tracker.evidence()
        val verdict = tracker.verdict()

        assertEquals(100, evidence.consecutivePaperTrades)
        assertEquals(0, evidence.unhandledExceptions)
        assertEquals(120L, evidence.cancellationLatencyMsP95)
        assertEquals(100, evidence.minimizeEventsTested)
        assertEquals(100, evidence.minimizeEventsWarnedAndWiped)
        assertTrue(verdict.unlocked)
        assertTrue(verdict.unlocks(com.aistudio.alphapulse.data.model.ExecutionMode.SCALED_LIVE))
    }

    @Test
    fun `given only 99 completed paper trades when evidence is read then scaled live stays locked`() {
        val tracker = PaperTrialEvidenceTracker()
        repeat(99) { tracker.recordCompletedPaperTrade() }
        repeat(99) { tracker.recordCancellationLatency(90L) }
        repeat(99) { tracker.recordMinimizeEvent(true) }

        val verdict = tracker.verdict()

        assertFalse(verdict.unlocked)
        assertTrue(verdict.failures.any { it.contains("99/100") })
    }

    @Test
    fun `given an unhandled exception mid streak when it is followed by 100 clean trades then the fresh streak unlocks`() {
        val tracker = trackerWithPassingPaperTrial()

        tracker.recordUnhandledException()
        assertEquals(0, tracker.evidence().consecutivePaperTrades)
        assertFalse("An exception must restart the consecutive streak", tracker.verdict().unlocked)

        repeat(100) { tracker.recordCompletedPaperTrade() }

        assertEquals(100, tracker.evidence().consecutivePaperTrades)
        assertEquals(0, tracker.evidence().unhandledExceptions)
        assertTrue(
            "The 100 trades after the exception are the consecutive, exception-free streak",
            tracker.verdict().unlocked
        )
    }

    @Test
    fun `given ten percent of cancellations at 200ms when the p95 is computed then the latency criterion fails`() {
        val tracker = PaperTrialEvidenceTracker()
        repeat(100) { tracker.recordCompletedPaperTrade() }
        repeat(90) { tracker.recordCancellationLatency(100L) }
        repeat(10) { tracker.recordCancellationLatency(200L) }
        repeat(100) { tracker.recordMinimizeEvent(true) }

        assertEquals(200L, tracker.evidence().cancellationLatencyMsP95)
        assertFalse(tracker.verdict().unlocked)
        assertTrue(tracker.verdict().failures.any { it.contains("cancellation latency") })
    }

    @Test
    fun `given no cancellation was ever measured when evidence is read then the gate reports a missing benchmark instead of a pass`() {
        val tracker = PaperTrialEvidenceTracker()
        repeat(100) { tracker.recordCompletedPaperTrade() }
        repeat(100) { tracker.recordMinimizeEvent(true) }

        assertEquals(
            PaperTrialEvidenceTracker.NO_LATENCY_SAMPLE,
            tracker.evidence().cancellationLatencyMsP95
        )
        assertFalse(tracker.verdict().unlocked)
        assertTrue(
            tracker.verdict().failures.any { it.contains("no cancellation latency measured") }
        )
    }

    @Test
    fun `given the intercept wiped ram without warning on a minimize event when evaluated then that event is not credited`() {
        val tracker = PaperTrialEvidenceTracker()
        repeat(100) { tracker.recordCompletedPaperTrade() }
        repeat(100) { tracker.recordCancellationLatency(80L) }
        repeat(99) { tracker.recordMinimizeEvent(warnedAndWiped = true) }
        tracker.recordMinimizeEvent(warnedAndWiped = false)

        val evidence = tracker.evidence()
        assertEquals(100, evidence.minimizeEventsTested)
        assertEquals(99, evidence.minimizeEventsWarnedAndWiped)
        assertFalse(tracker.verdict().unlocked)
    }

    @Test
    fun `given instrumentation observed a base view regression when evaluated then scaled live stays locked`() {
        val tracker = trackerWithPassingPaperTrial()

        tracker.recordBaseViewObservation(regressionDetected = true)

        assertTrue(tracker.evidence().baseViewRegressionDetected)
        assertFalse(tracker.verdict().unlocked)
    }

    @Test
    fun `given no base view window was ever observed when evaluated then the dimension is unmet instead of clean`() {
        val tracker = PaperTrialEvidenceTracker()
        repeat(100) { tracker.recordCompletedPaperTrade() }
        repeat(100) { tracker.recordCancellationLatency(50L) }
        repeat(100) { tracker.recordMinimizeEvent(warnedAndWiped = true) }

        val evidence = tracker.evidence()
        assertFalse(evidence.baseViewObserved)
        assertFalse(evidence.baseViewRegressionDetected)
        assertFalse(tracker.verdict().unlocked)
        assertTrue(
            tracker.verdict().failures.any { it.contains("base view behaviour never observed") }
        )
    }
}
