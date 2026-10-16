package com.aistudio.alphapulse.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quantitative engine unit tests (spec section 52, unit group): volume
 * profile / VWAP / value area, UQ-LOB uncertainty, Markov-GARCH regime,
 * liquidity pain path, horizon pain path, temporal attention, confluence
 * alpha, and ensemble weight calibration.
 */
class QuantMathTest {

    private fun kline(openTime: Long, open: Double, high: Double, low: Double, close: Double, vol: Double = 1000.0) =
        KlineData(
            openTime = openTime,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = vol,
            quoteVolume = open * vol,
            takerBuyQuoteVolume = open * vol / 2.0
        )

    private fun syntheticKlines(count: Int, startPrice: Double, drift: Double): List<KlineData> {
        val out = mutableListOf<KlineData>()
        var price = startPrice
        val t0 = 1700000000000L
        for (i in 0 until count) {
            val open = price
            val close = price * (1.0 + drift)
            out.add(kline(t0 + i * 60000L, open, maxOf(open, close) * 1.0005, minOf(open, close) * 0.9995, close))
            price = close
        }
        return out
    }

    // ---- Volume Profile / Value Area -----------------------------------

    @Test
    fun `volume profile returns sorted value area and positive vwap`() {
        val klines = mutableListOf<KlineData>()
        val baseTime = 1700000000000L
        for (i in 0 until 30) {
            val p = 100.0 + (i % 5) * 2.0
            klines.add(
                KlineData(baseTime + i * 60000L, p, p + 1.0, p - 1.0, p + 0.5, 1000.0, 100000.0, 55000.0)
            )
        }

        val vp = QuantMath.computeVolumeProfileAndVWAP(klines, 102.0, isMomentumBurst = false)
        assertTrue("POC should be positive", vp.poc > 0.0)
        assertTrue("VAH should be >= VAL", vp.vah >= vp.valPrice)
        assertTrue("VWAP should be positive", vp.vwap > 0.0)
    }

    @Test
    fun `volume profile works on empty input without throwing`() {
        val vp = QuantMath.computeVolumeProfileAndVWAP(emptyList(), 0.0, isMomentumBurst = false)
        assertTrue(vp.poc >= 0.0)
        assertTrue(vp.vwap >= 0.0)
    }

    // ---- Liquidity Pain Path -------------------------------------------

    @Test
    fun `pain path is positive when the ask side is the thin side`() {
        // Heavy bid support, thin asks -> an institutional buy shock walks up a lazy ask book.
        val bids = (1..10).map { DepthTier(100.0 - it * 0.1, 10.0) }
        val asks = (1..10).map { DepthTier(100.0 + it * 0.1, 1.0) }

        val result = QuantMath.computeLiquidityPainPath(bids, asks, spotPrice = 100.0, empiricalShock = 1500.0)
        assertTrue("Thin asks must lift muPath above zero, got ${result.muPath}", result.muPath > 0.0)
        assertTrue(result.muPath < 0.05)
    }

    @Test
    fun `pain path is negative when the bid side is the thin side`() {
        val bids = (1..10).map { DepthTier(100.0 - it * 0.1, 1.0) }
        val asks = (1..10).map { DepthTier(100.0 + it * 0.1, 10.0) }

        val result = QuantMath.computeLiquidityPainPath(bids, asks, spotPrice = 100.0, empiricalShock = 1500.0)
        assertTrue("Thin bids must drag muPath below zero, got ${result.muPath}", result.muPath < 0.0)
    }

    @Test
    fun `pain path falls back to top-of-book imbalance on zero slippage`() {
        // Tick-0 books with perfectly symmetric depth elsewhere.
        val bids = List(10) { DepthTier(if (it == 0) 99.99 else 99.0, if (it == 0) 50.0 else 10.0) }
        val asks = List(10) { DepthTier(if (it == 0) 100.01 else 101.0, if (it == 0) 5.0 else 10.0) }

        val result = QuantMath.computeLiquidityPainPath(bids, asks, spotPrice = 100.0, empiricalShock = 0.0)
        assertTrue(result.source.startsWith("L2"))
        assertTrue("Fallback must flag bid pressure, got ${result.muPath}", result.muPath > 0.0)
    }

    @Test
    fun `pain path with empty book is neutral`() {
        val result = QuantMath.computeLiquidityPainPath(emptyList(), emptyList(), spotPrice = 100.0, empiricalShock = 1000.0)
        assertEquals(0.0, result.muPath, 1e-12)
    }

    @Test
    fun `horizon pain path for 15m blends POC gravity`() {
        val klines = syntheticKlines(40, startPrice = 100.0, drift = 0.001)
        val spot = 100.0
        val vp = QuantMath.computeVolumeProfileAndVWAP(klines, spot, isMomentumBurst = true)
        val bids = (1..20).map { DepthTier(spot - it * 0.05, 5.0) }
        val asks = (1..20).map { DepthTier(spot + it * 0.05, 5.0) }

        val result = QuantMath.computeHorizonPainPath(15, bids, asks, spot, empiricalShock = 1000.0, vp = vp, klines = klines)
        // POC above spot (uptrend) must not force the blended vector negative.
        assertTrue(result.source.startsWith("POC"))
    }

