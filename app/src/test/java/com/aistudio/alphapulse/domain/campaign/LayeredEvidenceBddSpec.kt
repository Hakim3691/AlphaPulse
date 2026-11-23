package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-57 — the layered gate: price first, pattern and strategy additive.
 *
 * Given/When/Then:
 *  - given no proven price horizon, when the verdict is read, then staging is held and
 *    the reason names the horizon and its sample.
 *  - given a proven price horizon and unproven pattern/strategy, when the verdict is
 *    read, then the board may stage and only PRICE is active.
 *  - given pattern and strategy above their own floors, when the verdict is read, then
 *    they become active inputs without changing the price decision.
 *  - given a strategy sample below twenty graded calls, when the verdict is read, then
 *    the strategy layer stays inactive even at 100%.
 *
 * Spec Ref: Phase 2 C-57, §22/§67/§73/§74
 */
class LayeredEvidenceBddSpec {

    @Test
    fun `given no proven price horizon when assessed then the board is withheld and the reason names the horizon`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1h", settled = 2, hits = 1))
        )

        assertFalse(verdict.canStage)
        assertEquals(1, verdict.blockingReasons.size)
        assertTrue(
            "The refusal must state the horizon and the evidence",
            verdict.blockingReasons.single().contains("1h 50.0% of 2 settled")
        )
        assertTrue(verdict.activeLayers.isEmpty())
    }

    @Test
    fun `given a proven price horizon when assessed then the board may stage even with no pattern or strategy evidence`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("5m", settled = 20, hits = 17))
        )

        assertTrue(verdict.canStage)
        assertEquals(listOf("5m"), verdict.provenPriceHorizons)
        assertEquals(setOf(EvidenceLayer.PRICE), verdict.activeLayers)
        assertTrue(verdict.blockingReasons.isEmpty())
    }

    @Test
    fun `given proven pattern and strategy evidence when assessed then both are active inputs on top of price`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1m", settled = 20, hits = 17)),
            pattern = listOf(LayeredEvidenceGate.pattern("15m", settled = 4, hits = 4)),
            strategy = LayeredEvidenceGate.strategy("BTCUSDT", settled = 20, hits = 18)
        )

        assertTrue(verdict.canStage)
        assertEquals(
            setOf(EvidenceLayer.PRICE, EvidenceLayer.PATTERN, EvidenceLayer.STRATEGY),
            verdict.activeLayers
        )
        assertEquals(listOf("15m"), verdict.provenPatternHorizons)
    }

    @Test
    fun `given an unproven pattern horizon when assessed then it is simply inactive rather than blocking`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1m", settled = 20, hits = 17)),
            pattern = listOf(LayeredEvidenceGate.pattern("1h", settled = 4, hits = 3))
        )

        assertTrue("Pattern never blocks a proven price layer", verdict.canStage)
        assertTrue(verdict.provenPatternHorizons.isEmpty())
        assertEquals(setOf(EvidenceLayer.PRICE), verdict.activeLayers)
    }

    @Test
    fun `given nineteen perfect strategy grades when assessed then the pool is too small to activate`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1m", settled = 20, hits = 17)),
            strategy = LayeredEvidenceGate.strategy("ETHUSDT", settled = 19, hits = 19)
        )

        assertFalse("Twenty graded calls is the confirmed strategy floor", EvidenceLayer.STRATEGY in verdict.activeLayers)
        assertTrue(verdict.canStage)
    }

    @Test
    fun `given a proven price horizon beside no price record at all when assessed then silence fails closed`() {
        val verdict = LayeredEvidenceGate.assess(price = emptyList())

        assertFalse(verdict.canStage)
        assertEquals(
            listOf("PRICE_LAYER_NOT_PROVEN(no price horizon has settled)"),
            verdict.blockingReasons
        )
    }

    @Test
    fun `given an active verdict when summarised then the board's note names the layers and proves the price`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("15m", settled = 4, hits = 4)),
            pattern = listOf(LayeredEvidenceGate.pattern("15m", settled = 4, hits = 4))
        )

        assertEquals(
            "Layered evidence active: PRICE+PATTERN (price 15m)",
            verdict.summary()
        )
    }
}
