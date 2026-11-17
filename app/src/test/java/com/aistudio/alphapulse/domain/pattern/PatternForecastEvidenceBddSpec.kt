package com.aistudio.alphapulse.domain.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-25 / §16 — the pattern ledger consults the same mounted Chronos-Bolt / TimesFM ensemble
 * the price ledger does, and records what it said.
 *
 * The price ledger already blends the two TSFMs into its cone. The pattern ledger previously
 * scored pure geometry, so two ledgers on one screen disagreed about whether the models were
 * consulted. These scenarios pin the contract: the classifier still owns the *pattern*
 * (settlement is graded against geometry), while the ensemble may only move the prior by a
 * bounded amount and must leave a row untouched when it has no opinion.
 *
 * Spec Ref: §16 Model Strategy, §73 Pattern Ledger, C-25
 */
class PatternForecastEvidenceBddSpec {

    private fun bar(o: Double, h: Double, l: Double, c: Double, openTime: Long) =
        PatternCandle(openTime = openTime, open = o, high = h, low = l, close = c, volume = 10.0)

    /** Five falling bars, then a hammer whose long lower wick the classifier scores 0.90. */
    private fun hammerSeries(): List<PatternCandle> = listOf(
        bar(106.0, 106.4, 105.2, 105.4, 0L),
        bar(105.4, 105.8, 104.4, 104.6, 60_000L),
        bar(104.6, 105.0, 103.6, 103.8, 120_000L),
        bar(103.8, 104.2, 102.8, 103.0, 180_000L),
        bar(103.0, 103.4, 102.0, 102.2, 240_000L),
        bar(102.2, 102.6, 99.8, 102.6, 300_000L)
    )

    private fun risingSeries(): List<PatternCandle> = (0 until 6).map { index ->
        val open = 100.0 + index
        bar(open, open + 0.8, open - 0.1, open + 0.7, index * 60_000L)
    }

    @Test
    fun `given chronos and timesfm point the same way as a bullish reversal when it is issued then confidence rises and the row records the ensemble`() {
        val entry = PatternLedgerEngine.issue(
            symbol = "VVVUSDT",
            candles = hammerSeries(),
            horizon = PatternHorizon.M1,
            nowMs = 341_000L,
            forecast = PatternForecastEvidence("chronos+timesfm", 0.12)
        ) ?: throw AssertionError("The hammer must still be issued")

        assertEquals("hammer", entry.predictedPatternId)
        assertEquals(
            "An aligned ensemble may confirm a formation by its bounded bonus",
            0.90 + PatternForecastEvidence.AGREEMENT_BONUS,
            entry.confidence,
            1e-9
        )
        assertEquals("chronos+timesfm", entry.tsfmProvider)
        assertEquals(0.12, entry.tsfmDriftPct, 1e-9)
        assertEquals(true, entry.tsfmAligned)
    }

    @Test
    fun `given the ensemble forecasts the opposite move when a bullish reversal is issued then confidence is penalised and the dissent is auditable`() {
        val entry = PatternLedgerEngine.issue(
            symbol = "VVVUSDT",
            candles = hammerSeries(),
            horizon = PatternHorizon.M1,
            nowMs = 341_000L,
            forecast = PatternForecastEvidence("timesfm", -0.12)
        ) ?: throw AssertionError("The hammer must still be issued")

        assertEquals(
            "A contradiction lowers the prior but never erases the formation",
            0.90 - PatternForecastEvidence.CONTRADICTION_PENALTY,
            entry.confidence,
            1e-9
        )
        assertEquals("timesfm", entry.tsfmProvider)
        assertEquals(false, entry.tsfmAligned)
    }

    @Test
    fun `given no mounted model produced a forecast when a pattern is issued then geometry is unchanged and no model is claimed`() {
        val entry = PatternLedgerEngine.issue(
            symbol = "VVVUSDT",
            candles = hammerSeries(),
            horizon = PatternHorizon.M1,
            nowMs = 341_000L
        ) ?: throw AssertionError("The hammer must still be issued without models")

        assertEquals("Geometry alone must keep the classifier's own confidence", 0.90, entry.confidence, 1e-9)
        assertEquals("", entry.tsfmProvider)
        assertEquals(0.0, entry.tsfmDriftPct, 1e-9)
        assertNull("No model means no alignment claim", entry.tsfmAligned)
    }

    @Test
    fun `given an ensemble drift inside the noise floor when the prior is applied then the formation is not re-ranked`() {
        val entry = PatternLedgerEngine.issue(
            symbol = "VVVUSDT",
            candles = hammerSeries(),
            horizon = PatternHorizon.M1,
            nowMs = 341_000L,
            forecast = PatternForecastEvidence("chronos", PatternForecastEvidence.MIN_DRIFT_PCT / 10.0)
        ) ?: throw AssertionError("The hammer must still be issued")

        assertEquals(0.90, entry.confidence, 1e-9)
        assertEquals("chronos", entry.tsfmProvider)
        assertNull("A move under the noise floor is not an opinion", entry.tsfmAligned)
    }

    @Test
    fun `given a continuation formation when the ensemble runs with or against the existing trend then alignment follows the trend`() {
        val match = PatternMatch("rising-flag", "Rising Flag", PatternCategory.CONTINUATION, 0.60)
        val series = risingSeries()

        assertEquals(
            "A rising continuation is confirmed by a positive forecast",
            true,
            PatternLedgerEngine.alignment(match, series, PatternForecastEvidence("chronos", 0.10))
        )
        assertEquals(
            "A rising continuation is opposed by a negative forecast",
            false,
            PatternLedgerEngine.alignment(match, series, PatternForecastEvidence("chronos", -0.10))
        )
    }

    @Test
    fun `given a reversal when the ensemble has no drift then no alignment is invented`() {
        val match = PatternMatch("hammer", "Hammer", PatternCategory.BULLISH_REVERSAL, 0.80)

        assertNull(
            PatternLedgerEngine.alignment(match, hammerSeries(), PatternForecastEvidence.NONE)
        )
        assertNull(
            PatternLedgerEngine.alignment(match, hammerSeries(), PatternForecastEvidence("chronos", 0.0))
        )
    }

    @Test
    fun `given a settlement when the ensemble confirmed the prediction then the recorded evidence survives on the settled row`() {
        val entry = PatternLedgerEngine.issue(
            symbol = "VVVUSDT",
            candles = hammerSeries(),
            horizon = PatternHorizon.M1,
            nowMs = 341_000L,
            forecast = PatternForecastEvidence("chronos", 0.12)
        )!!
        val model = PatternSgdModel(PatternTaxonomy.idsFor(PatternHorizon.M1))

        val settlement = PatternLedgerEngine.settle(
            entry = entry,
            candles = hammerSeries(),
            horizon = PatternHorizon.M1,
            model = model
        )

        assertTrue("The row must settle", settlement.entry.settled)
        assertEquals("chronos", settlement.entry.tsfmProvider)
        assertEquals(0.12, settlement.entry.tsfmDriftPct, 1e-9)
        assertEquals(true, settlement.entry.tsfmAligned)
        assertFalse("The evidence must not be rewritten by settlement", settlement.entry.confidence < 0.5)
    }
}
