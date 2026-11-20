package com.aistudio.alphapulse.engine.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §38 / Contract C-49 — the base-view frame-health producer.
 *
 * Spec Ref: Phase 2 §38
 */
class BaseViewHealthMonitorBddSpec {

    private val monitor = BaseViewHealthMonitor()

    @Test
    fun `given fewer than a full frame window when frames are recorded then no observation exists and the dimension is unobserved`() {
        repeat(BaseViewHealthMonitor.DEFAULT_WINDOW_SAMPLES - 1) {
            assertNull(monitor.recordFrame(16.0))
        }
    }

    @Test
    fun `given a full window of healthy frames when observed then the p95 is inside budget and no regression is reported`() {
        var observation: BaseViewObservation? = null
        repeat(BaseViewHealthMonitor.DEFAULT_WINDOW_SAMPLES) {
            observation = monitor.recordFrame(16.0) ?: observation
        }

        assertNotNull(observation)
        assertEquals(16.0, observation!!.p95FrameMs, 0.001)
        assertFalse(observation!!.regressionDetected)
    }

    @Test
    fun `given a full window of janky frames when observed then the p95 exceeds the 60fps budget and reports a regression`() {
        var observation: BaseViewObservation? = null
        repeat(BaseViewHealthMonitor.DEFAULT_WINDOW_SAMPLES) {
            observation = monitor.recordFrame(40.0) ?: observation
        }

        assertNotNull(observation)
        assertTrue(observation!!.regressionDetected)
        assertTrue(observation!!.p95FrameMs > BaseViewHealthMonitor.DEFAULT_P95_BUDGET_MS)
    }

    @Test
    fun `given an observed regression when a later clean window is observed then the latest window clears it`() {
        val tracker = PaperTrialEvidenceTracker()

        tracker.recordBaseViewObservation(regressionDetected = true)
        assertTrue(tracker.evidence().baseViewRegressionDetected)

        tracker.recordBaseViewObservation(regressionDetected = false)
        assertTrue(tracker.evidence().baseViewObserved)
        assertFalse(tracker.evidence().baseViewRegressionDetected)
    }
}
