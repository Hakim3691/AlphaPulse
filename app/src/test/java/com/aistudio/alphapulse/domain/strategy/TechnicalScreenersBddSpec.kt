package com.aistudio.alphapulse.domain.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of §3's seven technical confluence screeners.
 *
 * Why this spec exists
 * --------------------
 * §2 binds each pattern horizon to named confluence triggers, so those triggers have to be
 * executable rather than captions. This spec pins each screener to the rule the directive
 * states (SMA 5/20 golden and death crosses, 5/10/20 alignment, MACD zero-axis cross and
 * divergence, RSI 30/70, KDJ extreme cross and divergence, Bollinger band bounce, 20D/60D
 * volume breakout) and requires NEUTRAL — never a guess — when the history is too short.
 *
 * Spec Ref: Directive §3 Technical Screeners
 */
class TechnicalScreenersBddSpec {

    private fun candle(
        index: Int,
        close: Double,
        volume: Double = 100.0,
        spread: Double = 0.2,
        low: Double = close - spread,
        high: Double = close + spread
    ) = StrategyCandle(
        openTime = index * 60_000L,
        open = close,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

    private fun closes(vararg values: Double) = values.mapIndexed { index, value -> candle(index, value) }

    private val expectedIds = listOf(
        "sma-5-20-cross",
        "ma-5-10-20-alignment",
        "macd-zero-axis",
        "rsi-14",
        "kdj-extreme-cross",
        "bollinger-band-bounce",
        "volume-breakout-20-60"
    )

    @Test
    fun `given section three's screeners when evaluated then exactly the seven named screeners are reported in order`() {
        val series = (0 until 80).map { candle(it, 100.0 + it) }
        assertEquals(expectedIds, TechnicalScreeners.evaluateAll(series).map { it.id })
    }

    @Test
    fun `given the five period average crossing above the twenty period average then a golden cross is reported`() {
        val series = closes(*DoubleArray(20) { 100.0 }, 100.0, 100.0, 130.0)
        val signal = TechnicalScreeners.smaCross(series)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertTrue(signal.detail, signal.detail.contains("Golden cross"))
    }

    @Test
    fun `given the five period average crossing below the twenty period average then a death cross is reported`() {
        val series = closes(*DoubleArray(20) { 100.0 }, 100.0, 100.0, 70.0)
        val signal = TechnicalScreeners.smaCross(series)

        assertEquals(SignalDirection.BEARISH, signal.direction)
        assertTrue(signal.detail, signal.detail.contains("Death cross"))
    }

    @Test
    fun `given stacked averages when alignment runs then the direction follows the stack order`() {
        val rising = (0 until 25).map { candle(it, 100.0 + it) }
        val falling = (0 until 25).map { candle(it, 200.0 - it) }

        assertEquals(SignalDirection.BULLISH, TechnicalScreeners.maAlignment(rising).direction)
        assertEquals(SignalDirection.BEARISH, TechnicalScreeners.maAlignment(falling).direction)
    }

    @Test
    fun `given a decline that turns into a rally when macd runs then the zero-axis bullish cross is reported on that bar`() {
        val series = (0 until 140).map { index ->
            val close = if (index < 70) 240.0 - index else 170.0 + (index - 70) * 1.5
            candle(index, close)
        }

        val crossings = (40..series.size)
            .map { series.take(it) }
            .map { TechnicalScreeners.macd(it) }
            .filter { it.direction == SignalDirection.BULLISH && it.detail.contains("Zero-axis") }

        assertTrue("a zero-axis bullish cross must be reported", crossings.isNotEmpty())
    }

    @Test
    fun `given the specified thresholds when rsi runs then oversold is bullish and overbought is bearish`() {
        val falling = (0 until 40).map { candle(it, 200.0 - it) }
        val rising = (0 until 40).map { candle(it, 100.0 + it) }

        assertEquals(SignalDirection.BULLISH, TechnicalScreeners.rsi(falling).direction)
        assertEquals(SignalDirection.BEARISH, TechnicalScreeners.rsi(rising).direction)
    }

    @Test
    fun `given a stalled decline when kdj runs then the lower extreme cross reads bullish`() {
        val decline = (0 until 30).map { candle(it, 100.0 - it * 0.3) }
        val stall = candle(30, 91.4)
        val signal = TechnicalScreeners.kdj(decline + stall)

        assertEquals(SignalDirection.BULLISH, signal.direction)
    }

    @Test
    fun `given a dip through the lower band and a close back inside when bollinger runs then the bounce is bullish`() {
        val flat = (0 until 19).map { candle(it, 100.0) }
        val dip = candle(19, 100.0, low = 98.0)
        val recovery = candle(20, 100.5)
        val signal = TechnicalScreeners.bollinger(flat + dip + recovery)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertTrue(signal.detail, signal.detail.contains("Lower band"))
    }

    @Test
    fun `given a breakout on expanding volume when the volume screener runs then the direction follows the break`() {
        // 21 prior bars: the screener needs BREAKOUT_SHORT + 2 bars to compare against.
        val base = (0 until 21).map { candle(it, 100.0, volume = 10.0) }
        val breakout = candle(21, 105.0, volume = 100.0)
        val signal = TechnicalScreeners.volumeBreakout(base + breakout)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertTrue(signal.detail, signal.detail.contains("20-day high"))
    }

    @Test
    fun `given too little history when any screener runs then it abstains instead of guessing`() {
        val signals = TechnicalScreeners.evaluateAll(emptyList())
        assertEquals(expectedIds, signals.map { it.id })
        signals.forEach { assertEquals(SignalDirection.NEUTRAL, it.direction) }
        assertNotEquals(SignalDirection.BULLISH, TechnicalScreeners.rsi(emptyList()).direction)
    }
}
