package com.aistudio.alphapulse.domain.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the nine quantitative models of §3.
 *
 * Why this spec exists
 * --------------------
 * §3 names the models and, for Turtle, states the rule ("20D High / 10D Low Exit"). The
 * implementation previously stopped at the pattern taxonomy, so those nine models were
 * captions. This spec encodes what each rule must do *before* the code is allowed to claim
 * it: a direction, the entry level the rule triggers on, the exit level the rule defines and
 * the invalidation level whose breach voids the setup. A model with too little history must
 * report NEUTRAL with no levels rather than invent a trade.
 *
 * Spec Ref: Directive §3 Quantitative Strategy Execution
 */
class QuantStrategyEngineBddSpec {

    private fun candle(
        index: Int,
        close: Double,
        volume: Double = 100.0,
        spread: Double = 0.2,
        open: Double = close
    ) = StrategyCandle(
        openTime = index * 60_000L,
        open = open,
        high = close + spread,
        low = close - spread,
        close = close,
        volume = volume
    )

    private fun ascending(count: Int, start: Double = 100.0, step: Double = 1.0) =
        (0 until count).map { candle(it, start + it * step) }

    private val expectedIds = listOf(
        "turtle-breakout",
        "dual-thrust",
        "opening-range-breakout",
        "super-trend",
        "donchian-breakout",
        "connors-rsi",
        "vwap-reversion",
        "chaikin-money-flow",
        "triple-screen"
    )

    @Test
    fun `given section three's catalogue when evaluated then exactly the nine named models are reported in order`() {
        val signals = QuantStrategyEngine.evaluateAll(ascending(120))
        assertEquals(expectedIds, signals.map { it.id })
        assertEquals(signals.size, signals.map { it.name }.distinct().size)
    }

    @Test
    fun `given a close above the twenty bar high when the turtle rule runs then it is bullish and exits on the ten bar low`() {
        val signal = QuantStrategyEngine.turtle(ascending(21))

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertEquals(119.2, signal.entry!!, 1e-9)
        assertEquals(109.8, signal.exit!!, 1e-9)
        // The 10-bar low is both the exit and the level whose breach voids the trade.
        assertEquals(signal.exit, signal.invalidation)
        assertTrue(signal.detail.contains("20-bar high"))
    }

    @Test
    fun `given a close inside the twenty bar range when the turtle rule runs then no trade is reported`() {
        val flat = (0 until 25).map { candle(it, 100.0) }
        val signal = QuantStrategyEngine.turtle(flat)

        assertEquals(SignalDirection.NEUTRAL, signal.direction)
        assertNull(signal.entry)
        assertNull(signal.exit)
        assertNull(signal.invalidation)
    }

    @Test
    fun `given an opening drive when dual thrust runs then the buy line is the five bar range offset from the open`() {
        val history = (0 until 5).map { candle(it, 100.0, spread = 2.0, open = 100.0) } // H 102, L 98, C 100
        val breakout = candle(5, 103.0, spread = 0.5, open = 100.0)
        val signal = QuantStrategyEngine.dualThrust(history + breakout)

        // range = max(HH−LC, HC−LL) = max(102−100, 100−98) = 2 -> buy line 101, sell line 99.
        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertEquals(101.0, signal.entry!!, 1e-9)
        assertEquals(99.0, signal.exit!!, 1e-9)
    }

    @Test
    fun `given a break of the opening range when the orb rule runs then the measured move is the exit and the range low invalidates`() {
        val openingRange = (0 until 15).map { candle(it, 100.0, spread = 1.0) } // H 101, L 99
        val inside = (15 until 19).map { candle(it, 100.0, spread = 1.0) }
        val breakout = candle(19, 102.0, spread = 0.5)
        val signal = QuantStrategyEngine.openingRangeBreakout(openingRange + inside + breakout)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertEquals(101.0, signal.entry!!, 1e-9)
        assertEquals(103.0, signal.exit!!, 1e-9)   // measured move = range height
        assertEquals(99.0, signal.invalidation!!, 1e-9)
    }