    @Test
    fun `horizon pain path for 1h honours the 80 percent rotation rule`() {
        val klines = mutableListOf<KlineData>()
        val baseTime = 1700000000000L
        for (i in 0 until 20) {
            klines.add(KlineData(baseTime + i * 60000L, 98.0, 102.0, 98.0, 100.0, 1000.0, 100000.0, 50000.0))
        }
        val vp = QuantMath.computeVolumeProfileAndVWAP(klines, 100.0, isMomentumBurst = false)
        val t = baseTime + 20 * 60000L
        klines.add(KlineData(t, vp.vah + 2.0, vp.vah + 3.0, vp.vah + 1.0, vp.vah + 2.0, 500.0, 50000.0, 25000.0))
        klines.add(KlineData(t + 60000L, vp.vah - 0.2, vp.vah + 0.5, vp.valPrice + 0.2, (vp.vah + vp.valPrice) / 2.0, 500.0, 50000.0, 25000.0))
        klines.add(KlineData(t + 120000L, (vp.vah + vp.valPrice) / 2.0, vp.vah, vp.valPrice, (vp.vah + vp.valPrice) / 2.0, 500.0, 50000.0, 25000.0))
        val rotated = QuantMath.computeVolumeProfileAndVWAP(klines, (vp.vah + vp.valPrice) / 2.0, isMomentumBurst = false)

        assertTrue("Rotation must trigger after 2 consecutive VA acceptances", rotated.rotationRuleTriggered)
        assertEquals(rotated.valPrice, rotated.rotationTarget!!, 0.001)

        val bids = (1..20).map { DepthTier(100.0 - it * 0.05, 5.0) }
        val asks = (1..20).map { DepthTier(100.0 + it * 0.05, 5.0) }
        val result = QuantMath.computeHorizonPainPath(
            60, bids, asks, (rotated.vah + rotated.valPrice) / 2.0,
            empiricalShock = 1000.0, vp = rotated, klines = klines
        )
        assertEquals("1h pain path is the 80% rotation drift", "80% VA Rotation", result.source)
        assertNotNull(result.muPath)
    }

    // ---- UQ-LOB ---------------------------------------------------------

    @Test
    fun `uq-lob on empty book is neutral uncertainty`() {
        val confidence = QuantMath.computeUQLOBUncertainty(emptyList(), emptyList(), baseVol = 0.01)
        assertEquals(0.5, confidence, 1e-12)
    }

    @Test
    fun `uq-lob confidence falls on a wide washed-out spread`() {
        // Same imbalance on both books; only spread quality differs.
        val tightBids = (1..20).map { DepthTier(100.0 - it * 0.01, 10.0) }
        val tightAsks = (1..20).map { DepthTier(100.0 + it * 0.01, 10.0) }
        val wideBids = (1..20).map { DepthTier(100.0 - it * 1.0, 10.0) }
        val wideAsks = (1..20).map { DepthTier(100.0 + it * 1.0, 10.0) }

        val tight = QuantMath.computeUQLOBUncertainty(tightBids, tightAsks, baseVol = 0.01)
        val wide = QuantMath.computeUQLOBUncertainty(wideBids, wideAsks, baseVol = 0.01)

        assertTrue("Tight spread must score higher than wide spread", tight > wide)
        assertTrue(wide in 0.10..1.0)
        assertTrue(tight in 0.10..1.0)
    }

    // ---- Markov-GARCH regime --------------------------------------------

    @Test
    fun `flat return tape stays in mean reversion`() {
        val returns = List(15) { 0.0 }
        val result = QuantMath.evaluateMarkovRegimeGARCH(
            returns = returns,
            kurtosisHistory = mutableListOf(),
            state1Returns = mutableListOf(),
            state0Returns = mutableListOf()
        )
        assertTrue(result.regime.startsWith("State 0"))
        // >=15 returns enters the full evaluation path: State 0 vol scale 0.85.
        assertEquals(0.85, result.regimeVolScale, 1e-12)
        // All-zero tape: revert drift is -mean * 0.5 = 0.
        assertEquals(0.0, result.muKinematic, 1e-12)
    }

    @Test
    fun `insufficient history stays in mean reversion with zero drift`() {
        val result = QuantMath.evaluateMarkovRegimeGARCH(
            returns = List(5) { 0.01 },
            kurtosisHistory = mutableListOf(),
            state1Returns = mutableListOf(),
            state0Returns = mutableListOf()
        )
        assertTrue(result.regime.startsWith("State 0"))
        assertEquals(0.0, result.muKinematic, 1e-12)
    }

