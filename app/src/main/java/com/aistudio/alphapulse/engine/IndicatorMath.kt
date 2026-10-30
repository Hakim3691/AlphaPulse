package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.CoinCalibration
import com.aistudio.alphapulse.data.remote.BinanceFuturesClient
import kotlin.math.abs

/**
 * Phase 3 (Spec §4, §8, §10, §11, §15): deterministic market analytics computed from
 * real candles. Pure functions only, so engines consume reproducible numbers and unit
 * tests can assert exact behavior without network or Android bindings (Spec §37).
 */
object IndicatorMath {

    // ---------------------------------------------------------------- swings (§8)

    /** A detected swing extreme (fractal pivot) with its index for recency math. */
    data class SwingPoint(
        val price: Double,
        val index: Int,
        val timeUtcMs: Long,
        val isHigh: Boolean
    )

    /**
     * Detects swing highs/lows over an adaptive window: a candle is a pivot when its
     * high (or low) is the strict extreme within [window] candles on both sides.
     * Window scales with timeframe importance (Spec §8 "adaptive windows").
     */
    fun swingPoints(candles: List<BinanceFuturesClient.Candle>, window: Int = 3): List<SwingPoint> {
        if (candles.size < window * 2 + 1) return emptyList()
        val out = mutableListOf<SwingPoint>()
        for (i in window until candles.size - window) {
            var isHigh = true
            var isLow = true
            for (j in (i - window) until i) {
                if (candles[j].high >= candles[i].high) isHigh = false
                if (candles[j].low <= candles[i].low) isLow = false
            }
            for (j in (i + 1)..(i + window)) {
                if (candles[j].high > candles[i].high) isHigh = false
                if (candles[j].low < candles[i].low) isLow = false
            }
            if (isHigh) out.add(SwingPoint(candles[i].high, i, candles[i].openTimeUtcMs, isHigh = true))
            if (isLow) out.add(SwingPoint(candles[i].low, i, candles[i].openTimeUtcMs, isHigh = false))
        }
        return out
    }

    // ---------------------------------------------------------------- bias (§9, §14, §15)

    /**
     * Structural + momentum multi-horizon bias score (-100..+100) for one timeframe.
     * Combines (a) momentum-z of the last close vs the window mean normalized by the
     * realized range, and (b) swing structure: net count of higher-highs/higher-lows
     * vs lower-highs/lower-lows across detected pivots. Deterministic given inputs.
     */
    fun structuralBias(candles: List<BinanceFuturesClient.Candle>, lookback: Int = 40): Int {
        if (candles.isEmpty()) return 0
        val window = candles.takeLast(lookback.coerceAtMost(candles.size))

        val closes = window.map { it.close }
        val mean = closes.average()
        val last = closes.last()
        val range = (window.maxOf { it.high } - window.minOf { it.low })
            .coerceAtLeast(Double.MIN_VALUE)
        val momentumComponent = ((last - mean) / range * 100.0).coerceIn(-60.0, 60.0)

        val swings = swingPoints(window, window = 2)
        var structureComponent = 0.0
        if (swings.size >= 3) {
            val highs = swings.filter { it.isHigh }
            val lows = swings.filter { !it.isHigh }
            val higherHighs = highs.zipWithNext().count { (a, b) -> b.price > a.price }
            val higherLows = lows.zipWithNext().count { (a, b) -> b.price > a.price }
            val lowerHighs = highs.size - 1 - higherHighs
            val lowerLows = lows.size - 1 - higherLows
            val bullishPivots = higherHighs + higherLows
            val bearishPivots = lowerHighs + lowerLows
            val totalPivots = (bullishPivots + bearishPivots).coerceAtLeast(1)
            structureComponent = (bullishPivots - bearishPivots).toDouble() / totalPivots * 40.0
        }

        return (momentumComponent + structureComponent).toInt().coerceIn(-100, 100)
    }

    /**
     * §15 score-band operational table: bias scores map onto named bands that modify
     * campaign priority and size multiplier. Version-controlled in one place so the
     * weighting is reproducible and backtestable.
     */
    enum class BiasBand(val label: String, val priorityAdj: Double, val sizeAdj: Double) {
        STRONG_BEAR("Strong Bearish Asymmetry", -8.0, -0.20),
        BEAR("Bearish Asymmetry", -4.0, -0.10),
        NEUTRAL("Neutral", 0.0, 0.0),
        BULL("Bullish Asymmetry", +4.0, +0.10),
        STRONG_BULL("Strong Bullish Asymmetry", +8.0, +0.20);

