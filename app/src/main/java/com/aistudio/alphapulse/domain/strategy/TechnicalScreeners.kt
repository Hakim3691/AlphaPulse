package com.aistudio.alphapulse.domain.strategy

/**
 * The seven technical screeners of §3's "Auxiliary Confluence Screeners".
 *
 * Why this exists
 * ---------------
 * §2 binds each pattern horizon to confluence triggers ("SMA 5/20 Cross", "MACD zero-axis
 * cross", "VWAP Band touch", "CMF/OBV volume surge", "20D/60D High/Low break" …). Without
 * executable screeners those bindings were labels; a prediction could not be corroborated
 * or audited against the trigger that was supposed to confirm it.
 *
 * Parameters are the specification's own where it states them (SMA 5/20, MA 5/10/20,
 * RSI <30 / >70, 20D/60D breakouts); where §3 names a model without numbers the canonical
 * definition of that model is used and documented at the function. Nothing is exposed as a
 * tunable parameter, because the directive asks for a fixed reference library, not a
 * configuration surface.
 *
 * Spec Ref: Directive §3 Technical Screeners
 */
object TechnicalScreeners {

    const val SMA_FAST = 5
    const val SMA_SLOW = 20
    const val MA_FAST = 5
    const val MA_MID = 10
    const val MA_SLOW = 20
    const val MACD_FAST = 12
    const val MACD_SLOW = 26
    const val MACD_SIGNAL = 9
    const val RSI_PERIOD = 14
    const val RSI_OVERSOLD = 30.0
    const val RSI_OVERBOUGHT = 70.0
    const val KDJ_PERIOD = 9
    const val KDJ_EXTREME_LOW = 20.0
    const val KDJ_EXTREME_HIGH = 80.0
    const val BOLLINGER_PERIOD = 20
    const val BOLLINGER_DEVIATIONS = 2.0
    const val BREAKOUT_SHORT = 20
    const val BREAKOUT_LONG = 60
    const val VOLUME_SURGE_MULTIPLE = 1.5

    /** Every screener of §3, in the directive's order. */
    fun evaluateAll(series: List<StrategyCandle>): List<ScreenerSignal> = listOf(
        smaCross(series),
        maAlignment(series),
        macd(series),
        rsi(series),
        kdj(series),
        bollinger(series),
        volumeBreakout(series)
    )

    /** SMA 5/20 Golden Cross / Death Cross. */
    fun smaCross(series: List<StrategyCandle>): ScreenerSignal {
        val id = "sma-5-20-cross"
        val name = "SMA 5/20 Cross"
        if (series.size < SMA_SLOW + 2) return neutral(id, name)
        val closes = series.map { it.close }
        val fastNow = SignalMath.sma(closes, SMA_FAST)
        val slowNow = SignalMath.sma(closes, SMA_SLOW)
        val fastBefore = SignalMath.sma(closes.dropLast(1), SMA_FAST)
        val slowBefore = SignalMath.sma(closes.dropLast(1), SMA_SLOW)

        return when {
            fastBefore <= slowBefore && fastNow > slowNow -> ScreenerSignal(
                id, name, SignalDirection.BULLISH,
                "Golden cross: SMA5 ${SignalMath.text(fastNow)} > SMA20 ${SignalMath.text(slowNow)}"
            )
            fastBefore >= slowBefore && fastNow < slowNow -> ScreenerSignal(
                id, name, SignalDirection.BEARISH,
                "Death cross: SMA5 ${SignalMath.text(fastNow)} < SMA20 ${SignalMath.text(slowNow)}"
            )
            else -> ScreenerSignal(
                id, name, SignalDirection.NEUTRAL,
                "SMA5 ${SignalMath.text(fastNow)} vs SMA20 ${SignalMath.text(slowNow)}"
            )
        }
    }

    /** MA bullish/bearish alignment of the 5/10/20 averages. */
    fun maAlignment(series: List<StrategyCandle>): ScreenerSignal {
        val id = "ma-5-10-20-alignment"
        val name = "MA 5/10/20 Alignment"
        if (series.size < MA_SLOW) return neutral(id, name)
        val closes = series.map { it.close }
        val fast = SignalMath.sma(closes, MA_FAST)
        val mid = SignalMath.sma(closes, MA_MID)
        val slow = SignalMath.sma(closes, MA_SLOW)

        return when {
            fast > mid && mid > slow -> ScreenerSignal(
                id, name, SignalDirection.BULLISH,
                "Bullish alignment: 5 ${SignalMath.text(fast)} > 10 ${SignalMath.text(mid)} > 20 ${SignalMath.text(slow)}"
            )
            fast < mid && mid < slow -> ScreenerSignal(
                id, name, SignalDirection.BEARISH,
                "Bearish alignment: 5 ${SignalMath.text(fast)} < 10 ${SignalMath.text(mid)} < 20 ${SignalMath.text(slow)}"
            )
            else -> ScreenerSignal(id, name, SignalDirection.NEUTRAL, "Averages are not aligned")
        }
    }

