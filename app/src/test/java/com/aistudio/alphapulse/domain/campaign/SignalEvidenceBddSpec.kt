package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-55 — only a proven horizon, per coin, feeds a campaign.
 *
 * The gate is 80% of at least twenty settled predictions, and it fails closed. These
 * scenarios pin both halves and the per-horizon granularity the campaign feed relies on.
 *
 * Spec Ref: Phase 2 C-55, §22
 */
class SignalEvidenceBddSpec {

    @Test
    fun `given a horizon at exactly eighty percent of twenty settled when assessed then it qualifies`() {
        val evidence = SignalEvidence.assess("1h", settled = 20, hits = 16)

        assertEquals(80.0, evidence.hitRatePct, 1e-9)
        assertTrue(
            "80% of 20 is the minimum that counts as evidence",
            evidence.qualified
        )
        assertTrue(SignalEvidence.isQualified(settled = 20, hits = 16))
    }

    @Test
    fun `given a horizon at seventy nine point nine percent when assessed then it is withheld`() {
        val evidence = SignalEvidence.assess("1h", settled = 1000, hits = 799)

        assertEquals(79.9, evidence.hitRatePct, 1e-9)
        assertFalse(
            "A large sample does not lower the bar",
            evidence.qualified
        )
    }

    @Test
    fun `given nineteen perfect calls when assessed then the sample is too small to be evidence`() {
        val evidence = SignalEvidence.assess("5m", settled = 19, hits = 19)

        assertEquals(100.0, evidence.hitRatePct, 1e-9)
        assertFalse(
            "Nineteen of nineteen is an anecdote, not a hit rate",
            evidence.qualified
        )
    }

    @Test
    fun `given no settlement at all when assessed then the rate is zero and the horizon is withheld rather than assumed`() {
        val evidence = SignalEvidence.assess("15m", settled = 0, hits = 0)

        assertEquals(0.0, evidence.hitRatePct, 0.0)
        assertFalse(evidence.qualified)
        assertFalse(SignalEvidence.isQualified(settled = 0, hits = 0))
    }

    @Test
    fun `given an incoherent record when assessed then hits are clamped instead of inflating the rate`() {
        assertFalse(
            "More hits than settlements cannot qualify a horizon",
            SignalEvidence.isQualified(settled = 10, hits = 40)
        )
        assertEquals(6, SignalEvidence.assess("1h", settled = 6, hits = 99).hits)
        assertEquals(0, SignalEvidence.assess("1h", settled = 6, hits = -3).hits)
    }

    @Test
    fun `given a withheld horizon when described then the line states its rate, its sample and the requirement`() {
        val evidence = SignalEvidence.assess("1h", settled = 34, hits = 24)

        assertFalse(evidence.qualified)
        assertEquals(
            "1h 70.6% of 34 settled (needs 80.0% of 20)",
            evidence.describe()
        )
    }
}