        companion object {
            /** Band lookup for a bias score in -100..+100 (§15 operational table). */
            fun of(score: Int): BiasBand = when {
                score >= 50 -> STRONG_BULL
                score >= 20 -> BULL
                score <= -50 -> STRONG_BEAR
                score <= -20 -> BEAR
                else -> NEUTRAL
            }
        }
    }

    /** Legacy momentum-only bias (kept for the screener's fast path and tests). */
    fun interimBias(candles: List<BinanceFuturesClient.Candle>, lookback: Int = 20): Int {
        if (candles.isEmpty()) return 0
        val window = candles.takeLast(lookback.coerceAtMost(candles.size))
        val closes = window.map { it.close }
        val mean = closes.average()
        val last = closes.last()
        val range = (window.maxOf { it.high } - window.minOf { it.low })
            .coerceAtLeast(Double.MIN_VALUE)
        val momentumZ = (last - mean) / range * 200.0
        return momentumZ.coerceIn(-100.0, 100.0).toInt()
    }

    // ---------------------------------------------------------------- ATR & pain (§4, §11)

    /**
     * Wilder-smoothed ATR over [candles]. Requires at least one candle; the first value
     * seeds with the simple average of true ranges. Returns 0.0 for empty input.
     */
    fun atr(candles: List<BinanceFuturesClient.Candle>, period: Int = 14): Double {
        if (candles.isEmpty()) return 0.0
        val trueRanges = DoubleArray(candles.size)
        trueRanges[0] = candles[0].high - candles[0].low
        for (i in 1 until candles.size) {
            val prevClose = candles[i - 1].close
            trueRanges[i] = maxOf(
                candles[i].high - candles[i].low,
                abs(candles[i].high - prevClose),
                abs(candles[i].low - prevClose)
            )
        }
        if (candles.size == 1) return trueRanges[0]
        val effectivePeriod = period.coerceIn(1, candles.size)
        var atr = trueRanges.take(effectivePeriod).average()
        for (i in effectivePeriod until candles.size) {
            atr = (atr * (effectivePeriod - 1) + trueRanges[i]) / effectivePeriod
        }
        return atr
    }

    /** pain score proxy (§11): crowding weight on funding extremity plus momentum alignment. */
    fun painScore(fundingRate: Double, bias: Int): Int {
        val crowding = (abs(fundingRate) * 20_000).coerceIn(0.0, 100.0)
        val alignment = abs(bias).toDouble()
        return (0.6 * crowding + 0.4 * alignment).toInt().coerceIn(10, 98)
    }

    // ---------------------------------------------------------------- calibration (§10)