    /** MACD zero-axis crossover and top/bottom divergence. */
    fun macd(series: List<StrategyCandle>): ScreenerSignal {
        val id = "macd-zero-axis"
        val name = "MACD Zero-Axis"
        if (series.size < MACD_SLOW + MACD_SIGNAL + 1) return neutral(id, name)
        val closes = series.map { it.close }
        val fast = SignalMath.emaSeries(closes, MACD_FAST)
        val slow = SignalMath.emaSeries(closes, MACD_SLOW)
        val macdLine = DoubleArray(closes.size) { fast[it] - slow[it] }

        val last = macdLine.last()
        val previous = macdLine[macdLine.size - 2]

        if (previous <= 0.0 && last > 0.0) {
            return ScreenerSignal(id, name, SignalDirection.BULLISH, "Zero-axis bullish cross: MACD ${SignalMath.text(last)}")
        }
        if (previous >= 0.0 && last < 0.0) {
            return ScreenerSignal(id, name, SignalDirection.BEARISH, "Zero-axis bearish cross: MACD ${SignalMath.text(last)}")
        }

        // Top/bottom divergence: a new price extreme that the MACD line refuses to confirm.
        val size = closes.size
        if (size >= 20) {
            val recentHigh = (size - 10 until size).maxByOrNull { closes[it] } ?: 0
            val priorHigh = (size - 20 until size - 10).maxByOrNull { closes[it] } ?: 0
            if (closes[recentHigh] > closes[priorHigh] && macdLine[recentHigh] < macdLine[priorHigh]) {
                return ScreenerSignal(id, name, SignalDirection.BEARISH, "Bearish divergence: higher high, lower MACD")
            }
            val recentLow = (size - 10 until size).minByOrNull { closes[it] } ?: 0
            val priorLow = (size - 20 until size - 10).minByOrNull { closes[it] } ?: 0
            if (closes[recentLow] < closes[priorLow] && macdLine[recentLow] > macdLine[priorLow]) {
                return ScreenerSignal(id, name, SignalDirection.BULLISH, "Bullish divergence: lower low, higher MACD")
            }
        }

        return ScreenerSignal(id, name, SignalDirection.NEUTRAL, "MACD ${SignalMath.text(last)}")
    }

    /** RSI oversold (<30) / overbought (>70). */
    fun rsi(series: List<StrategyCandle>): ScreenerSignal {
        val id = "rsi-14"
        val name = "RSI Oversold/Overbought"
        val value = SignalMath.rsi(series.map { it.close }, RSI_PERIOD)
        if (value.isNaN()) return neutral(id, name)
        return when {
            value < RSI_OVERSOLD -> ScreenerSignal(id, name, SignalDirection.BULLISH, "RSI ${SignalMath.text(value)} < 30 (oversold)")
            value > RSI_OVERBOUGHT -> ScreenerSignal(id, name, SignalDirection.BEARISH, "RSI ${SignalMath.text(value)} > 70 (overbought)")
            else -> ScreenerSignal(id, name, SignalDirection.NEUTRAL, "RSI ${SignalMath.text(value)}")
        }
    }

    /** KDJ extreme cross and divergence. */
    fun kdj(series: List<StrategyCandle>): ScreenerSignal {
        val id = "kdj-extreme-cross"
        val name = "KDJ Extreme Cross"
        if (series.size < KDJ_PERIOD + 4) return neutral(id, name)
        val kValues = ArrayList<Double>(series.size)
        val dValues = ArrayList<Double>(series.size)
        var previousK = 50.0
        var previousD = 50.0
        for (index in series.indices) {
            if (index < KDJ_PERIOD - 1) {
                kValues.add(previousK)
                dValues.add(previousD)
                continue
            }
            val window = series.subList(index - KDJ_PERIOD + 1, index + 1)
            val highest = window.maxOf { it.high }
            val lowest = window.minOf { it.low }
            val rsv = if (highest == lowest) 50.0 else (series[index].close - lowest) / (highest - lowest) * 100.0
            // The canonical KDJ smoothing: K = 2/3 prior K + 1/3 RSV, D = 2/3 prior D + 1/3 K.
            val k = previousK * 2.0 / 3.0 + rsv / 3.0
            val d = previousD * 2.0 / 3.0 + k / 3.0
            kValues.add(k)
            dValues.add(d)
            previousK = k
            previousD = d
        }

        val k = kValues.last()
        val d = dValues.last()
        val kBefore = kValues[kValues.size - 2]
        val dBefore = dValues[dValues.size - 2]

        if (kBefore <= dBefore && k > d && k < KDJ_EXTREME_LOW) {
            return ScreenerSignal(id, name, SignalDirection.BULLISH, "K ${SignalMath.text(k)} crossed above D in the lower extreme")
        }
        if (kBefore >= dBefore && k < d && k > KDJ_EXTREME_HIGH) {
            return ScreenerSignal(id, name, SignalDirection.BEARISH, "K ${SignalMath.text(k)} crossed below D in the upper extreme")
        }

        // Divergence: price makes a new extreme the K line refuses to confirm.
        val size = series.size
        if (size >= 20) {
            val recentHigh = (size - 10 until size).maxByOrNull { series[it].high } ?: 0
            val priorHigh = (size - 20 until size - 10).maxByOrNull { series[it].high } ?: 0
            if (series[recentHigh].high > series[priorHigh].high && kValues[recentHigh] < kValues[priorHigh]) {
                return ScreenerSignal(id, name, SignalDirection.BEARISH, "KDJ bearish divergence at the highs")
            }
            val recentLow = (size - 10 until size).minByOrNull { series[it].low } ?: 0
            val priorLow = (size - 20 until size - 10).minByOrNull { series[it].low } ?: 0
            if (series[recentLow].low < series[priorLow].low && kValues[recentLow] > kValues[priorLow]) {
                return ScreenerSignal(id, name, SignalDirection.BULLISH, "KDJ bullish divergence at the lows")
            }
        }
        return ScreenerSignal(id, name, SignalDirection.NEUTRAL, "K ${SignalMath.text(k)} / D ${SignalMath.text(d)}")
    }

