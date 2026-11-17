package com.aistudio.alphapulse.domain.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1-§4 — the dual ledger's second table: synchronized target clock, issuing, settlement
 * and the SGD callback.
 *
 * The price ledger already has a settlement contract; this engine must mirror it without
 * touching it. These scenarios pin the alignment (same phase-locked boundary as the price
 * ledger), the honesty rules (no pattern issued when there is none, no grading on an
 * unfinished bar) and the learning callback.
 *
 * Spec Ref: Directive §1 Dual Ledger, §4 SGD Pipeline
 */
class PatternLedgerEngineBddSpec {

    private fun bar(o: Double, h: Double, l: Double, c: Double, openTime: Long) =
        PatternCandle(openTime = openTime, open = o, high = h, low = l, close = c, volume = 10.0)

    /** Five falling 1-minute bars, then a hammer that closes the move. */
    private fun seriesWithHammer(): List<PatternCandle> = listOf(
        bar(106.0, 106.4, 105.2, 105.4, 0L),
        bar(105.4, 105.8, 104.4, 104.6, 60_000L),
        bar(104.6, 105.0, 103.6, 103.8, 120_000L),
        bar(103.8, 104.2, 102.8, 103.0, 180_000L),
        bar(103.0, 103.4, 102.0, 102.2, 240_000L),
        bar(102.2, 102.6, 99.8, 102.6, 300_000L)
    )

    @Test
    fun `given the price ledger's clock when a pattern target is computed then it lands on the same phase-locked boundary`() {
        val now = 341_000L // 00:05:41 into the epoch's minute grid

        assertEquals(
            "The 1-minute pattern must expire at the price prediction's boundary",
            360_000L,
            PatternLedgerEngine.targetTimestamp(now, PatternHorizon.M1)
        )
        assertEquals(600_000L, PatternLedgerEngine.targetTimestamp(now, PatternHorizon.M5))
        assertEquals(1_200_000L, PatternLedgerEngine.targetTimestamp(now, PatternHorizon.M15))
        assertEquals(2_100_000L, PatternLedgerEngine.targetTimestamp(now, PatternHorizon.M30))
        assertEquals(3_900_000L, PatternLedgerEngine.targetTimestamp(now, PatternHorizon.H1))

        val priceLedgerFormula = ((now / 60_000L) + 15) * 60_000L
        assertEquals(
            "Both ledgers must derive the boundary from the same formula",
            priceLedgerFormula,
            PatternLedgerEngine.targetTimestamp(now, PatternHorizon.M15)
        )
    }

    @Test
    fun `given one-minute candles when rolled up then the horizon bar keeps open high low close and volume`() {
        val candles = listOf(
            bar(100.0, 101.0, 99.0, 100.5, 0L),
            bar(100.5, 103.0, 100.0, 102.5, 60_000L),
            bar(102.5, 102.8, 101.0, 101.4, 120_000L),
            bar(101.4, 101.9, 100.2, 100.8, 180_000L),
            bar(100.8, 102.4, 100.6, 102.2, 240_000L),
            bar(102.2, 102.6, 101.8, 102.0, 300_000L)
        )

        val fiveMinute = PatternLedgerEngine.aggregate(candles, PatternHorizon.M5)

        assertEquals("six 1m bars are two 5m bars", 2, fiveMinute.size)
        assertEquals(0L, fiveMinute.first().openTime)
        assertEquals(100.0, fiveMinute.first().open, 0.0)
        assertEquals(103.0, fiveMinute.first().high, 0.0)
        assertEquals(99.0, fiveMinute.first().low, 0.0)
        assertEquals(102.2, fiveMinute.first().close, 0.0)
        assertEquals(50.0, fiveMinute.first().volume, 1e-9)
        assertEquals(300_000L, fiveMinute.last().openTime)
    }

    @Test
    fun `given a recognised formation when a prediction is issued then the row carries the taxonomy name confidence and a pending status`() {
        val entry = PatternLedgerEngine.issue(
            symbol = "VVVUSDT",
            candles = seriesWithHammer(),
            horizon = PatternHorizon.M1,
            nowMs = 341_000L
        ) ?: throw AssertionError("A hammer series must produce a 1m pattern prediction")

        assertEquals("VVV", entry.asset)
        assertEquals("1m", entry.horizon)
        assertEquals(360_000L, entry.targetTime)
        assertEquals("hammer", entry.predictedPatternId)
        assertEquals("Hammer", entry.predictedPatternName)
        assertEquals("Bullish Reversal", entry.predictedCategory)
        assertTrue(entry.confidence in 0.5..1.0)
        assertEquals(PatternLedgerEngine.STATUS_PENDING, entry.status)
        assertEquals("Pending Settlement", entry.sgdAction)
        assertTrue(!entry.settled)
    }