    @Test
    fun `fat tailed tape escalates to momentum burst`() {
        // Mostly flat tape with a handful of large outliers -> high kurtosis.
        val returns = List(16) { 0.0 } + List(4) { 0.5 }
        val result = QuantMath.evaluateMarkovRegimeGARCH(
            returns = returns,
            kurtosisHistory = mutableListOf(),
            state1Returns = mutableListOf(),
            state0Returns = mutableListOf()
        )
        assertTrue(
            "Expected momentum burst on fat tails (kurtosis=${result.kurtosis}, cutoff=${result.cutoff})",
            result.regime.startsWith("State 1")
        )
        assertTrue(result.kurtosis > 3.0)
        assertTrue(result.regimeVolScale > 1.0)
    }

    // ---- Temporal attention ----------------------------------------------

    @Test
    fun `temporal attention needs 30 candles and repeats last moves otherwise`() {
        val short = QuantMath.evaluateTemporalAttention(syntheticKlines(10, 100.0, 0.001), baseVol = 0.01)
        assertEquals(0.0, short.muKinematic, 1e-12)

        val long = QuantMath.evaluateTemporalAttention(syntheticKlines(30, 100.0, 0.001), baseVol = 0.01)
        assertTrue("Uptrend must give positive kinematic drift, got ${long.muKinematic}", long.muKinematic > 0.0)
        assertTrue(long.upperConeSkew > 1.0)
    }

    // ---- Confluence alpha -------------------------------------------------

    @Test
    fun `confluence alpha fuses vwap poc and lob confidence`() {
        val klines = syntheticKlines(40, 100.0, 0.0005)
        val depth = DepthSnapshot(
            bids = (1..20).map { DepthTier(100.0 - it * 0.05, 8.0) },
            asks = (1..20).map { DepthTier(100.0 + it * 0.05, 8.0) }
        )

        val result = QuantMath.computeConfluenceAlpha(klines, 100.0, depth, baseVol = 0.01, isMomentumBurst = true)
        assertTrue(result.vwap > 0.0)
        assertTrue(result.poc > 0.0)
        assertTrue(result.confidence in 0.10..1.0)
        assertNotNull(result.muAlpha)
    }

    @Test
    fun `confluence alpha without depth falls back to baseline confidence`() {
        val klines = syntheticKlines(40, 100.0, 0.0005)
        val result = QuantMath.computeConfluenceAlpha(klines, 100.0, depth = null, baseVol = 0.01, isMomentumBurst = true)
        assertEquals(0.70, result.confidence, 1e-12)
    }

    // ---- Ensemble weight calibration --------------------------------------

    @Test
    fun `inverse variance calibration normalizes weights to one`() {
        val modelState = ModelState()
        val klines = syntheticKlines(60, 100.0, 0.0008)

        QuantMath.calibrateInitialWeightsFromHistory(
            klines = klines,
            baseVol = 0.01,
            ltfMuPath = 0.0002,
            modelState = modelState
        )

        assertEquals("RMSE Calibrated", modelState.mode)
        val total = modelState.weightK + modelState.weightP + modelState.weightAlpha
        assertEquals("Weights must sum to 1.0", 1.0, total, 0.01)
        assertTrue(modelState.weightK > 0.0 && modelState.weightP > 0.0 && modelState.weightAlpha > 0.0)
    }

    @Test
    fun `calibration is a no-op on short history`() {
        val modelState = ModelState()
        QuantMath.calibrateInitialWeightsFromHistory(
            klines = syntheticKlines(10, 100.0, 0.0),
            baseVol = 0.01,
            ltfMuPath = 0.0002,
            modelState = modelState
        )
        assertEquals("Baseline Init", modelState.mode)
        assertEquals(0.30, modelState.weightK, 1e-12)
        assertEquals(0.35, modelState.weightP, 1e-12)
        assertEquals(0.35, modelState.weightAlpha, 1e-12)
    }

    // ---- Classic indicators -------------------------------------------------

    @Test
    fun `rsi stays within zero to one hundred`() {
        val closes = listOf(
            10.0, 11.0, 12.0, 11.5, 13.0, 14.0, 13.5, 15.0, 16.0, 15.5,
            17.0, 18.0, 17.5, 19.0, 20.0, 21.0
        )
        val rsi = QuantMath.computeRSI(closes)
        assertTrue("RSI should be between 0 and 100", rsi in 0.0..100.0)
        assertTrue("Monotone uptrend needs RSI above the neutral zone", rsi > 50.0)
    }

    @Test
    fun `ema tracks window size and moves toward trend`() {
        val closes = List(20) { 100.0 + it }
        val ema = QuantMath.computeEMA(closes, period = 9)
        assertEquals(closes.size, ema.size)
        assertTrue("EMA on an uptrend must rise", ema.last() > ema.first())
    }
}
