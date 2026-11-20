package com.aistudio.alphapulse.domain.presentation

import com.aistudio.alphapulse.domain.decision.DecisionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 §3: the one-line liquidity magnet under the spot price, and D1's width
 * budget for the tokenized ledger reason.
 *
 * The prior must name a real, derived price (spot × the path's own drift) instead of
 * a hardcoded level, and it must stay a single line on a 320dp viewport: a fixed
 * 42-character token is what makes D1 testable without an emulator.
 *
 * Spec Ref: Phase 1 §3 Directional Pain Path Prior, D1 String Tokenization
 */
class PainPathPriorBddSpec {

    @Test
    fun `given an ascending liquidity path when the prior is rendered then it names the magnet and its score`() {
        val prior = PainPathPrior.of(spotPrice = 23.0, muPath = 0.0369, confidence = 0.5)

        assertTrue("The prior must name the route: $prior", prior!!.startsWith("Pain Path: Ascending Sweep to $"))
        assertTrue("The prior must name the overhead cluster: $prior", prior.contains("Overhead Stop Cluster"))
        assertTrue("The prior must carry a bounded score: $prior", prior.contains("(Pain Score: "))
    }

    @Test
    fun `given a descending path when the prior is rendered then it points at support`() {
        val prior = PainPathPrior.of(spotPrice = 23.0, muPath = -0.02, confidence = 0.8)!!

        assertTrue(prior.contains("Descending Flush"))
        assertTrue(prior.contains("Support Stop Cluster"))
    }

    @Test
    fun `given a flat or unusable path when the prior is rendered then no magnet is invented`() {
        assertNull(PainPathPrior.of(spotPrice = 23.0, muPath = 0.0, confidence = 0.9))
        assertNull(PainPathPrior.of(spotPrice = 0.0, muPath = 0.01, confidence = 0.9))
        assertNull(PainPathPrior.of(spotPrice = 23.0, muPath = Double.NaN, confidence = 0.9))
    }

    @Test
    fun `given any confidence when the pain score is computed then it stays inside 0 to 100`() {
        assertEquals(0, PainPathPrior.painScore(0.0, 0.0))
        assertEquals(40, PainPathPrior.painScore(0.0, 1.0))
        assertTrue(PainPathPrior.painScore(10.0, 10.0) <= 100)
        assertTrue(PainPathPrior.painScore(-10.0, -10.0) >= 0)
    }

    @Test
    fun `given every verdict and source when the reason is tokenized then it fits the published column budget`() {
        val states = listOf("STRONG_LONG", "LONG", "NEUTRAL", "SHORT", "STRONG_SHORT", "NO_TRADE")
        // A controller name that is longer than any real router must still be
        // compacted rather than overflowing the column.
        val sources = DecisionSource.entries.map { it.name } + "SOME_FUTURE_ROUTER"

        for (source in sources) {
            for (state in states) {
                for (confidence in listOf(0.0, 0.5, 0.69, 0.7, 1.0)) {
                    val token = TokenizedReason.of(source, state, confidence)

                    assertTrue(
                        "The token must stay inside the published budget: '$token'",
                        token.length <= TokenizedReason.MAX_TOKEN_CHARS
                    )
                    assertTrue(
                        "The token must carry its model, action, score, gate and fallback: $token",
                        token.startsWith("[") &&
                            token.contains(" @ ") &&
                            token.contains(" < 0.70]") &&
                            token.contains("Fallback: ")
                    )
                }
            }
        }
    }
}