    /** Bollinger Band lower bounce / upper resistance (20, 2σ). */
    fun bollinger(series: List<StrategyCandle>): ScreenerSignal {
        val id = "bollinger-band-bounce"
        val name = "Bollinger Band Bounce"
        if (series.size < BOLLINGER_PERIOD + 1) return neutral(id, name)
        val closes = series.map { it.close }
        val middle = SignalMath.sma(closes, BOLLINGER_PERIOD)
        val deviation = SignalMath.stdev(closes.takeLast(BOLLINGER_PERIOD))
        val upper = middle + BOLLINGER_DEVIATIONS * deviation
        val lower = middle - BOLLINGER_DEVIATIONS * deviation
        val previous = series[series.size - 2]
        val current = series.last()

        return when {
            previous.low <= lower && current.close > lower -> ScreenerSignal(
                id, name, SignalDirection.BULLISH,
                "Lower band bounce: low ${SignalMath.text(previous.low)} <= ${SignalMath.text(lower)}"
            )
            previous.high >= upper && current.close < upper -> ScreenerSignal(
                id, name, SignalDirection.BEARISH,
                "Upper band rejection: high ${SignalMath.text(previous.high)} >= ${SignalMath.text(upper)}"
            )
            else -> ScreenerSignal(id, name, SignalDirection.NEUTRAL, "Bands ${SignalMath.text(lower)} – ${SignalMath.text(upper)}")
        }
    }

    /** 20-day and 60-day high/low volume breakouts. */
    fun volumeBreakout(series: List<StrategyCandle>): ScreenerSignal {
        val id = "volume-breakout-20-60"
        val name = "20D/60D Volume Breakout"
        if (series.size < BREAKOUT_SHORT + 2) return neutral(id, name)
        val history = series.dropLast(1)
        val current = series.last()
        val averageVolume = history.takeLast(BREAKOUT_SHORT).map { it.volume }.average()
        val surging = current.volume > averageVolume * VOLUME_SURGE_MULTIPLE

        if (series.size >= BREAKOUT_LONG + 1) {
            val high60 = history.takeLast(BREAKOUT_LONG).maxOf { it.high }
            val low60 = history.takeLast(BREAKOUT_LONG).minOf { it.low }
            if (surging && current.close > high60) {
                return ScreenerSignal(id, name, SignalDirection.BULLISH, "60-day high ${SignalMath.text(high60)} on expanding volume")
            }
            if (surging && current.close < low60) {
                return ScreenerSignal(id, name, SignalDirection.BEARISH, "60-day low ${SignalMath.text(low60)} on expanding volume")
            }
        }

        val high20 = history.takeLast(BREAKOUT_SHORT).maxOf { it.high }
        val low20 = history.takeLast(BREAKOUT_SHORT).minOf { it.low }
        return when {
            surging && current.close > high20 -> ScreenerSignal(id, name, SignalDirection.BULLISH, "20-day high ${SignalMath.text(high20)} on expanding volume")
            surging && current.close < low20 -> ScreenerSignal(id, name, SignalDirection.BEARISH, "20-day low ${SignalMath.text(low20)} on expanding volume")
            else -> ScreenerSignal(id, name, SignalDirection.NEUTRAL, "No volume-confirmed breakout")
        }
    }

    private fun neutral(id: String, name: String) = ScreenerSignal(id, name, SignalDirection.NEUTRAL, "Insufficient history")
}
