package com.aistudio.alphapulse.domain.pattern

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Deterministic candlestick geometry → [PatternMatch].
 *
 * Why this exists
 * ---------------
 * The SGD ledger grades predictions against "the deterministic geometry", so the thing
 * that produces the prediction and the thing that produces the ground truth have to be
 * the same deterministic classifier. A model that guessed a pattern could never be
 * scored: there would be no independent truth to compare it with. Every rule below is
 * therefore a pure function of open/high/low/close — no randomness, no clock, no network.
 *
 * The classifier is horizon-scoped: it only emits ids bound to the requested
 * [PatternHorizon], so an intraday wick can never be reported as an hourly structure.
 *
 * Spec Ref: Directive §2 Validity Framework, §3 Taxonomy Library, §4 Ground Truth
 */

/** One OHLCV bar. */
data class PatternCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
) {
    val body: Double get() = abs(close - open)
    val range: Double get() = max(high - low, EPSILON)
    val isBullish: Boolean get() = close >= open
    val upperWick: Double get() = max(0.0, high - max(open, close))
    val lowerWick: Double get() = max(0.0, min(open, close) - low)
    val midpoint: Double get() = (open + close) / 2.0

    private companion object {
        const val EPSILON = 1e-9
    }
}

/** A formation the classifier recognised, with its normalized confidence. */
data class PatternMatch(
    val id: String,
    val name: String,
    val category: PatternCategory,
    /** Probability in [MIN_CONFIDENCE, 0.99]. */
    val confidence: Double
)

object PatternClassifier {

    /** Below this the geometry is not a formation; the ledger issues nothing rather than guess. */
    const val MIN_CONFIDENCE = 0.50

    /**
     * Every formation visible in [series] for [horizon], strongest first.
     *
     * Empty means "no structure present". Callers must treat that as no prediction, not
     * as a neutral signal.
     */
    fun classify(series: List<PatternCandle>, horizon: PatternHorizon): List<PatternMatch> {
        if (series.size < 2) return emptyList()

        val found = LinkedHashMap<String, Double>()
        fun offer(id: String, confidence: Double) {
            if (id !in horizon.targetClasses) return
            if (confidence < MIN_CONFIDENCE) return
            val clamped = round(min(0.99, max(MIN_CONFIDENCE, confidence)) * 100.0) / 100.0
            val previous = found[id]
            if (previous == null || clamped > previous) found[id] = clamped
        }

        detectSingleBar(series, ::offer)
        detectTwoBar(series, ::offer)
        detectThreeBar(series, ::offer)
        detectThreeMethods(series, ::offer)
        detectFlag(series, ::offer)
        if (series.size >= 10) {
            detectStructure(series.takeLast(14), ::offer)
        }

        return found.map { (id, confidence) ->
            val definition = PatternTaxonomy.byId.getValue(id)
            PatternMatch(id, definition.name, definition.category, confidence)
        }.sortedWith(compareByDescending<PatternMatch> { it.confidence }.thenBy { it.id })
    }

    /**
     * Turn recognised formations into the feature vector the SGD model consumes.
     *
     * Aligned to [classes] and normalized to sum to 1, so a cross-entropy loss over the
     * model's own distribution is comparable across horizons. A horizon with no evidence
     * yields the uniform vector rather than a zero vector (which would make every logit
     * identical by accident).
     */
    fun evidenceVector(matches: List<PatternMatch>, classes: List<String>): DoubleArray {
        val index = HashMap<String, Int>(classes.size)
        classes.forEachIndexed { position, id -> index[id] = position }
        val vector = DoubleArray(classes.size)
        matches.forEach { match ->
            val position = index[match.id] ?: return@forEach
            vector[position] += match.confidence
        }
        val total = vector.sum()
        if (total <= 0.0) return DoubleArray(classes.size) { 1.0 / classes.size }
        return DoubleArray(classes.size) { vector[it] / total }
    }

    // ----------------------------------------------------------- single bar

    private fun detectSingleBar(series: List<PatternCandle>, offer: (String, Double) -> Unit) {
        val bar = series.last()
        // A doji has no body to sit on top of a wick, so it is not a wick reversal.
        if (bar.body < bar.range * 0.12) return

        val lowerRatio = bar.lowerWick / max(bar.body, 1e-9)
        val upperRatio = bar.upperWick / max(bar.body, 1e-9)
        val fallingContext = isFalling(series)
        val risingContext = isRising(series)

        if (lowerRatio >= 2.0 && upperRatio <= 0.6) {
            val strength = 0.55 + min(0.35, (lowerRatio - 2.0) * 0.10)
            if (fallingContext) offer("hammer", strength)
            if (risingContext) offer("hanging-man", strength)
        }
        if (upperRatio >= 2.0 && lowerRatio <= 0.6) {
            val strength = 0.55 + min(0.35, (upperRatio - 2.0) * 0.10)
            if (risingContext) offer("shooting-star", strength)
        }
    }

