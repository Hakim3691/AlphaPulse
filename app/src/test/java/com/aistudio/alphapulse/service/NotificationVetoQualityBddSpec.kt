package com.aistudio.alphapulse.service

import com.aistudio.alphapulse.domain.quality.DataQualityEngine
import com.aistudio.alphapulse.domain.quality.DataQualityInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the notification veto when it is driven by the data-quality report.
 *
 * §35 introduced one verdict about the feed; §58 suppresses alerts that would be
 * misleading. This spec makes the two agree: the veto now reads the same report the
 * dashboard shows, so a signal cannot be "fresh" on one screen and "stale" on the
 * other.
 *
 * Spec Ref: §58 Notification Veto, §35 Data Quality Engine
 */
class NotificationVetoQualityBddSpec {

    private val now = 1_700_000_000_000L

    private fun quality(
        lastTickMs: Long = now - 1_000L,
        requiredModelAvailable: Boolean = true
    ) = DataQualityEngine.evaluate(
        DataQualityInput(
            lastTickTimestampMs = lastTickMs,
            nowMs = now,
            bookLevels = 20,
            spreadBps = 1.0,
            requiredModelAvailable = requiredModelAvailable
        )
    )

    @Test
    fun `given a healthy feed and moderate disagreement when checked then the alert is delivered`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.2f,
            modelDisagreement = 0.2f,
            quality = quality()
        )

        assertFalse("A healthy feed must not suppress the alert", vetoed)
        assertNull(reason)
    }

    @Test
    fun `given a stale feed when checked then the veto fires and reports the age in minutes`() {
        val stale = quality(lastTickMs = now - 6 * 60 * 1000L)

        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.2f,
            modelDisagreement = 0.2f,
            quality = stale
        )

        assertTrue("Stale data must suppress the alert", vetoed)
        assertNotNull(reason)
        assertTrue("Reason must state the age (got: $reason)", reason!!.contains("min old"))
    }

    @Test
    fun `given no market data has ever arrived when checked then the veto says so explicitly`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.2f,
            modelDisagreement = 0.2f,
            quality = quality(lastTickMs = 0L)
        )

        assertTrue(vetoed)
        assertTrue(
            "The reason must distinguish 'no data' from 'old data' (got: $reason)",
            reason!!.contains("no market data")
        )
    }

    @Test
    fun `given a missing required model when checked then the veto names it`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.2f,
            modelDisagreement = 0.2f,
            quality = quality(requiredModelAvailable = false)
        )

        assertTrue(vetoed)
        assertTrue("(got: $reason)", reason!!.contains("Required model unavailable"))
    }

    @Test
    fun `given extreme uncertainty with high disagreement when checked then condition one still wins`() {
        val (vetoed, reason) = NotificationVeto.checkVeto(
            lobUncertainty = 0.95f,
            modelDisagreement = 0.9f,
            quality = quality()
        )

        assertTrue(vetoed)
        assertTrue(reason!!.contains("uncertainty"))
        assertTrue(reason.contains("disagreement"))
    }
}
