package com.aistudio.alphapulse.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the notification veto, specified as Given/When/Then.
 *
 * §58 requires the system to suppress a high-priority signal whenever one of
 * three conditions holds - and, crucially, to *log the reason* so the missing
 * alert is never a mystery. Every test therefore checks both halves: the veto
 * fired (or did not), and a reason exists (or does not) to match.
 *
 * Spec Ref: §58 Notification Veto
 */
class NotificationVetoBddSpec {

    /** A healthy market: confident, fresh data, required model present. */
    private val healthyUncertainty = 0.30f
    private val healthyDisagreement = 0.20f
    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------ condition 1

    @Test
    fun `given extreme lob uncertainty and high model disagreement when checked then the notification is vetoed with a reason naming both numbers`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.95f,
            modelDisagreement = 0.85f,
            lastDataTimestampMs = now(),
            requiredModelAvailable = true
        )

        assertTrue("Both veto conditions are met, so the alert must be suppressed", vetoed)
        assertNotNull("§58 requires the reason to be logged", reason)
        assertTrue("Reason must mention uncertainty", reason!!.contains("uncertainty"))
        assertTrue("Reason must mention disagreement", reason.contains("disagreement"))
    }

    @Test
    fun `given uncertainty alone when checked then no veto fires because both conditions are required`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.99f,
            modelDisagreement = 0.10f, // low disagreement
            lastDataTimestampMs = now(),
            requiredModelAvailable = true
        )

        assertFalse("Uncertainty alone must not suppress the signal", vetoed)
        assertNull("A passing check must not report a reason", reason)
    }

    @Test
    fun `given disagreement alone when checked then no veto fires because both conditions are required`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.10f,
            modelDisagreement = 0.99f,
            lastDataTimestampMs = now(),
            requiredModelAvailable = true
        )

        assertFalse("Disagreement alone must not suppress the signal", vetoed)
        assertNull(reason)
    }

    @Test
    fun `given values exactly on the thresholds when checked then the veto fires because the comparison is inclusive`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.80f, // exactly the extreme threshold
            modelDisagreement = 0.70f, // exactly the high threshold
            lastDataTimestampMs = now(),
            requiredModelAvailable = true
        )

        assertTrue("Thresholds are inclusive (>=), so 0.80/0.70 must veto", vetoed)
        assertNotNull(reason)
    }

    // ------------------------------------------------------------ condition 2

    @Test
    fun `given data older than five minutes when checked then the notification is vetoed and the age is reported in minutes`() {
        val sixMinutesAgo = now() - 6 * 60 * 1000L
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = healthyUncertainty,
            modelDisagreement = healthyDisagreement,
            lastDataTimestampMs = sixMinutesAgo,
            requiredModelAvailable = true
        )

        assertTrue("Stale data must suppress the signal", vetoed)
        assertNotNull(reason)
        assertTrue("Reason must mention freshness", reason!!.contains("freshness"))
        assertTrue("Reason must state the age (got: $reason)", reason.contains("min old"))
    }

    @Test
    fun `given fresh data when checked then no freshness veto fires`() {
        val (vetoed, _) = NotificationVeto.checkVeto(
            lobUncertainty = healthyUncertainty,
            modelDisagreement = healthyDisagreement,
            lastDataTimestampMs = now() - 1_000L,
            requiredModelAvailable = true
        )

        assertFalse("One-second-old data must not veto", vetoed)
    }

    @Test
    fun `given a zero timestamp meaning data never arrived when checked then freshness is not treated as stale`() {
        // 0 is the documented "never received" marker, not an epoch date.
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = healthyUncertainty,
            modelDisagreement = healthyDisagreement,
            lastDataTimestampMs = 0L,
            requiredModelAvailable = true
        )

        assertFalse(
            "A never-received marker must not be read as a 56-year-old timestamp",
            vetoed
        )
        assertNull(reason)
    }

    // ------------------------------------------------------------ condition 3

    @Test
    fun `given the required model is unavailable when checked then the notification is vetoed with an explicit reason`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = healthyUncertainty,
            modelDisagreement = healthyDisagreement,
            lastDataTimestampMs = now(),
            requiredModelAvailable = false
        )

        assertTrue("A missing model must suppress the signal", vetoed)
        assertTrue(
            "Reason must name the missing model (got: $reason)",
            reason!!.contains("model unavailable")
        )
    }

    // ----------------------------------------------------------- all clear

    @Test
    fun `given a healthy market when checked then nothing is vetoed and no reason is returned`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = healthyUncertainty,
            modelDisagreement = healthyDisagreement,
            lastDataTimestampMs = now(),
            requiredModelAvailable = true
        )

        assertFalse("A healthy market must deliver its alert", vetoed)
        assertNull("No reason should be logged when nothing was suppressed", reason)
    }
}