    @Test
    fun `given a flat tape when a prediction is requested then no row is fabricated`() {
        val flat = (0 until 8).map { bar(100.0, 100.1, 99.9, 100.0, it * 60_000L) }

        assertNull(
            "An empty ledger row is honest; a made-up pattern is not",
            PatternLedgerEngine.issue("VVVUSDT", flat, PatternHorizon.M1, 341_000L)
        )
    }

    @Test
    fun `given a boundary that has not closed when the prediction settles then it stays evaluating with no loss`() {
        val entry = PatternLedgerEngine.issue("VVVUSDT", seriesWithHammer(), PatternHorizon.M1, 341_000L)!!
        val model = PatternSgdModel(PatternTaxonomy.idsFor(PatternHorizon.M1))

        val settlement = PatternLedgerEngine.settle(
            entry = entry,
            candles = seriesWithHammer().dropLast(1), // the boundary bar is still forming
            horizon = PatternHorizon.M1,
            model = model
        )

        assertEquals(PatternLedgerEngine.STATUS_EVALUATING, settlement.entry.status)
        assertNull("An unfinished bar cannot produce a ground truth", settlement.fitLoss)
        assertNull(settlement.step)
        assertTrue(!settlement.entry.settled)
    }

    @Test
    fun `given a closed boundary when the prediction settles then the realized pattern is audited and one SGD step is recorded`() {
        val entry = PatternLedgerEngine.issue("VVVUSDT", seriesWithHammer(), PatternHorizon.M1, 341_000L)!!
        val model = PatternSgdModel(PatternTaxonomy.idsFor(PatternHorizon.M1))

        val settlement = PatternLedgerEngine.settle(
            entry = entry,
            candles = seriesWithHammer(),
            horizon = PatternHorizon.M1,
            model = model
        )

        assertEquals(PatternLedgerEngine.STATUS_SETTLED, settlement.entry.status)
        assertTrue("The realized geometry must be audited", settlement.entry.settled)
        assertTrue(
            "The realized formation must come from the closed window: ${settlement.entry.realizedPattern}",
            settlement.entry.realizedPattern != null
        )
        assertTrue(
            "Categorical cross-entropy must be recorded: ${settlement.fitLoss}",
            (settlement.fitLoss ?: -1.0) >= 0.0
        )
        assertEquals(1, model.steps)
        assertTrue(
            "The ledger's SGD column must report the step: ${settlement.entry.sgdAction}",
            settlement.entry.sgdAction.startsWith("SGD Step:")
        )
        assertEquals(settlement.fitLoss!!, settlement.entry.fitLoss!!, 0.0)
    }

    @Test
    fun `given each horizon when predictions are issued then the rows are keyed by horizon and boundary without collisions`() {
        // A clean one-minute advance: three rising bodies are Three White Soldiers on 1m,
        // and roll up into the same formation on 15m.
        val candles = (0 until 90).map { index ->
            val open = 100.0 + index * 0.5
            bar(open, open + 0.6, open - 0.1, open + 0.5, index * 60_000L)
        }

        val issued = PatternHorizon.entries.mapNotNull { horizon ->
            PatternLedgerEngine.issue("VVVUSDT", candles, horizon, 5_341_000L)
        }

        assertTrue("At least one structural horizon must find a formation", issued.isNotEmpty())
        assertEquals(
            "A prediction is keyed by asset + horizon + boundary, so two horizons can never collide",
            issued.size,
            issued.map { it.id }.toSet().size
        )
        assertEquals(issued.size, issued.map { it.horizon }.toSet().size)
        issued.forEach { entry ->
            assertNotEquals(0L, entry.targetTime)
            assertTrue(
                "Every horizon must expire after it was issued: ${entry.horizon}",
                entry.targetTime > entry.issuedTimestamp
            )
        }
    }

    @Test
    fun `given a prediction before at and after its boundary when the status is rendered then it counts down then evaluates then settles`() {
        val entry = PatternLedgerEngine.issue("VVVUSDT", seriesWithHammer(), PatternHorizon.M1, 341_000L)!!

        assertEquals("In 00:19", PatternLedgerEngine.statusLabel(entry, 341_000L))
        assertEquals("Evaluating", PatternLedgerEngine.statusLabel(entry, 360_000L))
        assertEquals(
            "Settled",
            PatternLedgerEngine.statusLabel(entry.copy(status = PatternLedgerEngine.STATUS_SETTLED), 400_000L)
        )
    }
}
