package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-57 — only a proven horizon, per coin, feeds a campaign.
 *
 * The confirmed gate is 85%, with a sample floor that scales with how often the horizon
 * settles: 20 for the 1m/5m cones, 4 for 15m/30m/1h. These scenarios pin both halves of
 * each boundary and the per-horizon granularity the campaign feed relies on.
 *
 * Spec Ref: Phase 2 C-57, §22, §67
 */
class SignalEvidenceBddSpec {

    /** Pinned clock: these scenarios measure their evidence now (C-61). */
    private val NOW = 1_800_000_000_000L

    @Test
    fun `given a one minute horizon at exactly eighty five percent of twenty settled when assessed then it qualifies`() {
        // Given: 17 of 20 = 85.0%, the confirmed floor.
        val evidence = SignalEvidence.assess("1m", settled = 20, hits = 17, measuredAt = NOW, nowMs = NOW)

        // Then: exactly 85% qualifies, and the integer comparison says so.
        assertEquals(85.0, evidence.hitRatePct, 1e-9)
        assertTrue("85% of 20 is the minimum that counts as evidence", evidence.qualified)
    }

    @Test
    fun `given a one minute horizon at eighty percent of twenty settled when assessed then it is withheld`() {
        // Given: 16 of 20 = 80%, below the confirmed 85%.
        val evidence = SignalEvidence.assess("1m", settled = 20, hits = 16, measuredAt = NOW, nowMs = NOW)

        // Then: the earlier 80% floor is replaced, not honoured.
        assertEquals(80.0, evidence.hitRatePct, 1e-9)
        assertFalse("80% no longer clears the confirmed floor", evidence.qualified)
    }

    @Test
    fun `given an hour horizon with four of four calls when assessed then it qualifies on its own smaller floor`() {
        // Given: an hourly cone can only settle four times in four hours.
        val evidence = SignalEvidence.assess("1h", settled = 4, hits = 4, measuredAt = NOW, nowMs = NOW)

        // Then: four perfect calls are the hourly equivalent of twenty 1m calls.
        assertTrue(evidence.qualified)
        assertEquals(4, evidence.minSettled)
    }

    @Test
    fun `given an hour horizon with three of four calls when assessed then it is withheld`() {
        // Given: 3/4 = 75%.
        val evidence = SignalEvidence.assess("1h", settled = 4, hits = 3, measuredAt = NOW, nowMs = NOW)

        // Then: a smaller sample is not a weaker standard.
        assertFalse(evidence.qualified)
    }

    @Test
    fun `given an hour horizon with only three settled calls when assessed then the sample is too small even at a perfect rate`() {
        val evidence = SignalEvidence.assess("1h", settled = 3, hits = 3, measuredAt = NOW, nowMs = NOW)

        assertEquals(100.0, evidence.hitRatePct, 1e-9)
        assertFalse("Three perfect calls cannot express an 85% rate", evidence.qualified)
    }

    @Test
    fun `given a fifteen minute horizon at four settled when assessed then it uses the four sample floor`() {
        assertTrue(SignalEvidence.assess("15m", settled = 4, hits = 4, measuredAt = NOW, nowMs = NOW).qualified)
        assertFalse(SignalEvidence.assess("15m", settled = 3, hits = 3, measuredAt = NOW, nowMs = NOW).qualified)
        assertEquals(4, SignalEvidence.minSettledFor("15m"))
        assertEquals(4, SignalEvidence.minSettledFor("30m"))
        assertEquals(20, SignalEvidence.minSettledFor("1m"))
        assertEquals(20, SignalEvidence.minSettledFor("5m"))
    }

    @Test
    fun `given a large sample below the rate when assessed then a bigger sample does not lower the bar`() {
        val evidence = SignalEvidence.assess("1h", settled = 1000, hits = 849, measuredAt = NOW, nowMs = NOW)

        assertEquals(84.9, evidence.hitRatePct, 1e-9)
        assertFalse(evidence.qualified)
    }

    @Test
    fun `given no settlement at all when assessed then the rate is zero and the horizon is withheld rather than assumed`() {
        val evidence = SignalEvidence.assess("15m", settled = 0, hits = 0, measuredAt = NOW, nowMs = NOW)

        assertEquals(0.0, evidence.hitRatePct, 0.0)
        assertFalse(evidence.qualified)
    }

    @Test
    fun `given an incoherent record when assessed then hits are clamped instead of inflating the rate`() {
        assertFalse(
            "More hits than settlements cannot qualify a horizon",
            SignalEvidence.isQualified("1m", settled = 10, hits = 40, measuredAt = NOW, nowMs = NOW)
        )
        assertEquals(6, SignalEvidence.assess("1h", settled = 6, hits = 99, measuredAt = NOW, nowMs = NOW).hits)
        assertEquals(0, SignalEvidence.assess("1h", settled = 6, hits = -3).hits)
    }

    @Test
    fun `given a withheld horizon when described then the line states its rate, its sample and its own requirement`() {
        val evidence = SignalEvidence.assess("1h", settled = 34, hits = 24, measuredAt = NOW, nowMs = NOW)

        assertFalse(evidence.qualified)
        assertEquals(
            "1h 70.6% of 34 settled (needs 85.0% of 4)",
            evidence.describe()
        )
    }
}