    // ------------------------------------------------------------ two bars

    private fun detectTwoBar(series: List<PatternCandle>, offer: (String, Double) -> Unit) {
        if (series.size < 3) return
        val previous = series[series.size - 2]
        val current = series.last()
        if (previous.body < previous.range * 0.10 || current.body < current.range * 0.10) return

        val engulfsUp = current.open <= previous.close &&
            current.close >= previous.open &&
            current.body >= previous.body * 0.9
        val engulfsDown = current.open >= previous.close &&
            current.close <= previous.open &&
            current.body >= previous.body * 0.9

        if (!previous.isBullish && current.isBullish && engulfsUp) {
            offer("bullish-engulfing", 0.60 + min(0.30, (current.body / previous.body - 1.0) * 0.15))
        }
        if (previous.isBullish && !current.isBullish && engulfsDown) {
            offer("bearish-engulfing", 0.60 + min(0.30, (current.body / previous.body - 1.0) * 0.15))
        }
        if (!previous.isBullish && current.isBullish &&
            current.open < previous.close &&
            current.close > previous.midpoint &&
            current.close < previous.open
        ) {
            offer("piercing-line", 0.55 + min(0.30, (current.close - previous.midpoint) / previous.body))
        }
        if (previous.isBullish && !current.isBullish &&
            current.open > previous.close &&
            current.close < previous.midpoint &&
            current.close > previous.open
        ) {
            offer("dark-cloud-cover", 0.55 + min(0.30, (previous.midpoint - current.close) / previous.body))
        }
    }

    // ---------------------------------------------------------- three bars

    private fun detectThreeBar(series: List<PatternCandle>, offer: (String, Double) -> Unit) {
        if (series.size < 4) return
        val first = series[series.size - 3]
        val second = series[series.size - 2]
        val third = series.last()

        val smallSecond = second.body <= first.body * 0.5
        if (!first.isBullish && first.body >= first.range * 0.30 && smallSecond &&
            third.isBullish && third.close > first.midpoint
        ) {
            offer("morning-star", 0.60 + min(0.30, (third.close - first.midpoint) / first.body))
        }
        if (first.isBullish && first.body >= first.range * 0.30 && smallSecond &&
            !third.isBullish && third.close < first.midpoint
        ) {
            offer("evening-star", 0.60 + min(0.30, (first.midpoint - third.close) / first.body))
        }

        val secondEngulfsUp = second.isBullish && second.open <= first.close &&
            second.close >= first.open && second.body >= first.body * 0.9
        val secondEngulfsDown = !second.isBullish && second.open >= first.close &&
            second.close <= first.open && second.body >= first.body * 0.9
        if (!first.isBullish && secondEngulfsUp && third.isBullish && third.close > second.close) {
            offer("three-outside-up", 0.62 + min(0.28, (second.body / first.body - 1.0) * 0.15))
        }
        if (first.isBullish && secondEngulfsDown && !third.isBullish && third.close < second.close) {
            offer("three-outside-down", 0.62 + min(0.28, (second.body / first.body - 1.0) * 0.15))
        }

        val soldiers = first.isBullish && second.isBullish && third.isBullish &&
            second.close > first.close && third.close > second.close &&
            second.open >= first.open && third.open >= second.open &&
            third.body >= third.range * 0.5
        if (soldiers) offer("three-white-soldiers", 0.60 + min(0.30, (third.close - first.open) / first.open * 12.0))

        val crows = !first.isBullish && !second.isBullish && !third.isBullish &&
            second.close < first.close && third.close < second.close &&
            second.open <= first.open && third.open <= second.open &&
            third.body >= third.range * 0.5
        if (crows) offer("three-black-crows", 0.60 + min(0.30, (first.open - third.close) / first.open * 12.0))
    }

    // ------------------------------------------------------- three methods

    private fun detectThreeMethods(series: List<PatternCandle>, offer: (String, Double) -> Unit) {
        if (series.size < 5) return
        val first = series[series.size - 5]
        val last = series.last()
        val inner = series.subList(series.size - 4, series.size - 1)
        if (first.body < first.range * 0.40) return

        val contained = inner.all { it.high <= first.high && it.low >= first.low }
        if (!contained) return

        if (first.isBullish && inner.all { !it.isBullish } && last.isBullish && last.close > first.close) {
            offer("rising-three-methods", 0.60 + min(0.25, (last.close - first.close) / first.body))
        }
        if (!first.isBullish && inner.all { it.isBullish } && !last.isBullish && last.close < first.close) {
            offer("falling-three-methods", 0.60 + min(0.25, (first.close - last.close) / first.body))
        }
    }

