package com.aistudio.alphapulse.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the trade-tape analyzer, specified as Given/When/Then.
 *
 * §10 asks for institutional trade-tape analysis: who is crossing the spread,
 * how big the prints are, and whether one participant swept the book. These
 * tests define each of those in numbers a reader can check by hand.
 *
 * Spec Ref: §10 Institutional Trade-Tape Analysis
 */
class TradeTapeAnalyzerBddSpec {

    private val now = 1_700_000_000_000L

    /** A $100 print, the smallest size used in these tests. */
    private fun print(
        offsetMs: Long,
        price: Double = 100.0,
        quantity: Double = 1.0,
        aggressiveBuy: Boolean = true
    ) = TapePrint(
        timestampMs = now - offsetMs,
        price = price,
        quantity = quantity,
        buyerMaker = !aggressiveBuy
    )

    // ------------------------------------------------------------- empty tape

    @Test
    fun `given no prints in the window when analysed then every metric is zero and nothing divides by zero`() {
        val metrics = TradeTapeAnalyzer.analyze(emptyList(), now)

        assertEquals(0, metrics.printCount)
        assertEquals(0.0, metrics.aggressorImbalance, 1e-9)
        assertEquals(0.0, metrics.averagePrintNotional, 1e-9)
        assertEquals(0, metrics.sweepCount)
        assertFalse("An empty tape is neither buy nor sell dominant", metrics.isBuyDominant)
        assertTrue(TradeTapeAnalyzer.describe(metrics).contains("no prints"))
    }

    @Test
    fun `given prints older than the window when analysed then they are ignored`() {
        val stale = print(offsetMs = 90_000L, quantity = 50.0) // 90 s ago, window is 60 s
        val fresh = print(offsetMs = 5_000L, quantity = 1.0)

        val metrics = TradeTapeAnalyzer.analyze(listOf(stale, fresh), now)

        assertEquals("Only the fresh print counts", 1, metrics.printCount)
        assertEquals(100.0, metrics.totalNotional, 1e-6)
    }

    @Test
    fun `given a print timestamped in the future when analysed then it is excluded`() {
        val future = TapePrint(now + 10_000L, price = 100.0, quantity = 10.0, buyerMaker = false)

        val metrics = TradeTapeAnalyzer.analyze(listOf(future), now)

        assertEquals("Future volume must never be counted", 0, metrics.printCount)
    }

    // ------------------------------------------------------------ aggressor

    @Test
    fun `given buyers lift the offer more than sellers hit the bid when analysed then the imbalance is positive`() {
        val prints = listOf(
            print(offsetMs = 30_000L, quantity = 3.0), // $300 bought
            print(offsetMs = 20_000L, quantity = 1.0), // $100 bought
            print(offsetMs = 10_000L, quantity = 1.0, aggressiveBuy = false) // $100 sold
        )

        val metrics = TradeTapeAnalyzer.analyze(prints, now)

        assertEquals(400.0, metrics.buyNotional, 1e-6)
        assertEquals(100.0, metrics.sellNotional, 1e-6)
        assertEquals("(400 - 100) / 500", 0.6, metrics.aggressorImbalance, 1e-9)
        assertTrue(metrics.isBuyDominant)
        assertFalse(metrics.isSellDominant)
        assertEquals(300.0, metrics.deltaNotional, 1e-6)
    }

    @Test
    fun `given a perfectly balanced tape when analysed then neither side dominates`() {
        val prints = listOf(
            print(offsetMs = 20_000L, quantity = 2.0),
            print(offsetMs = 10_000L, quantity = 2.0, aggressiveBuy = false)
        )

        val metrics = TradeTapeAnalyzer.analyze(prints, now)

        assertEquals(0.0, metrics.aggressorImbalance, 1e-9)
        assertFalse(metrics.isBuyDominant)
        assertFalse(metrics.isSellDominant)
    }

