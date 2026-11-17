package com.aistudio.alphapulse.domain.strategy

import kotlin.math.abs
import kotlin.math.max

/**
 * The nine quantitative strategy models named in §3's "Quantitative Strategy Execution".
 *
 * Why this exists
 * ---------------
 * §3 lists the models but the earlier implementation stopped at the pattern taxonomy, so
 * "Turtle Breakout (20D High / 10D Low Exit)" was a caption with no executable rule. This
 * engine turns each named model into a pure, testable rule that produces a direction plus
 * the entry, exit and invalidation levels *that model itself defines* — which is what makes
 * the library auditable and what §2's "confluent triggers" columns actually refer to.
 *
 * Parameters are stated explicitly per model. Where §3 gives numbers (Turtle 20/10) they are
 * used verbatim; where it names a model without numbers the model's canonical definition is
 * used and cited, and it is a constant — not a user knob — because §3 asks for a fixed
 * reference library.
 *
 * Spec Ref: Directive §3 Quantitative Strategy Execution
 */
object QuantStrategyEngine {

    /** §3: "Turtle Breakout (20D High / 10D Low Exit)". */
    const val TURTLE_ENTRY_LOOKBACK = 20
    const val TURTLE_EXIT_LOOKBACK = 10

    /** Dual Thrust: 5-bar range, k1 = k2 = 0.5 (the model's canonical form). */
    const val DUAL_THRUST_WINDOW = 5
    const val DUAL_THRUST_K = 0.5

    /** Opening Range Breakout: the first 15 bars form the opening range. */
    const val ORB_BARS = 15

    /** Crypto sessions anchor at UTC midnight, which is what "the open" means for ORB. */
    const val SESSION_MS = 86_400_000L

    /** SuperTrend: ATR(10) × 3 (canonical parameters). */
    const val SUPERTREND_PERIOD = 10
    const val SUPERTREND_MULTIPLIER = 3.0

    /** Donchian: 20-bar channel entry, 10-bar mid-channel exit. */
    const val DONCHIAN_ENTRY = 20
    const val DONCHIAN_EXIT = 10

    /** Connors RSI: RSI(3), streak RSI(2), 100-bar percent rank, 10/90 extremes. */
    const val CRSI_RSI_PERIOD = 3
    const val CRSI_STREAK_PERIOD = 2
    const val CRSI_OVERSOLD = 10.0
    const val CRSI_OVERBOUGHT = 90.0

    /** VWAP reversion: 2σ entry band, exit at VWAP, one further σ invalidates. */
    const val VWAP_PERIOD = 20
    const val VWAP_DEVIATIONS = 2.0

    /** Chaikin Money Flow: 20-period, ±0.05 thresholds. */
    const val CMF_PERIOD = 20
    const val CMF_THRESHOLD = 0.05

    /** Triple Screen: EMA(20)/EMA(50) trend, RSI(14) oscillator, prior-bar breakout. */
    const val TRIPLE_TREND_FAST = 20
    const val TRIPLE_TREND_SLOW = 50
    const val TRIPLE_OSCILLATOR = 14

    /** Every model §3 names, in the directive's order. */
    fun evaluateAll(series: List<StrategyCandle>): List<StrategySignal> = listOf(
        turtle(series),
        dualThrust(series),
        openingRangeBreakout(series),
        superTrend(series),
        donchian(series),
        connorsRsi(series),
        vwapReversion(series),
        chaikinMoneyFlow(series),
        tripleScreen(series)
    )