    // --------------------------------------------------------------- flags

    private fun detectFlag(series: List<PatternCandle>, offer: (String, Double) -> Unit) {
        if (series.size < 6) return
        val poleBars = series.subList(series.size - 6, series.size - 3)
        val flagBars = series.subList(series.size - 3, series.size)

        val poleStart = poleBars.first().open
        val poleEnd = poleBars.last().close
        val poleMove = poleEnd - poleStart
        val polePct = abs(poleMove) / max(poleStart, 1e-9)
        val poleBody = poleBars.map { it.body }.average()
        val flagBody = flagBars.map { it.body }.average()

        // A flag is a pole followed by a contraction; without both it is just a drift.
        if (polePct < 0.004 || poleBody <= flagBody * 1.2) return

        if (poleMove > 0.0) {
            val retrace = (poleEnd - flagBars.minOf { it.low }) / poleMove
            if (retrace <= 0.5) offer("rising-flag", 0.55 + min(0.35, (0.5 - retrace) * 0.6))
        } else if (poleMove < 0.0) {
            val retrace = (flagBars.maxOf { it.high } - poleEnd) / abs(poleMove)
            if (retrace <= 0.5) offer("falling-flag", 0.55 + min(0.35, (0.5 - retrace) * 0.6))
        }
    }

    // ------------------------------------------------- multi-bar structures

    private fun detectStructure(window: List<PatternCandle>, offer: (String, Double) -> Unit) {
        detectDoubleExtremes(window, offer)
        detectTripleExtremes(window, offer)
        detectHeadAndShoulders(window, offer)
        detectWedges(window, offer)
        detectTriangles(window, offer)
    }

    private fun detectDoubleExtremes(window: List<PatternCandle>, offer: (String, Double) -> Unit) {
        val half = window.size / 2
        if (half < 3) return

        val lowA = (0 until half).minByOrNull { window[it].low } ?: return
        val lowB = (half until window.size).minByOrNull { window[it].low } ?: return
        val trough = max(window[lowA].low, window[lowB].low)
        if (abs(window[lowA].low - window[lowB].low) <= trough * 0.008 && lowB > lowA + 1) {
            val neckline = window.subList(lowA, lowB + 1).maxOf { it.high }
            val close = window.last().close
            if (neckline >= trough * 1.008 && close >= neckline) {
                offer("double-bottom", 0.60 + min(0.30, (close / neckline - 1.0) * 10.0))
            }
        }

        val highA = (0 until half).maxByOrNull { window[it].high } ?: return
        val highB = (half until window.size).maxByOrNull { window[it].high } ?: return
        val peak = min(window[highA].high, window[highB].high)
        if (abs(window[highA].high - window[highB].high) <= peak * 0.008 && highB > highA + 1) {
            val neckline = window.subList(highA, highB + 1).minOf { it.low }
            val close = window.last().close
            if (neckline <= peak * 0.992 && close <= neckline) {
                offer("double-top", 0.60 + min(0.30, (1.0 - close / neckline) * 10.0))
            }
        }
    }

    private fun detectTripleExtremes(window: List<PatternCandle>, offer: (String, Double) -> Unit) {
        if (window.size < 12) return
        val third = window.size / 3
        if (third < 3) return

        val lows = listOf(
            (0 until third).minByOrNull { window[it].low } ?: return,
            (third until third * 2).minByOrNull { window[it].low } ?: return,
            (third * 2 until window.size).minByOrNull { window[it].low } ?: return
        )
        val trough = lows.maxOf { window[it].low }
        if (lows.all { abs(window[it].low - trough) <= trough * 0.008 }) {
            val neckline = window.subList(lows.first(), lows.last() + 1).maxOf { it.high }
            if (window.last().close >= neckline && neckline >= trough * 1.008) {
                offer("triple-bottom", 0.65 + min(0.25, (window.last().close / neckline - 1.0) * 10.0))
            }
        }

        val highs = listOf(
            (0 until third).maxByOrNull { window[it].high } ?: return,
            (third until third * 2).maxByOrNull { window[it].high } ?: return,
            (third * 2 until window.size).maxByOrNull { window[it].high } ?: return
        )
        val peak = highs.minOf { window[it].high }
        if (highs.all { abs(window[it].high - peak) <= peak * 0.008 }) {
            val neckline = window.subList(highs.first(), highs.last() + 1).minOf { it.low }
            if (window.last().close <= neckline && neckline <= peak * 0.992) {
                offer("triple-top", 0.65 + min(0.25, (1.0 - window.last().close / neckline) * 10.0))
            }
        }
    }

