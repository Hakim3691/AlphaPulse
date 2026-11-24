package com.aistudio.alphapulse.domain.campaign

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-60 — one configured floor, read by every consumer that used to carry its own copy.
 *
 * The 85% floor and the sample floors were constants in `SignalEvidence`, re-read by
 * `LayeredEvidence` and defaulted by `CampaignReadinessGate`. Three copies of one number
 * cannot be tuned: raising the bar for a quiet market meant editing all three and hoping
 * none of them drifted. These scenarios pin the single source: install a configuration once
 * and every consumer's verdict moves with it, without a second hardcoded copy anywhere.
 *
 * Spec Ref: Phase 2 C-57/C-58/C-60, §22, §67
 */
class ThresholdConfigBddSpec {

    /** Pinned clock: these scenarios measure their evidence now (C-61). */
    private val NOW = 1_800_000_000_000L

    @After
    fun restoreShippedDefaults() {
        Thresholds.install(ThresholdConfig.DEFAULT)
    }

    private fun telemetry(hitRate: Double) = AlphaPulseReadinessTelemetry(
        settled1mCount = 5,
        settled5mCount = 5,
        settled15mCount = 5,
        settled30mCount = 5,
        settled1hCount = 5,
        readyEnsembleModels = 5,
        totalEnsembleModels = 5,
        readyTsfmDockModels = 2,
        requiredTsfmDockModels = 2,
        horizonConeHitRatePct = mapOf("15m" to hitRate),
        horizonConeMeasuredAt = mapOf("15m" to NOW)
    )

    @Test
    fun `given no configuration has been changed when the defaults are read then they are the shipped 85 percent and twenty samples`() {
        val config = Thresholds.current()

        assertEquals(85.0, config.floorPct, 1e-9)
        assertEquals(20, config.minSettledFast)
        assertEquals(4, config.minSettledSlow)
        assertEquals(20, config.minSettledStrategy)
        assertEquals("85.0%", config.describeFloor())
    }

    @Test
    fun `given a horizon exactly at the configured floor when each consumer reads it then all three qualify it`() {
        Thresholds.install(ThresholdConfig(floorPct = 85.0))

        // 17 of 20 settled is exactly 85.0%.
        assertTrue(SignalEvidence.isQualified("15m", settled = 20, hits = 17, measuredAt = NOW, nowMs = NOW))
        assertTrue(
            LayeredEvidenceGate.assess(
                price = listOf(
                    LayeredEvidenceGate.price("15m", settled = 20, hits = 17, measuredAt = NOW)
                ),
                nowMs = NOW
            ).price.single().qualified
        )
        val gate = CampaignReadinessGate()
        assertEquals(
            "Every consumer must read the installed floor",
            85.0,
            gate.minConeHitRateInclusivePct,
            1e-9
        )
    }

    @Test
    fun `given one settled row below the configured floor when each consumer reads it then all three refuse it`() {
        Thresholds.install(ThresholdConfig(floorPct = 85.0))

        // 16 of 20 is 80.0%.
        assertFalse(SignalEvidence.isQualified("15m", settled = 20, hits = 16, measuredAt = NOW, nowMs = NOW))
        assertFalse(
            LayeredEvidenceGate.assess(
                price = listOf(
                    LayeredEvidenceGate.price("15m", settled = 20, hits = 16, measuredAt = NOW)
                ),
                nowMs = NOW
            ).price.single().qualified
        )
        assertTrue(
            CampaignReadinessGate().evaluate(telemetry(80.0), listOf("15m"), nowMs = NOW)
                .rejectionReasons.any { it.contains("HORIZON_15m") }
        )
    }