    @Test
    fun `given sellers are hitting the bid when analysed then the tape reads as sell dominant`() {
        val prints = listOf(
            print(offsetMs = 20_000L, quantity = 5.0, aggressiveBuy = false),
            print(offsetMs = 10_000L, quantity = 1.0)
        )

        val metrics = TradeTapeAnalyzer.analyze(prints, now)

        assertTrue(metrics.isSellDominant)
        assertTrue(TradeTapeAnalyzer.describe(metrics).contains("sellers aggressive"))
    }

    // ------------------------------------------------------------ large prints

    @Test
    fun `given one print above the large-print threshold when analysed then it is counted and reported as the largest`() {
        val prints = listOf(
            print(offsetMs = 30_000L, quantity = 1.0), // $100
            print(offsetMs = 20_000L, quantity = 2_000.0) // $200,000
        )

        val metrics = TradeTapeAnalyzer.analyze(prints, now)

        assertEquals(1, metrics.largePrintCount)
        assertEquals(200_000.0, metrics.largestPrintNotional, 1e-6)
        assertEquals("Average of $100 and $200,000", 100_050.0, metrics.averagePrintNotional, 1e-6)
    }

    @Test
    fun `given a custom large-print threshold when analysed then that threshold is used`() {
        val prints = listOf(print(offsetMs = 5_000L, quantity = 20.0)) // $2,000

        val metrics = TradeTapeAnalyzer.analyze(prints, now, largePrintNotional = 1_000.0)

        assertEquals(1, metrics.largePrintCount)
    }

    // ----------------------------------------------------------------- sweeps

    @Test
    fun `given three quick buys in a row when analysed then they count as one sweep`() {
        val prints = listOf(
            print(offsetMs = 3_000L),
            print(offsetMs = 2_900L),
            print(offsetMs = 2_800L)
        )

        assertEquals(1, TradeTapeAnalyzer.analyze(prints, now).sweepCount)
    }

    @Test
    fun `given five quick buys in a row when analysed then they still count as a single sweep`() {
        val prints = (0 until 5).map { print(offsetMs = 5_000L - it * 100L) }

        assertEquals("A five-print run is one order walking the book", 1,
            TradeTapeAnalyzer.analyze(prints, now).sweepCount)
    }

    @Test
    fun `given only two prints on the same side when analysed then no sweep is reported`() {
        val prints = listOf(print(offsetMs = 2_000L), print(offsetMs = 1_900L))

        assertEquals(0, TradeTapeAnalyzer.analyze(prints, now).sweepCount)
    }

    @Test
    fun `given three same-side prints far apart when analysed then they are separate orders not a sweep`() {
        val prints = listOf(
            print(offsetMs = 30_000L),
            print(offsetMs = 20_000L),
            print(offsetMs = 10_000L)
        )

        assertEquals(0, TradeTapeAnalyzer.analyze(prints, now).sweepCount)
    }

    @Test
    fun `given a buy run then a sell run when analysed then each side is counted separately`() {
        val prints = listOf(
            // three quick buys
            print(offsetMs = 5_000L),
            print(offsetMs = 4_900L),
            print(offsetMs = 4_800L),
            // then three quick sells
            print(offsetMs = 4_000L, aggressiveBuy = false),
            print(offsetMs = 3_900L, aggressiveBuy = false),
            print(offsetMs = 3_800L, aggressiveBuy = false)
        )

        assertEquals(2, TradeTapeAnalyzer.analyze(prints, now).sweepCount)
    }

    // -------------------------------------------------------------- narration

    @Test
    fun `given a buy-heavy tape when described then the sentence states the window, side and dollars`() {
        val prints = listOf(
            print(offsetMs = 5_000L, quantity = 12_000.0), // $1.2M
            print(offsetMs = 4_000L, quantity = 4_000.0, aggressiveBuy = false) // $400K
        )

        val text = TradeTapeAnalyzer.describe(TradeTapeAnalyzer.analyze(prints, now))

        assertTrue(text, text.contains("60s"))
        assertTrue(text, text.contains("buyers aggressive"))
        assertTrue(text, text.contains("$1.20M bought"))
        assertTrue(text, text.contains("$400.0K sold"))
    }
}
