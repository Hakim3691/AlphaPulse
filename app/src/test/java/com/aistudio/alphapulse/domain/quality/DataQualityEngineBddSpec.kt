package com.aistudio.alphapulse.domain.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the data-quality engine, specified as Given/When/Then.
 *
 * §35 asks for a single place that decides whether the feed can be trusted.
 * These tests pin that behaviour: a clean snapshot is healthy, each symptom is
 * named in the report, and the dangerous combinations are unusable rather than
 * merely "a bit worse".
 *
 * Spec Ref: §35 Data Quality Engine
 */
class DataQualityEngineBddSpec {

    private val now = 1_700_000_000_000L

    /** A snapshot with nothing wrong: fresh tick, sane spread, full book, models mounted. */
    private fun healthyInput() = DataQualityInput(
        lastTickTimestampMs = now - 1_000L,
        nowMs = now,
        bookLevels = 20,
        spreadBps = 2.0
    )

    // ------------------------------------------------------------- healthy feed

    @Test
    fun `given a fresh feed with a sane spread when assessed then the report is healthy and scores one hundred percent`() {
        val report = DataQualityEngine.evaluate(healthyInput())

        assertEquals(DataQualityStatus.HEALTHY, report.status)
        assertEquals(1.0, report.score, 1e-9)
        assertTrue("A healthy feed must be usable", report.isUsable)
        assertTrue("A healthy feed must be alertable", report.isFresh)
        assertTrue("Nothing should be reported", report.issues.isEmpty())
        assertEquals(1_000L, report.ageMs)
    }

    // ------------------------------------------------------------- stale data

    @Test
    fun `given a tick older than the five minute limit when assessed then the feed is stale and cannot raise an alert`() {
        val report = DataQualityEngine.evaluate(
            healthyInput().copy(lastTickTimestampMs = now - 6 * 60 * 1000L)
        )

        assertTrue(DataQualityIssue.STALE in report.issues)
        assertFalse("Stale data must not justify an alert", report.isFresh)
        assertTrue("The reason must state the age", report.reasons.any { it.contains("360 s") })
    }

    @Test
    fun `given a tick exactly on the staleness limit when assessed then it is still considered fresh`() {
        val report = DataQualityEngine.evaluate(
            healthyInput().copy(lastTickTimestampMs = now - DataQualityInput.DEFAULT_STALE_AFTER_MS)
        )

        assertFalse("The limit is inclusive, so exactly five minutes is still fresh",
            DataQualityIssue.STALE in report.issues)
    }

    @Test
    fun `given no tick has ever arrived when assessed then the feed is unusable rather than merely stale`() {
        val report = DataQualityEngine.evaluate(healthyInput().copy(lastTickTimestampMs = 0L))

        assertTrue(DataQualityIssue.NO_DATA in report.issues)
        assertEquals(
            "No data must be unusable: a forecast cannot be built from nothing",
            DataQualityStatus.UNUSABLE, report.status
        )
        assertEquals("Freshness collapses to zero", 0.0, report.freshness, 1e-9)
        assertEquals("Age is meaningless without a tick", 0L, report.ageMs)
    }

    // ------------------------------------------------------- structural issues

    @Test
    fun `given gaps and out of order messages when assessed then both are named and the score drops`() {
        val report = DataQualityEngine.evaluate(healthyInput().copy(gapCount = 2, outOfOrderCount = 1))

        assertTrue(DataQualityIssue.GAPS in report.issues)
        assertTrue(DataQualityIssue.OUT_OF_ORDER in report.issues)
        assertEquals("0.20 + 0.10 of trust is lost", 0.70, report.score, 1e-9)
        assertEquals(DataQualityStatus.DEGRADED, report.status)
        assertTrue("The report must be explainable", report.reasons.size == 2)
    }

    @Test
    fun `given a spread wider than the limit when assessed then the spread is flagged with both numbers`() {
        val report = DataQualityEngine.evaluate(healthyInput().copy(spreadBps = 40.0))

        assertTrue(DataQualityIssue.WIDE_SPREAD in report.issues)
        assertTrue(report.reasons.any { it.contains("40.0 bps") && it.contains("25.0 bps") })
    }

    @Test
    fun `given a book shallower than required when assessed then the book is flagged`() {
        val report = DataQualityEngine.evaluate(healthyInput().copy(bookLevels = 4))

        assertTrue(DataQualityIssue.SHALLOW_BOOK in report.issues)
        assertTrue(report.reasons.any { it.contains("4 level") })
    }

    // ---------------------------------------------------------- missing model

    @Test
    fun `given a required model is unavailable when assessed then the feed is unusable even though it is fresh`() {
        val report = DataQualityEngine.evaluate(healthyInput().copy(requiredModelAvailable = false))

        assertTrue(DataQualityIssue.MODEL_MISSING in report.issues)
        assertEquals(
            "An incomplete decision must never be published as healthy",
            DataQualityStatus.UNUSABLE, report.status
        )
    }

    // -------------------------------------------------------- score arithmetic

    @Test
    fun `given every symptom at once when assessed then the score floors at zero instead of going negative`() {
        val report = DataQualityEngine.evaluate(
            DataQualityInput(
                lastTickTimestampMs = 0L,
                nowMs = now,
                gapCount = 5,
                outOfOrderCount = 5,
                spreadBps = 500.0,
                bookLevels = 0,
                requiredModelAvailable = false
            )
        )

        assertEquals("Weights sum past 1.0, so the score must clamp", 0.0, report.score, 1e-9)
        assertEquals(DataQualityStatus.UNUSABLE, report.status)
        // NO_DATA and STALE describe the same field and are mutually exclusive:
        // with no tick at all there is no age to be stale about. Every other
        // symptom must still be reported.
        assertEquals(
            "Every other issue must be reported",
            DataQualityIssue.entries.size - 1,
            report.issues.size
        )
        assertTrue(DataQualityIssue.NO_DATA in report.issues)
        assertFalse(DataQualityIssue.STALE in report.issues)
    }

    @Test
    fun `given a degraded feed when summarised then the line states the score, the status and the first problem`() {
        val report = DataQualityEngine.evaluate(healthyInput().copy(gapCount = 1, spreadBps = 40.0))
        val summary = DataQualityEngine.summarize(report)

        // 0.20 for the gap plus 0.20 for the wide spread leaves 0.60.
        assertTrue("Summary must show the score: $summary", summary.contains("60%"))
        assertTrue("Summary must show the status: $summary", summary.contains("degraded"))
        assertTrue("Summary must name the first problem: $summary", summary.contains("gap"))
    }

    @Test
    fun `given a healthy feed when summarised then it says every check passed`() {
        val summary = DataQualityEngine.summarize(DataQualityEngine.evaluate(healthyInput()))

        assertTrue(summary, summary.contains("all checks passed"))
    }

    // ------------------------------------------------- freshness for the router

    @Test
    fun `given a feed two and a half minutes old when assessed then freshness is about half`() {
        val report = DataQualityEngine.evaluate(
            healthyInput().copy(lastTickTimestampMs = now - 150_000L)
        )

        assertEquals(
            "Freshness feeds DecisionInputs.dataFreshness",
            0.5, report.freshness, 1e-6
        )
    }
}