    /**
     * §10 Coin-Specific Calibration computed from real 1h (behavior) and 5m (execution)
     * candle history. Measures side-specific asymmetries so boards are placed with
     * realistic expectations. Crowding elasticity is proxied from post-impulse range
     * expansion (funding/OI extremes amplify follow-through) — the honest candle-only
     * approximation until a funding history series is persisted.
     */
    fun computeCalibration(
        symbol: String,
        candles1h: List<BinanceFuturesClient.Candle>,
        candles5m: List<BinanceFuturesClient.Candle>,
        atr5m: Double,
        spreadPct: Double
    ): CoinCalibration {
        // Impulse asymmetry: volume-weighted average up-candle velocity vs down-candle.
        var upVelNum = 0.0
        var upVelDen = 0.0
        var downVelNum = 0.0
        var downVelDen = 0.0
        for (c in candles1h) {
            val body = c.close - c.open
            if (body > 0) {
                upVelNum += body * c.volume
                upVelDen += c.volume
            } else if (body < 0) {
                downVelNum += (-body) * c.volume
                downVelDen += c.volume
            }
        }
        val upVel = if (upVelDen > 0) upVelNum / upVelDen else 0.0
        val downVel = if (downVelDen > 0) downVelNum / downVelDen else 0.0
        val impulseAsymmetry = when {
            upVel <= 0.0 && downVel <= 0.0 -> 1.0
            downVel <= 0.0 -> 5.0
            else -> (upVel / downVel).coerceIn(0.2, 5.0)
        }

        // Wick asymmetry: how often upside probes vs downside probes reject immediately,
        // measured as relative wick size on impulsive candles.
        var upWickSum = 0.0
        var upWickN = 0
        var downWickSum = 0.0
        var downWickN = 0
        for (c in candles1h) {
            val height = (c.high - c.low).coerceAtLeast(1e-12)
            val body = c.close - c.open
            if (abs(body) > height * 0.4) {
                if (body > 0) {
                    upWickSum += (c.high - maxOf(c.open, c.close)) / height
                    upWickN++
                } else {
                    downWickSum += (minOf(c.open, c.close) - c.low) / height
                    downWickN++
                }
            }
        }
        val upWick = if (upWickN >= 3) upWickSum / upWickN else 0.25
        val downWick = if (downWickN >= 3) downWickSum / downWickN else 0.25
        val wickAsymmetry = when {
            downWick <= 1e-9 -> 5.0
            else -> (upWick / downWick).coerceIn(0.2, 5.0)
        }

        // Follow-through asymmetry: P(continuation | strong impulse) up vs down.
        var upCont = 0
        var upTotal = 0
        var downCont = 0
        var downTotal = 0
        for (i in 1 until candles1h.size) {
            val prev = candles1h[i - 1]
            val body = prev.close - prev.open
            val height = (prev.high - prev.low).coerceAtLeast(1e-12)
            if (abs(body) < height * 0.5) continue
            val nextBody = candles1h[i].close - candles1h[i].open
            if (body > 0) {
                upTotal++
                if (nextBody > 0) upCont++
            } else {
                downTotal++
                if (nextBody < 0) downCont++
            }
        }
        val upRate = if (upTotal >= 3) upCont.toDouble() / upTotal else 0.5
        val downRate = if (downTotal >= 3) downCont.toDouble() / downTotal else 0.5
        val followThroughAsymmetry = when {
            downRate <= 1e-9 -> 5.0
            else -> (upRate / downRate).coerceIn(0.2, 5.0)
        }

        // Crowding elasticity proxy: range expansion in the 3 candles after a >=2-sigma
        // impulse relative to the baseline range (chasing amplifies moves).
        val baseline = candles1h.map { it.high - it.low }.averageOrNull() ?: 0.0
        val avgRange = if (baseline > 0) baseline else 1.0
        val meanClose = candles1h.map { it.close }.averageOrNull() ?: 0.0
        val sd = candles1h.map { abs(it.close - meanClose) }.averageOrNull() ?: 0.0
        val postImpulseRanges = mutableListOf<Double>()
        for (i in 0 until (candles1h.size - 3).coerceAtLeast(0)) {
            val move = abs(candles1h[i].close - candles1h[i].open)
            if (sd > 0 && move > 2.0 * sd) {
                for (j in (i + 1)..(i + 3)) {
                    postImpulseRanges.add(candles1h[j].high - candles1h[j].low)
                }
            }
        }
        val expansion = postImpulseRanges.averageOrNull()
        val crowdingElasticity = if (expansion != null && expansion > 0) {
            (expansion / avgRange).coerceIn(0.2, 5.0)
        } else 1.0

        // Mean-reversion half-life: candles until a >=1.5-sigma stretch retraces 50%.
        val retraceCandles = halfLifeCandles(candles5m)
        val meanReversionHalfLifeMinutes = retraceCandles * 5

        // Execution profile: slippage proxy from 5m ATR in bps plus the real spread.
        val atrBps = if (candles5m.isNotEmpty() && candles5m.last().close > 0) {
            atr5m / candles5m.last().close * 10_000.0
        } else 5.0
        val typicalSlippageBps = (atrBps * 0.3 + spreadPct * 100.0).coerceIn(0.5, 50.0)

        return CoinCalibration(
            symbol = symbol,
            impulseAsymmetry = impulseAsymmetry,
            wickAsymmetry = wickAsymmetry,
            followThroughAsymmetry = followThroughAsymmetry,
            crowdingElasticity = crowdingElasticity,
            meanReversionHalfLifeMinutes = meanReversionHalfLifeMinutes.coerceIn(5, 240),
            typicalSlippageBps = typicalSlippageBps
        )
    }

    /** Candles until the close retraces >=50% of the most recent directional stretch. */
    private fun halfLifeCandles(candles: List<BinanceFuturesClient.Candle>): Int {
        if (candles.size < 10) return 12
        val windows = mutableListOf<Int>()
        var i = 0
        while (i < candles.size - 2) {
            val move = candles[i + 1].close - candles[i].close
            val stretch = abs(move)
            val sigma = candles.take(maxOf(10, i + 1)).map { abs(it.close - it.open) }.averageOrNull() ?: 0.0
            if (sigma > 0 && stretch > 1.5 * sigma) {
                val anchor = candles[i].close
                var retraceIdx = -1
                for (j in (i + 2) until candles.size) {
                    val retrace = if (move > 0) anchor - candles[j].close else candles[j].close - anchor
                    if (retrace >= stretch * 0.5) {
                        retraceIdx = j - (i + 1)
                        break
                    }
                }
                windows.add(if (retraceIdx > 0) retraceIdx else 24)
                i += (retraceIdx + 2).coerceAtLeast(2)
            } else {
                i++
            }
        }
        return if (windows.isEmpty()) 12 else windows.average().toInt().coerceIn(1, 48)
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
