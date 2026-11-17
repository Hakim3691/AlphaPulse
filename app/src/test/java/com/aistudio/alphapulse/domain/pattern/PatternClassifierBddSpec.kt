package com.aistudio.alphapulse.domain.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2-§4 — the deterministic geometry classifier that produces `predictedPattern`.
 *
 * The ground truth is extracted from candle geometry, so the classifier has to be as
 * deterministic as the audit that grades it: the same bars must always produce the same
 * pattern and the same confidence. A formation that is not bound to the requested
 * horizon must never appear, or the ledger would attribute an hourly structure to a
 * one-minute bet.
 *
 * Spec Ref: Directive §2 Validity Framework, §3 Taxonomy, §4 PatternLedgerEntry
 */
class PatternClassifierBddSpec {

    private fun bar(o: Double, h: Double, l: Double, c: Double, volume: Double = 1.0) =
        PatternCandle(openTime = 0L, open = o, high = h, low = l, close = c, volume = volume)

    /** Six falling bars, so a reversal that follows them has a downtrend to reverse. */
    private fun downtrend(): List<PatternCandle> = listOf(
        bar(106.0, 106.4, 105.2, 105.4),
        bar(105.4, 105.8, 104.4, 104.6),
        bar(104.6, 105.0, 103.6, 103.8),
        bar(103.8, 104.2, 102.8, 103.0),
        bar(103.0, 103.4, 102.0, 102.2)
    )

    /** Six rising bars, the context a top reversal needs. */
    private fun uptrend(): List<PatternCandle> = listOf(
        bar(100.0, 100.8, 99.8, 100.6),
        bar(100.6, 101.4, 100.4, 101.2),
        bar(101.2, 102.0, 101.0, 101.8),
        bar(101.8, 102.6, 101.6, 102.4),
        bar(102.4, 103.2, 102.2, 103.0)
    )

    @Test
    fun `given a hammer at the end of a downtrend when classified on the one minute horizon then it is predicted as a bullish reversal`() {
        val hammer = bar(102.2, 102.6, 99.8, 102.6) // tiny body at the top, long lower wick
        val matches = PatternClassifier.classify(downtrend() + hammer, PatternHorizon.M1)

        val match = matches.firstOrNull { it.id == "hammer" }
            ?: throw AssertionError("A hammer shape in a downtrend must be classified as a hammer: $matches")
        assertEquals("Hammer", match.name)
        assertEquals(PatternCategory.BULLISH_REVERSAL, match.category)
        assertTrue(
            "Confidence is a normalized probability: ${match.confidence}",
            match.confidence in 0.5..1.0
        )
        assertEquals(
            "The same bars must always produce the same confidence",
            match.confidence,
            PatternClassifier.classify(downtrend() + hammer, PatternHorizon.M1)
                .first { it.id == "hammer" }.confidence,
            0.0
        )
    }

    @Test
    fun `given a bullish engulfing pair when classified then the body-engulfing rule fires on the five minute horizon`() {
        val pair = listOf(
            bar(101.0, 101.2, 99.8, 100.0),
            bar(99.6, 101.8, 99.4, 101.6) // opens below the prior close, closes above its open
        )
        val match = PatternClassifier.classify(downtrend() + pair, PatternHorizon.M5)
            .firstOrNull { it.id == "bullish-engulfing" }
            ?: throw AssertionError("Body engulfing must be detected on 5m")

        assertEquals(PatternCategory.BULLISH_REVERSAL, match.category)
    }

    @Test
    fun `given a shooting star at the end of an uptrend when classified then it is a bearish reversal`() {
        val star = bar(103.0, 105.0, 102.9, 103.4) // long upper wick, body at the bottom
        val match = PatternClassifier.classify(uptrend() + star, PatternHorizon.M1)
            .firstOrNull { it.id == "shooting-star" }
            ?: throw AssertionError("A long upper wick after a rise is a shooting star")

        assertEquals(PatternCategory.BEARISH_REVERSAL, match.category)
    }

    @Test
    fun `given a flat series with no structure when classified then nothing is predicted`() {
        val flat = (1..12).map { bar(100.0, 100.1, 99.9, 100.0) }

        for (horizon in PatternHorizon.entries) {
            assertEquals(
                "A doji-less flat tape has no formation; fabricating one would make the ledger lie",
                emptyList<PatternMatch>(),
                PatternClassifier.classify(flat, horizon)
            )
        }
    }

    @Test
    fun `given a double bottom when classified then it is only offered to the hourly horizon`() {
        // Two troughs at ~100 with a 103 neckline, then a close through it.
        val series = listOf(
            bar(106.0, 106.2, 105.0, 105.2),
            bar(105.2, 105.4, 102.0, 102.4),
            bar(102.4, 102.6, 100.0, 100.4),
            bar(100.4, 101.6, 100.1, 101.4),
            bar(101.4, 103.0, 101.2, 102.8),
            bar(102.8, 103.1, 101.8, 102.0),
            bar(102.0, 102.2, 100.1, 100.5),
            bar(100.5, 101.9, 100.2, 101.8),
            bar(101.8, 103.2, 101.6, 103.0),
            bar(103.0, 103.6, 102.8, 103.4),
            bar(103.4, 104.0, 103.2, 103.8)
        )

        assertTrue(
            "An hourly double bottom must be found on the 1h horizon",
            PatternClassifier.classify(series, PatternHorizon.H1).any { it.id == "double-bottom" }
        )
        assertFalse(
            "A 1-minute classifier must not claim an hourly double bottom",
            PatternClassifier.classify(series, PatternHorizon.M1).any { it.id == "double-bottom" }
        )
    }

    @Test
    fun `given matches for a horizon when converted to an evidence vector then it is aligned to the class list and sums to one`() {
        val classes = PatternTaxonomy.idsFor(PatternHorizon.M5)
        val matches = listOf(
            PatternMatch("bullish-engulfing", "Bullish Engulfing", PatternCategory.BULLISH_REVERSAL, 0.80),
            PatternMatch("piercing-line", "Piercing Line", PatternCategory.BULLISH_REVERSAL, 0.60)
        )

        val vector = PatternClassifier.evidenceVector(matches, classes)

        assertEquals("one feature per class", classes.size, vector.size)
        assertEquals(1.0, vector.sum(), 1e-9)
        assertTrue(vector[classes.indexOf("bullish-engulfing")] > vector[classes.indexOf("piercing-line")])
        assertEquals(0.0, vector[classes.indexOf("bearish-engulfing")], 0.0)
    }
}
