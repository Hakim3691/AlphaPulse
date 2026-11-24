package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-60 — the Thresholds surface states the configured limits and what the inspected coin is
 * actually judged by.
 *
 * Two numbers that differ only in wording are indistinguishable on a screenshot, so the
 * projection is pure and pinned here: each layer prints the configured floor and sample
 * floor, and beneath it the coin's own measured rate against them.
 *
 * Spec Ref: Phase 2 C-57/C-58/C-60
 */
class ThresholdPresentationBddSpec {

    @Test
    fun `given shipped defaults and an inspected coin when the rows are built then each layer prints its configured limits`() {
        val rows = ThresholdPresentation.rows(
            config = ThresholdConfig.DEFAULT,
            verdict = LayeredEvidenceGate.assess(
                price = listOf(LayeredEvidenceGate.price("15m", settled = 20, hits = 17)),
                strategy = LayeredEvidenceGate.strategy("BTCUSDT", settled = 5, hits = 5)
            )
        )

        assertEquals(EvidenceLayer.entries, rows.map { it.layer })
        rows.forEach { row ->
            assertEquals(row.layer.name, "85.0%", row.floorLabel)
        }
        assertEquals("20 fast / 4 slow", rows.first { it.layer == EvidenceLayer.PRICE }.samplesLabel)
        assertEquals("20 fast / 4 slow", rows.first { it.layer == EvidenceLayer.PATTERN }.samplesLabel)
        assertEquals("20 graded", rows.first { it.layer == EvidenceLayer.STRATEGY }.samplesLabel)
    }

    @Test
    fun `given an inspected coin with measured evidence when the rows are built then they state the effective values`() {
        val rows = ThresholdPresentation.rows(
            config = ThresholdConfig.DEFAULT,
            verdict = LayeredEvidenceGate.assess(
                price = listOf(LayeredEvidenceGate.price("15m", settled = 20, hits = 17)),
                strategy = LayeredEvidenceGate.strategy("BTCUSDT", settled = 5, hits = 5)
            )
        )

        val price = rows.first { it.layer == EvidenceLayer.PRICE }
        assertTrue(
            "The effective line must show the coin's own rate: ${price.effective}",
            price.effective.contains("15m") && price.effective.contains("85.0%")
        )
        val strategy = rows.first { it.layer == EvidenceLayer.STRATEGY }
        assertTrue(strategy.effective.contains("100.0%"))
    }

    @Test
    fun `given no verdict for the coin when the rows are built then each layer says what is missing rather than inventing a rate`() {
        val rows = ThresholdPresentation.rows(ThresholdConfig.DEFAULT, verdict = null)

        assertEquals(
            "no price horizon has settled",
            rows.first { it.layer == EvidenceLayer.PRICE }.effective
        )
        assertEquals(
            "no settled formations for this coin",
            rows.first { it.layer == EvidenceLayer.PATTERN }.effective
        )
        assertEquals(
            "no graded strategy calls for this coin",
            rows.first { it.layer == EvidenceLayer.STRATEGY }.effective
        )
    }

    @Test
    fun `given a configured floor when the rows are built then they print the configured value not a constant`() {
        val rows = ThresholdPresentation.rows(
            config = ThresholdConfig(floorPct = 92.0, minSettledSlow = 7),
            verdict = null
        )

        rows.forEach { assertEquals("92.0%", it.floorLabel) }
        assertEquals("20 fast / 7 slow", rows.first { it.layer == EvidenceLayer.PRICE }.samplesLabel)
    }
}