    /** §3 Turtle Breakout: enter on the 20-bar extreme, leave on the 10-bar opposite extreme. */
    fun turtle(series: List<StrategyCandle>): StrategySignal {
        val id = "turtle-breakout"
        val name = "Turtle Breakout"
        if (series.size < TURTLE_ENTRY_LOOKBACK + 1) return neutral(id, name, "Needs ${TURTLE_ENTRY_LOOKBACK + 1} bars")

        val entryHigh = SignalMath.priorHigh(series, TURTLE_ENTRY_LOOKBACK)
        val entryLow = SignalMath.priorLow(series, TURTLE_ENTRY_LOOKBACK)
        val exitLow = SignalMath.priorLow(series, TURTLE_EXIT_LOOKBACK)
        val exitHigh = SignalMath.priorHigh(series, TURTLE_EXIT_LOOKBACK)
        val close = series.last().close

        return when {
            close > entryHigh -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = entryHigh, exit = exitLow, invalidation = exitLow,
                detail = "20-bar high ${SignalMath.text(entryHigh)} taken; exit on the 10-bar low ${SignalMath.text(exitLow)}"
            )
            close < entryLow -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = entryLow, exit = exitHigh, invalidation = exitHigh,
                detail = "20-bar low ${SignalMath.text(entryLow)} taken; exit on the 10-bar high ${SignalMath.text(exitHigh)}"
            )
            else -> StrategySignal(
                id, name, SignalDirection.NEUTRAL,
                entry = null, exit = null, invalidation = null,
                detail = "Inside the 20-bar range ${SignalMath.text(entryLow)} – ${SignalMath.text(entryHigh)}"
            )
        }
    }

    /** §3 Dual Thrust: open ± k × the 5-bar range. */
    fun dualThrust(series: List<StrategyCandle>): StrategySignal {
        val id = "dual-thrust"
        val name = "Dual Thrust"
        if (series.size < DUAL_THRUST_WINDOW + 1) return neutral(id, name, "Needs ${DUAL_THRUST_WINDOW + 1} bars")

        val window = series.dropLast(1).takeLast(DUAL_THRUST_WINDOW)
        val highestHigh = window.maxOf { it.high }
        val lowestLow = window.minOf { it.low }
        val lastClose = window.last().close
        val highestClose = window.maxOf { it.close }
        // Canonical Dual Thrust range: max(HH − LC, HC − LL).
        val range = max(highestHigh - lastClose, highestClose - lowestLow)
        val current = series.last()
        val buyLine = current.open + DUAL_THRUST_K * range
        val sellLine = current.open - DUAL_THRUST_K * range

        return when {
            current.close > buyLine -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = buyLine, exit = sellLine, invalidation = sellLine,
                detail = "Close ${SignalMath.text(current.close)} above buy line ${SignalMath.text(buyLine)} (range ${SignalMath.text(range)})"
            )
            current.close < sellLine -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = sellLine, exit = buyLine, invalidation = buyLine,
                detail = "Close ${SignalMath.text(current.close)} below sell line ${SignalMath.text(sellLine)} (range ${SignalMath.text(range)})"
            )
            else -> StrategySignal(
                id, name, SignalDirection.NEUTRAL, null, null, null,
                "Between the buy ${SignalMath.text(buyLine)} and sell ${SignalMath.text(sellLine)} lines"
            )
        }
    }

    /**
     * §3 Opening Range Breakout: the first [ORB_BARS] bars of the current UTC session form
     * the range; the measured move is the target.
     *
     * Anchoring on the session matters now that the board is fed a multi-hundred-bar
     * series: reading the oldest 15 bars of an arbitrary window would call yesterday's range
     * "the open". A series that starts mid-session cannot know the open, so it reports
     * NEUTRAL instead of manufacturing one.
     */
    fun openingRangeBreakout(series: List<StrategyCandle>): StrategySignal {
        val id = "opening-range-breakout"
        val name = "Opening Range Breakout"
        val lastBar = series.lastOrNull() ?: return neutral(id, name, "No bars")
        val sessionStart = lastBar.openTime - (lastBar.openTime % SESSION_MS)
        val session = series.filter { it.openTime >= sessionStart }
        if (session.size <= ORB_BARS) {
            return neutral(id, name, "Needs the session's opening range plus a breakout bar")
        }

        val openingRange = session.take(ORB_BARS)
        val high = openingRange.maxOf { it.high }
        val low = openingRange.minOf { it.low }
        val height = high - low
        val close = lastBar.close

        return when {
            close > high -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = high, exit = high + height, invalidation = low,
                detail = "Broke the opening range high ${SignalMath.text(high)}; measured move target ${SignalMath.text(high + height)}"
            )
            close < low -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = low, exit = low - height, invalidation = high,
                detail = "Broke the opening range low ${SignalMath.text(low)}; measured move target ${SignalMath.text(low - height)}"
            )
            else -> StrategySignal(
                id, name, SignalDirection.NEUTRAL, null, null, null,
                "Inside the opening range ${SignalMath.text(low)} – ${SignalMath.text(high)}"
            )
        }
    }

    /** §3 SuperTrend Following: ATR(10) × 3 trailing band, flip on the close. */
    fun superTrend(series: List<StrategyCandle>): StrategySignal {
        val id = "super-trend"
        val name = "SuperTrend"
        if (series.size < SUPERTREND_PERIOD + 2) return neutral(id, name, "Needs ${SUPERTREND_PERIOD + 2} bars")

        var finalUpper = 0.0
        var finalLower = 0.0
        var trendUp = true
        var initialised = false

        for (index in SUPERTREND_PERIOD until series.size) {
            val atr = SignalMath.atr(series.subList(0, index + 1), SUPERTREND_PERIOD)
            if (atr.isNaN()) continue
            val midpoint = (series[index].high + series[index].low) / 2.0
            val basicUpper = midpoint + SUPERTREND_MULTIPLIER * atr
            val basicLower = midpoint - SUPERTREND_MULTIPLIER * atr
            if (!initialised) {
                finalUpper = basicUpper
                finalLower = basicLower
                trendUp = series[index].close >= finalLower
                initialised = true
            } else {
                val previousClose = series[index - 1].close
                finalUpper = if (basicUpper < finalUpper || previousClose > finalUpper) basicUpper else finalUpper
                finalLower = if (basicLower > finalLower || previousClose < finalLower) basicLower else finalLower
                trendUp = if (trendUp) series[index].close >= finalLower else series[index].close > finalUpper
            }
        }
        if (!initialised) return neutral(id, name, "ATR unavailable")

        val close = series.last().close
        return if (trendUp) {
            StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = close, exit = finalLower, invalidation = finalLower,
                detail = "Uptrend holds above the SuperTrend line ${SignalMath.text(finalLower)}"
            )
        } else {
            StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = close, exit = finalUpper, invalidation = finalUpper,
                detail = "Downtrend holds below the SuperTrend line ${SignalMath.text(finalUpper)}"
            )
        }
    }

    /** §3 Donchian Channel Breakout: 20-bar channel entry, mid-channel exit. */
    fun donchian(series: List<StrategyCandle>): StrategySignal {
        val id = "donchian-breakout"
        val name = "Donchian Channel Breakout"
        if (series.size < DONCHIAN_ENTRY + 1) return neutral(id, name, "Needs ${DONCHIAN_ENTRY + 1} bars")

        val upper = SignalMath.priorHigh(series, DONCHIAN_ENTRY)
        val lower = SignalMath.priorLow(series, DONCHIAN_ENTRY)
        val middle = (upper + lower) / 2.0
        val close = series.last().close

        return when {
            close > upper -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = upper, exit = middle, invalidation = lower,
                detail = "Closed ${SignalMath.text(close)} above the 20-bar channel ${SignalMath.text(upper)}"
            )
            close < lower -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = lower, exit = middle, invalidation = upper,
                detail = "Closed ${SignalMath.text(close)} below the 20-bar channel ${SignalMath.text(lower)}"
            )
            else -> StrategySignal(
                id, name, SignalDirection.NEUTRAL, null, null, null,
                "Inside the channel ${SignalMath.text(lower)} – ${SignalMath.text(upper)}"
            )
        }
    }

    /** §3 Connors RSI: mean reversion at the 10/90 extremes of the composite. */
    fun connorsRsi(series: List<StrategyCandle>): StrategySignal {
        val id = "connors-rsi"
        val name = "Connors RSI"
        if (series.size < CRSI_RSI_PERIOD + 2) return neutral(id, name, "Needs ${CRSI_RSI_PERIOD + 2} bars")

        val closes = series.map { it.close }
        val priceRsi = SignalMath.rsi(closes, CRSI_RSI_PERIOD)
        val streakRsi = SignalMath.rsi(streaks(closes), CRSI_STREAK_PERIOD)
        if (priceRsi.isNaN() || streakRsi.isNaN()) return neutral(id, name, "RSI unavailable")
        val percentRank = SignalMath.percentRank(closes)
        val value = (priceRsi + streakRsi + percentRank) / 3.0

        return when {
            value < CRSI_OVERSOLD -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = closes.last(), exit = null, invalidation = null,
                detail = "CRSI ${SignalMath.text(value)} < 10; exit when CRSI returns to 50"
            )
            value > CRSI_OVERBOUGHT -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = closes.last(), exit = null, invalidation = null,
                detail = "CRSI ${SignalMath.text(value)} > 90; exit when CRSI returns to 50"
            )
            else -> StrategySignal(id, name, SignalDirection.NEUTRAL, null, null, null, "CRSI ${SignalMath.text(value)}")
        }
    }

    /** §3 VWAP Reversion: fade a 2σ departure from VWAP, exit at VWAP. */
    fun vwapReversion(series: List<StrategyCandle>): StrategySignal {
        val id = "vwap-reversion"
        val name = "VWAP Reversion"
        if (series.size < VWAP_PERIOD) return neutral(id, name, "Needs $VWAP_PERIOD bars")
        val totalVolume = series.sumOf { it.volume }
        if (totalVolume <= 0.0) return neutral(id, name, "No volume to weight VWAP")

        val vwapSeries = ArrayList<Double>(series.size)
        var cumulativeVolume = 0.0
        var cumulativeValue = 0.0
        series.forEach { bar ->
            val typical = (bar.high + bar.low + bar.close) / 3.0
            cumulativeValue += typical * bar.volume
            cumulativeVolume += bar.volume
            vwapSeries.add(if (cumulativeVolume > 0.0) cumulativeValue / cumulativeVolume else typical)
        }
        val vwap = vwapSeries.last()
        val deviations = ((series.size - VWAP_PERIOD) until series.size).map { series[it].close - vwapSeries[it] }
        val sigma = SignalMath.stdev(deviations)
        val close = series.last().close
        val upperBand = vwap + VWAP_DEVIATIONS * sigma
        val lowerBand = vwap - VWAP_DEVIATIONS * sigma

        return when {
            sigma > 0.0 && close < lowerBand -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = close, exit = vwap, invalidation = close - sigma,
                detail = "Close ${SignalMath.text(close)} below the lower VWAP band ${SignalMath.text(lowerBand)}; revert to ${SignalMath.text(vwap)}"
            )
            sigma > 0.0 && close > upperBand -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = close, exit = vwap, invalidation = close + sigma,
                detail = "Close ${SignalMath.text(close)} above the upper VWAP band ${SignalMath.text(upperBand)}; revert to ${SignalMath.text(vwap)}"
            )
            else -> StrategySignal(
                id, name, SignalDirection.NEUTRAL, null, null, null,
                "Within the VWAP bands ${SignalMath.text(lowerBand)} – ${SignalMath.text(upperBand)}"
            )
        }
    }

    /** §3 Chaikin Money Flow: ±0.05 accumulation/distribution regimes. */
    fun chaikinMoneyFlow(series: List<StrategyCandle>): StrategySignal {
        val id = "chaikin-money-flow"
        val name = "Chaikin Money Flow"
        if (series.size < CMF_PERIOD + 1) return neutral(id, name, "Needs ${CMF_PERIOD + 1} bars")

        var moneyFlowVolume = 0.0
        var volume = 0.0
        series.takeLast(CMF_PERIOD).forEach { bar ->
            val range = bar.high - bar.low
            if (range > 0.0 && bar.volume > 0.0) {
                val multiplier = ((bar.close - bar.low) - (bar.high - bar.close)) / range
                moneyFlowVolume += multiplier * bar.volume
                volume += bar.volume
            }
        }
        if (volume <= 0.0) return neutral(id, name, "No volume to weight money flow")
        val value = moneyFlowVolume / volume
        val close = series.last().close

        return when {
            value > CMF_THRESHOLD -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = close, exit = null, invalidation = null,
                detail = "CMF ${SignalMath.text(value)} > +0.05; exit on a cross below zero"
            )
            value < -CMF_THRESHOLD -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = close, exit = null, invalidation = null,
                detail = "CMF ${SignalMath.text(value)} < -0.05; exit on a cross above zero"
            )
            else -> StrategySignal(id, name, SignalDirection.NEUTRAL, null, null, null, "CMF ${SignalMath.text(value)}")
        }
    }

    /**
     * §3 Triple Screen Trading System (Elder): screen 1 is the trend (EMA20 vs EMA50),
     * screen 2 the oscillator pullback (RSI14), screen 3 the prior-bar breakout trigger.
     */
    fun tripleScreen(series: List<StrategyCandle>): StrategySignal {
        val id = "triple-screen"
        val name = "Triple Screen"
        if (series.size < TRIPLE_TREND_SLOW + 2) return neutral(id, name, "Needs ${TRIPLE_TREND_SLOW + 2} bars")

        val closes = series.map { it.close }
        val fast = SignalMath.ema(closes, TRIPLE_TREND_FAST)
        val slow = SignalMath.ema(closes, TRIPLE_TREND_SLOW)
        val oscillator = SignalMath.rsi(closes, TRIPLE_OSCILLATOR)
        if (oscillator.isNaN()) return neutral(id, name, "RSI unavailable")

        val previous = series[series.size - 2]
        val current = series.last()
        val uptrend = fast > slow
        val downtrend = fast < slow

        return when {
            uptrend && oscillator < 50.0 && current.close > previous.high -> StrategySignal(
                id, name, SignalDirection.BULLISH,
                entry = previous.high, exit = previous.low, invalidation = previous.low,
                detail = "Screen 1 up (EMA20 ${SignalMath.text(fast)} > EMA50 ${SignalMath.text(slow)}), screen 2 RSI ${SignalMath.text(oscillator)} < 50, screen 3 broke ${SignalMath.text(previous.high)}"
            )
            downtrend && oscillator > 50.0 && current.close < previous.low -> StrategySignal(
                id, name, SignalDirection.BEARISH,
                entry = previous.low, exit = previous.high, invalidation = previous.high,
                detail = "Screen 1 down (EMA20 ${SignalMath.text(fast)} < EMA50 ${SignalMath.text(slow)}), screen 2 RSI ${SignalMath.text(oscillator)} > 50, screen 3 broke ${SignalMath.text(previous.low)}"
            )
            else -> StrategySignal(
                id, name, SignalDirection.NEUTRAL, null, null, null,
                "Screens disagree: EMA20 ${SignalMath.text(fast)} vs EMA50 ${SignalMath.text(slow)}, RSI ${SignalMath.text(oscillator)}"
            )
        }
    }

    /** Consecutive up/down close streak, as Connors RSI defines it. */
    private fun streaks(closes: List<Double>): List<Double> {
        val out = ArrayList<Double>(closes.size)
        if (closes.isEmpty()) return out
        out.add(0.0)
        var streak = 0.0
        for (index in 1 until closes.size) {
            streak = when {
                closes[index] > closes[index - 1] -> if (streak > 0) streak + 1 else 1.0
                closes[index] < closes[index - 1] -> if (streak < 0) streak - 1 else -1.0
                else -> 0.0
            }
            out.add(streak)
        }
        return out
    }

    private fun neutral(id: String, name: String, detail: String) =
        StrategySignal(id, name, SignalDirection.NEUTRAL, null, null, null, detail)
}