    private fun detectHeadAndShoulders(window: List<PatternCandle>, offer: (String, Double) -> Unit) {
        if (window.size < 12) return
        val third = window.size / 3
        if (third < 3) return

        val left = (0 until third).maxByOrNull { window[it].high } ?: return
        val head = (third until third * 2).maxByOrNull { window[it].high } ?: return
        val right = (third * 2 until window.size).maxByOrNull { window[it].high } ?: return

        val headHigh = window[head].high
        val shoulders = max(window[left].high, window[right].high)
        if (headHigh > shoulders * 1.004 &&
            abs(window[left].high - window[right].high) <= headHigh * 0.01
        ) {
            val neckline = window.subList(left, right + 1).minOf { it.low }
            if (window.last().close <= neckline) {
                offer("head-and-shoulders-top", 0.68 + min(0.22, (1.0 - window.last().close / neckline) * 10.0))
            }
        }

        val leftLow = (0 until third).minByOrNull { window[it].low } ?: return
        val headLow = (third until third * 2).minByOrNull { window[it].low } ?: return
        val rightLow = (third * 2 until window.size).minByOrNull { window[it].low } ?: return

        val headLowPrice = window[headLow].low
        val shoulderLows = min(window[leftLow].low, window[rightLow].low)
        if (headLowPrice < shoulderLows * 0.996 &&
            abs(window[leftLow].low - window[rightLow].low) <= shoulderLows * 0.01
        ) {
            val neckline = window.subList(leftLow, rightLow + 1).maxOf { it.high }
            if (window.last().close >= neckline) {
                offer(
                    "reverse-head-and-shoulders",
                    0.68 + min(0.22, (window.last().close / neckline - 1.0) * 10.0)
                )
            }
        }
    }

    private fun detectWedges(window: List<PatternCandle>, offer: (String, Double) -> Unit) {
        val highSlope = slopePercent(window.map { it.high })
        val lowSlope = slopePercent(window.map { it.low })
        val narrowing = window.last().range < window.first().range * 0.9

        if (highSlope > 0.01 && lowSlope > 0.01 && lowSlope > highSlope && narrowing) {
            offer("rising-wedge", 0.58 + min(0.30, (lowSlope - highSlope) * 4.0))
        }
        if (highSlope < -0.01 && lowSlope < -0.01 && highSlope > lowSlope && narrowing) {
            offer("falling-wedge", 0.58 + min(0.30, (highSlope - lowSlope) * 4.0))
        }
    }

    private fun detectTriangles(window: List<PatternCandle>, offer: (String, Double) -> Unit) {
        val resistance = window.maxOf { it.high }
        val support = window.minOf { it.low }
        val highSlope = slopePercent(window.map { it.high })
        val lowSlope = slopePercent(window.map { it.low })

        val resistanceTouches = window.count { abs(it.high - resistance) <= resistance * 0.003 }
        val supportTouches = window.count { abs(it.low - support) <= support * 0.003 }

        if (resistanceTouches >= 3 && abs(highSlope) < 0.02 && lowSlope > 0.02) {
            offer("ascending-triangle", 0.60 + min(0.30, lowSlope))
        }
        if (supportTouches >= 3 && abs(lowSlope) < 0.02 && highSlope < -0.02) {
            offer("descending-triangle", 0.60 + min(0.30, -highSlope))
        }
    }

    // -------------------------------------------------------------- helpers

    private fun isFalling(series: List<PatternCandle>): Boolean {
        val window = series.takeLast(4)
        return window.size >= 3 && window.last().close < window.first().close
    }

    private fun isRising(series: List<PatternCandle>): Boolean {
        val window = series.takeLast(4)
        return window.size >= 3 && window.last().close > window.first().close
    }

    /** Least-squares slope as a percentage of the mean level, per bar. */
    private fun slopePercent(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val meanX = (values.size - 1) / 2.0
        val meanY = values.average()
        if (meanY == 0.0) return 0.0
        var numerator = 0.0
        var denominator = 0.0
        values.forEachIndexed { index, value ->
            numerator += (index - meanX) * (value - meanY)
            denominator += (index - meanX) * (index - meanX)
        }
        if (denominator == 0.0) return 0.0
        return (numerator / denominator) / meanY * 100.0
    }
}