    @Test
    fun `given the floor is raised once when the same sample is re-read then every consumer refuses it without a second edit`() {
        val settled = 20
        val hits = 17 // 85.0%: proven at the shipped floor...
        assertTrue(SignalEvidence.isQualified("15m", settled, hits, measuredAt = NOW, nowMs = NOW))

        Thresholds.install(ThresholdConfig(floorPct = 90.0))

        // ...and the same rows are no longer evidence anywhere.
        assertFalse(SignalEvidence.isQualified("15m", settled, hits, measuredAt = NOW, nowMs = NOW))
        val restamped = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("15m", settled, hits, measuredAt = NOW)),
            pattern = listOf(LayeredEvidenceGate.pattern("15m", settled, hits, measuredAt = NOW)),
            nowMs = NOW
        )
        assertFalse(restamped.price.single().qualified)
        assertFalse(restamped.pattern.single().qualified)
        val reasons = CampaignReadinessGate().evaluate(telemetry(85.0), listOf("15m"), nowMs = NOW).rejectionReasons
        assertTrue(
            "The refusal must name the configured floor, not a hardcoded 85: $reasons",
            reasons.any { it.contains("BELOW_90_PCT") }
        )
    }

    @Test
    fun `given a configured fast sample floor when a horizon is graded then it uses that count instead of the constant`() {
        Thresholds.install(ThresholdConfig(minSettledFast = 30, minSettledSlow = 6))

        assertEquals(30, SignalEvidence.minSettledFor("5m"))
        assertEquals(6, SignalEvidence.minSettledFor("15m"))
        assertFalse(
            "29 settled is short of the configured 30",
            SignalEvidence.isQualified("5m", 29, 29, measuredAt = NOW, nowMs = NOW)
        )
        assertTrue(SignalEvidence.isQualified("5m", 30, 30, measuredAt = NOW, nowMs = NOW))
    }

    @Test
    fun `given a configured strategy sample floor when the strategy layer is built then it uses that count`() {
        Thresholds.install(ThresholdConfig(minSettledStrategy = 30))

        val strategy = LayeredEvidenceGate.assess(
            price = emptyList(),
            strategy = LayeredEvidenceGate.strategy("BTCUSDT", settled = 25, hits = 25, measuredAt = NOW),
            nowMs = NOW
        ).strategy!!
        assertEquals(30, strategy.minSettled)
        assertFalse("25 graded calls is short of the configured 30", strategy.qualified)

        Thresholds.install(ThresholdConfig(minSettledStrategy = 20))
        assertTrue(
            LayeredEvidenceGate.assess(
                price = emptyList(),
                strategy = LayeredEvidenceGate.strategy("BTCUSDT", 25, 25, measuredAt = NOW),
                nowMs = NOW
            ).strategy!!.qualified
        )
    }

    @Test
    fun `given a floor outside fifty to ninety-five when the candidate is validated then it is rejected with the range in the reason`() {
        for (outOfRange in listOf(120.0, 49.9, -1.0, Double.NaN)) {
            val verdict = ThresholdConfig(floorPct = outOfRange).validate()
            assertFalse("$outOfRange% must not be accepted", verdict.accepted)
            assertNotNull(verdict.rejection)
            assertTrue(
                "The reason must state the range: ${verdict.rejection}",
                verdict.rejection!!.contains("50") && verdict.rejection.contains("95")
            )
        }
        assertTrue(ThresholdConfig(floorPct = 50.0).validate().accepted)
        assertTrue(ThresholdConfig(floorPct = 95.0).validate().accepted)
    }

    @Test
    fun `given a zero price sample floor when the candidate is validated then PRICE would be unproven but unblocking and the reason says so`() {
        for (candidate in listOf(
            ThresholdConfig(minSettledFast = 0),
            ThresholdConfig(minSettledSlow = 0)
        )) {
            val verdict = candidate.validate()
            assertFalse(verdict.accepted)
            assertTrue(
                "The reason must name PRICE: ${verdict.rejection}",
                verdict.rejection!!.contains("PRICE_UNPROVEN_BUT_UNBLOCKING")
            )
        }
    }

    @Test
    fun `given a zero strategy sample floor when the candidate is validated then it is rejected by name`() {
        val verdict = ThresholdConfig(minSettledStrategy = 0).validate()

        assertFalse(verdict.accepted)
        assertTrue(verdict.rejection!!.contains("STRATEGY_SAMPLE_FLOOR_BELOW_1"))
    }

    @Test
    fun `given changed thresholds when they are reset then exactly 85 point 0 percent and 20 samples return`() {
        Thresholds.install(
            ThresholdConfig(
                floorPct = 92.5,
                minSettledFast = 33,
                minSettledSlow = 9,
                minSettledStrategy = 41
            )
        )

        Thresholds.reset()

        val config = Thresholds.current()
        assertEquals(85.0, config.floorPct, 1e-9)
        assertEquals(20, config.minSettledFast)
        assertEquals(4, config.minSettledSlow)
        assertEquals(20, config.minSettledStrategy)
        assertEquals("85.0%", config.describeFloor())
        assertEquals(ThresholdConfig.DEFAULT, config)
    }

    @Test
    fun `given a floor to clamp when the operator steps past the range then the candidate lands on the boundary`() {
        assertEquals(50.0, ThresholdConfig.clampFloor(12.0), 1e-9)
        assertEquals(95.0, ThresholdConfig.clampFloor(140.0), 1e-9)
        assertEquals(85.0, ThresholdConfig.clampFloor(85.0), 1e-9)
    }
}
