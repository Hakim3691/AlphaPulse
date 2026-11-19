package com.aistudio.alphapulse.domain.strategy

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Shared vocabulary for the §3 auxiliary confluence screeners and quantitative models.
 *
 * Why this exists
 * ---------------
 * The directive's §3 library has two halves: the pattern taxonomy (implemented in
 * `domain/pattern`) and the *auxiliary confluence screeners & quant models* — SMA/MA/MACD/
 * RSI/KDJ/Bollinger/volume screeners plus the nine strategy models (Turtle, Dual Thrust,
 * ORB, SuperTrend, Donchian, CRSI, VWAP reversion, CMF, Triple Screen). Those models were
 * named in the specification but never executed, so "confluence" was prose and a strategy
 * could not be audited or scored.
 *
 * Every model below is a pure function of a candle series: no clock, no network, no
 * randomness, so a signal can be reproduced from the same bars months later.
 *
 * Spec Ref: Directive §3 Reference Taxonomy Library (Auxiliary Confluence Screeners &
 * Quant Models)
 */

/** Direction a screener or strategy currently implies. */
enum class SignalDirection { BULLISH, BEARISH, NEUTRAL }

/** One OHLCV bar, independent of the CSV/network layers. */
data class StrategyCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

/** A technical screener verdict (part of §3's "Technical Screeners" list). */
data class ScreenerSignal(
    /** Stable key, e.g. `sma-5-20-cross`. */
    val id: String,
    val name: String,
    val direction: SignalDirection,
    /** The measured values behind the verdict, for the audit trail. */
    val detail: String
)

/**
 * A quantitative strategy verdict with the levels its own rule defines.
 *
 * Levels are null when the model is not currently actionable — a neutral signal has no
 * entry to publish, and inventing one would make the audit claim a trade that never
 * existed.
 */
data class StrategySignal(
    /** Stable key, e.g. `turtle-breakout`. */
    val id: String,
    val name: String,
    val direction: SignalDirection,
    /** Price at which the rule triggers (breakout/band level). */
    val entry: Double?,
    /** Price at which the rule closes the position / takes profit. */
    val exit: Double?,
    /** Price whose breach voids the setup (the rule's stop). */
    val invalidation: Double?,
    /** The rule text and measured values, recorded verbatim. */
    val detail: String,
    /** §3 zone metadata: distance from last price to the zone centre, in ATR units. */
    val distanceAtr: Double? = null,
    /** §3 zone metadata: how many times the shelf has already been tested. */
    val zoneTouches: Int = 0
) {
    val isActive: Boolean get() = direction != SignalDirection.NEUTRAL
}

/**
 * The small amount of arithmetic the models share.
 *
 * Kept `internal` and dependency-free: the strategy layer must stay a pure JVM domain
 * package so it can be scored without Android, Room or the network.
 */
internal object SignalMath {

    /** Simple moving average of the newest [period] values, or NaN when too short. */
    fun sma(values: List<Double>, period: Int): Double {
        if (period <= 0 || values.size < period) return Double.NaN
        return values.takeLast(period).average()
    }

    /** Exponential moving average series, seeded with the first value. */
    fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty() || period <= 0) return emptyList()
        val k = 2.0 / (period + 1)
        val out = ArrayList<Double>(values.size)
        var previous = values.first()
        values.forEachIndexed { index, value ->
            previous = if (index == 0) value else value * k + previous * (1 - k)
            out.add(previous)
        }
        return out
    }

    /** Latest exponential moving average, or NaN when the series is empty. */
    fun ema(values: List<Double>, period: Int): Double =
        emaSeries(values, period).lastOrNull() ?: Double.NaN

    /** Wilder's RSI, or NaN when there is not enough history. */
    fun rsi(values: List<Double>, period: Int): Double {
        if (period <= 0 || values.size <= period) return Double.NaN
        var gain = 0.0
        var loss = 0.0
        for (index in 1..period) {
            val delta = values[index] - values[index - 1]
            if (delta >= 0) gain += delta else loss -= delta
        }
        var averageGain = gain / period
        var averageLoss = loss / period
        for (index in period + 1 until values.size) {
            val delta = values[index] - values[index - 1]
            averageGain = (averageGain * (period - 1) + max(delta, 0.0)) / period
            averageLoss = (averageLoss * (period - 1) + max(-delta, 0.0)) / period
        }
        if (averageLoss == 0.0) return 100.0
        val relativeStrength = averageGain / averageLoss
        return 100.0 - 100.0 / (1.0 + relativeStrength)
    }

    /** Population standard deviation. */
    fun stdev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    /** Average true range over the newest [period] bars, or NaN when too short. */
    fun atr(series: List<StrategyCandle>, period: Int): Double {
        if (period <= 0 || series.size < period + 1) return Double.NaN
        val ranges = ArrayList<Double>(series.size)
        for (index in 1 until series.size) {
            val high = series[index].high
            val low = series[index].low
            val previousClose = series[index - 1].close
            ranges.add(max(high - low, max(kotlin.math.abs(high - previousClose), kotlin.math.abs(low - previousClose))))
        }
        return ranges.takeLast(period).average()
    }

    /** Rank (0–100) of the newest value among the last 100 values. */
    fun percentRank(values: List<Double>): Double {
        if (values.size < 2) return 50.0
        val window = values.takeLast(100)
        val last = window.last()
        val below = window.count { it < last }
        return below * 100.0 / window.size
    }

    /** True when [series] is shorter than [required]. */
    fun tooShort(series: List<StrategyCandle>, required: Int): Boolean = series.size < required

    /** Lowest low of [window] ignoring the final bar. */
    fun priorLow(series: List<StrategyCandle>, lookback: Int): Double =
        series.dropLast(1).takeLast(lookback).minOfOrNull { it.low } ?: Double.NaN

    /** Highest high of [window] ignoring the final bar. */
    fun priorHigh(series: List<StrategyCandle>, lookback: Int): Double =
        series.dropLast(1).takeLast(lookback).maxOfOrNull { it.high } ?: Double.NaN

    /** Round to a sane number of decimals for the audit text. */
    fun text(value: Double): String = if (value.isNaN()) "n/a" else String.format(java.util.Locale.US, "%.4f", value)
}