    @Test
    fun `given a sustained advance when supertrend runs then it stays long with the trailing line below price`() {
        val series = ascending(40)
        val signal = QuantStrategyEngine.superTrend(series)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertNotNull(signal.exit)
        assertTrue("trailing line ${signal.exit} must sit below the close", signal.exit!! < series.last().close)
    }

    @Test
    fun `given a channel breakout when donchian runs then entry is the channel exit is its midpoint and the far side invalidates`() {
        val signal = QuantStrategyEngine.donchian(ascending(21))

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertEquals(119.2, signal.entry!!, 1e-9)
        assertEquals(109.5, signal.exit!!, 1e-9)      // midpoint of 99.8 .. 119.2
        assertEquals(99.8, signal.invalidation!!, 1e-9)
        assertTrue(signal.entry!! > signal.exit!! && signal.exit!! > signal.invalidation!!)
    }

    @Test
    fun `given an unbroken decline when connors rsi runs then the composite is oversold and the model is long`() {
        val decline = (0 until 130).map { candle(it, 300.0 - it) }
        val signal = QuantStrategyEngine.connorsRsi(decline)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertTrue(signal.detail, signal.detail.contains("CRSI"))
        assertEquals(decline.last().close, signal.entry!!, 1e-9)
    }

    @Test
    fun `given a price stretched below vwap when the reversion rule runs then it buys toward vwap and stops one sigma lower`() {
        val flat = (0 until 24).map { candle(it, 100.0, volume = 100.0, spread = 0.5) }
        val stretched = StrategyCandle(24 * 60_000L, 100.0, 100.0, 96.0, 96.5, 100.0)
        val signal = QuantStrategyEngine.vwapReversion(flat + stretched)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertEquals(96.5, signal.entry!!, 1e-9)
        assertTrue("exit ${signal.exit} must be above entry", signal.exit!! > signal.entry!!)
        assertTrue("invalidation ${signal.invalidation} must be below entry", signal.invalidation!! < signal.entry!!)
    }

    @Test
    fun `given accumulation under the high when chaikin money flow runs then the model is long`() {
        val accumulation = (0 until 21).map {
            StrategyCandle(it * 60_000L, 100.0, 101.0, 99.0, 100.9, 100.0)
        }
        val signal = QuantStrategyEngine.chaikinMoneyFlow(accumulation)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertTrue(signal.detail.contains("CMF"))
    }

    @Test
    fun `given an uptrend with a shallow pullback and a breakout when the triple screen runs then all three screens agree long`() {
        // A long advance keeps EMA20 above EMA50; a 10-bar dip of 2 per bar pulls RSI14
        // below 50 without flipping the trend; the trigger then breaks the prior bar high.
        val rally = (0 until 100).map { candle(it, 100.0 + it) }
        val pullback = (1..10).map { candle(99 + it, 200.0 - 2.0 * it) }
        val trigger = StrategyCandle(110 * 60_000L, 180.0, 181.5, 179.5, 181.0, 100.0)
        val signal = QuantStrategyEngine.tripleScreen(rally + pullback + trigger)

        assertEquals(SignalDirection.BULLISH, signal.direction)
        assertEquals(180.2, signal.entry!!, 1e-9)   // prior bar high
        assertEquals(179.8, signal.exit!!, 1e-9)    // prior bar low
    }

    @Test
    fun `given too little history when any model runs then it reports neutral with no fabricated levels`() {
        val signals = QuantStrategyEngine.evaluateAll(emptyList())
        assertEquals(expectedIds, signals.map { it.id })
        signals.forEach { signal ->
            assertEquals("${signal.id} must not fabricate a trade", SignalDirection.NEUTRAL, signal.direction)
            assertNull(signal.entry)
            assertNull(signal.exit)
            assertNull(signal.invalidation)
        }
    }
}
