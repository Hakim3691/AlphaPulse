package com.aistudio.alphapulse.domain.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 1 catalogue must be complete and honest.
 *
 * A catalogue is a published taxonomy with published baselines; the only thing
 * AlphaPulse may learn is per-setup weight. These scenarios pin both halves so a
 * later edit cannot shrink the taxonomy back to nine models or claim a better
 * win rate than the catalogue recorded.
 *
 * Spec Ref: Phase 1 §3.1 Comprehensive Trade Ideas & Pattern / Quant Catalog, D7
 */
class TradeIdeasCatalogBddSpec {

    @Test
    fun `given the catalogue when it is read then all four mandated groups are present`() {
        val ids = TradeIdeasCatalog.all.map { it.id }.toSet()

        listOf("hanging-man", "hammer", "bullish-engulfing", "morning-star", "three-white-soldiers")
            .forEach { assertTrue("Candlestick setup missing: $it", it in ids) }
        listOf("double-top", "head-shoulders-top", "rising-wedge", "ascending-triangle")
            .forEach { assertTrue("Macro formation missing: $it", it in ids) }
        listOf("sma-5-20-cross", "macd-zero-cross", "rsi-oversold-rebound", "bollinger-reversion")
            .forEach { assertTrue("Indicator combo missing: $it", it in ids) }
        listOf("turtle-breakout", "dual-thrust", "orb", "super-trend", "donchian", "connors-rsi", "vwap-reversion", "cmf", "triple-screen")
            .forEach { assertTrue("Quant rule missing: $it", it in ids) }
    }

    @Test
    fun `given the catalogue when it is read then it is far larger than the old nine-model limit`() {
        assertTrue("Catalogue must exceed the 9-model baseline", TradeIdeasCatalog.all.size >= 40)
    }

    @Test
    fun `given a candlestick reversal when its baseline is read then it sits inside the published band`() {
        val hammer = TradeIdeasCatalog.byId("hammer")!!

        assertTrue("Win rate must be inside +64..73%", hammer.winRate in 0.64..0.73)
        assertTrue("Avg return must be positive", hammer.avgReturn > 0.0)
        assertEquals("+68% WR \u00b7 +2.4% AR", hammer.baselineLabel())
    }

    @Test
    fun `given a trending-up regime when candidates are selected then counter-trend setups are excluded`() {
        val candidates = TradeIdeasCatalog.candidatesFor(Regime.TRENDING_UP)
        val ids = candidates.map { it.id }

        assertTrue("Trend setups belong in a trend", "turtle-breakout" in ids)
        assertTrue("Mean-reversion short setup must not fire in an uptrend", "hanging-man" !in ids)
    }

    @Test
    fun `given a mean-reverting regime when candidates are ranked then the highest empirical edge comes first`() {
        val candidates = TradeIdeasCatalog.candidatesFor(Regime.MEAN_REVERTING)

        assertTrue("A mean-reverting regime must have candidates", candidates.isNotEmpty())
        val edges = candidates.map { it.winRate * it.avgReturn }
        assertEquals(edges.sortedDescending(), edges)
    }

    @Test
    fun `given a category filter when it is read then it returns only that category`() {
        assertEquals(
            TradeIdeasCatalog.quant.size,
            TradeIdeasCatalog.forCategory(CatalogueCategory.QUANT).size
        )
        assertTrue(
            TradeIdeasCatalog.forCategory(CatalogueCategory.QUANT)
                .all { it.category == CatalogueCategory.QUANT }
        )
    }

    @Test
    fun `given a setup that produces an impulse within three candles then its weight is reinforced`() {
        val weights = CatalogueWeights()

        val next = weights.record("hammer", producedImpulseWithinCandles = true)

        assertTrue("Reinforcement must raise the weight above the 1.0 baseline", next > 1.0)
        assertEquals(1.05, next, 1e-9)
    }

    @Test
    fun `given repeated false alarms then the weight decays but never retires the setup`() {
        val weights = CatalogueWeights()

        repeat(100) { weights.record("rising-wedge", producedImpulseWithinCandles = false) }

        assertEquals(CatalogueWeights.MIN_WEIGHT, weights.weightOf("rising-wedge"), 1e-9)
    }

    @Test
    fun `given two setups when one learns then the other keeps its own weight`() {
        val weights = CatalogueWeights()
        weights.record("hammer", producedImpulseWithinCandles = false)

        assertEquals(1.0, weights.weightOf("morning-star"), 1e-9)
        assertNotNull(TradeIdeasCatalog.byId("morning-star"))
    }

    @Test
    fun `given price and a zone centre when ATR distance is computed then it is normalised by ATR`() {
        assertEquals(0.3, atrDistance(priceLast = 23.0, zoneCenter = 23.3, atr = 1.0), 1e-9)
        assertTrue(atrDistance(23.0, 23.3, 0.0).isNaN())
    }
}
