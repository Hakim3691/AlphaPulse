package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-58 — the Guardrails tab must say which of PRICE / PATTERN / STRATEGY are live.
 *
 * The layered gate decides what may stage, but the operator could only read that from a
 * board's gate note. The guardrail tab is where production limits are read, so it has to
 * state the three layers for the coin under inspection: which are active inputs, and which
 * are still waiting on their own 85% floor.
 *
 * The readout is a pure projection of the verdict so the wording can be pinned without a
 * database, a clock or Compose.
 *
 * Spec Ref: Phase 2 C-58, §67, §73, §74
 */
class LayeredEvidencePresentationBddSpec {

    /** Pinned clock: these scenarios measure their evidence now (C-61). */
    private val NOW = 1_800_000_000_000L

    @Test
    fun `given no verdict when the readout is built then every layer is inactive and price is blocking`() {
        val rows = LayeredEvidencePresentation.rows(null)

        assertEquals(EvidenceLayer.entries.toList(), rows.map { it.layer })
        assertFalse(rows.any { it.active })
        assertEquals(LayeredEvidencePresentation.BLOCKING, price(rows).statusLabel)
        assertEquals(LayeredEvidencePresentation.INACTIVE, pattern(rows).statusLabel)
        assertEquals(LayeredEvidencePresentation.INACTIVE, strategy(rows).statusLabel)
        assertTrue(price(rows).detail.contains("no price horizon"))
        assertTrue(strategy(rows).detail.contains("no graded strategy"))
    }

    @Test
    fun `given only a proven price horizon when the readout is built then price is active and the additive layers are not`() {
        val verdict = LayeredEvidenceGate.assess(nowMs = NOW, 
            price = listOf(LayeredEvidenceGate.price("1h", settled = 8, hits = 7, measuredAt = NOW))
        )

        val rows = LayeredEvidencePresentation.rows(verdict)

        assertTrue(price(rows).active)
        assertEquals(LayeredEvidencePresentation.ACTIVE, price(rows).statusLabel)
        assertTrue(price(rows).detail.contains("1h"))
        assertFalse(pattern(rows).active)
        assertFalse(strategy(rows).active)
    }

    @Test
    fun `given an unproven price layer and a proven pattern horizon when the readout is built then only pattern is active`() {
        val verdict = LayeredEvidenceGate.assess(nowMs = NOW, 
            price = listOf(LayeredEvidenceGate.price("1h", settled = 8, hits = 4, measuredAt = NOW)),
            pattern = listOf(LayeredEvidenceGate.pattern("15m", settled = 8, hits = 7, measuredAt = NOW))
        )

        val rows = LayeredEvidencePresentation.rows(verdict)

        assertFalse(price(rows).active)
        assertEquals(LayeredEvidencePresentation.BLOCKING, price(rows).statusLabel)
        assertTrue(pattern(rows).active)
        assertEquals(LayeredEvidencePresentation.ACTIVE, pattern(rows).statusLabel)
        assertTrue(pattern(rows).detail.contains("15m"))
    }

    @Test
    fun `given all three layers proven when the readout is built then every layer is active`() {
        val verdict = LayeredEvidenceGate.assess(nowMs = NOW, 
            price = listOf(LayeredEvidenceGate.price("1h", settled = 8, hits = 8, measuredAt = NOW)),
            pattern = listOf(LayeredEvidenceGate.pattern("15m", settled = 8, hits = 8, measuredAt = NOW)),
            strategy = LayeredEvidenceGate.strategy("SOLUSDT", settled = 40, hits = 36, measuredAt = NOW)
        )

        val rows = LayeredEvidencePresentation.rows(verdict)

        assertTrue(rows.all { it.active })
        assertTrue(rows.all { it.statusLabel == LayeredEvidencePresentation.ACTIVE })
        assertTrue(strategy(rows).detail.contains("40"))
    }

    @Test
    fun `given a strategy layer under its sample floor when the readout is built then it states the sample it still needs`() {
        val verdict = LayeredEvidenceGate.assess(nowMs = NOW, 
            price = listOf(LayeredEvidenceGate.price("5m", settled = 24, hits = 22, measuredAt = NOW)),
            strategy = LayeredEvidenceGate.strategy("SOLUSDT", settled = 12, hits = 11, measuredAt = NOW)
        )

        val rows = LayeredEvidencePresentation.rows(verdict)

        assertTrue(price(rows).active)
        assertFalse(strategy(rows).active)
        assertTrue(strategy(rows).detail.contains("12"))
        assertTrue(strategy(rows).detail.contains("20"))
    }

    private fun price(rows: List<LayerReadout>) = rows.first { it.layer == EvidenceLayer.PRICE }

    private fun pattern(rows: List<LayerReadout>) = rows.first { it.layer == EvidenceLayer.PATTERN }

    private fun strategy(rows: List<LayerReadout>) = rows.first { it.layer == EvidenceLayer.STRATEGY }
}
