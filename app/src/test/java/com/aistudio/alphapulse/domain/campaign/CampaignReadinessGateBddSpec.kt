package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BDD Guard Spec for §68 and Finalized Contract C-19:
 * multi-state readiness and the per-horizon cone hit-rate floor.
 *
 * The gate used to carry its own strict `> 90%` default while the layered gate that actually
 * stages boards proved price horizons at 85% (C-58). Two thresholds for one coin is a
 * contradiction, so the helper now reads the confirmed 85% floor from
 * [SignalEvidence.MIN_HIT_RATE_PCT] and compares inclusively. These scenarios exercise the
 * shipped class, not a copy of it, so the two gates cannot drift apart again.
 *
 * Spec Ref: §68 Readiness Gate, C-19, C-57, C-58
 */
class CampaignReadinessGateBddSpec {

    /** Pinned clock: these scenarios measure their evidence now (C-61). */
    private val NOW = 1_800_000_000_000L

    private fun fullyReadyTelemetry(
        hitRates: Map<String, Double>,
        settled1hCount: Int = 2,
        readyTsfmDockModels: Int = 2
    ) = AlphaPulseReadinessTelemetry(
        settled1mCount = 4,
        settled5mCount = 2,
        settled15mCount = 3,
        settled30mCount = 2,
        settled1hCount = settled1hCount,
        readyEnsembleModels = 5,
        totalEnsembleModels = 5,
        readyTsfmDockModels = readyTsfmDockModels,
        requiredTsfmDockModels = 2,
        horizonConeHitRatePct = hitRates,
        horizonConeMeasuredAt = hitRates.keys.associateWith { NOW },
        componentAvailability = mapOf(
            StagingComponent.TSFM to ComponentAvailability.READY,
            StagingComponent.NEURAL_LOB to ComponentAvailability.READY,
            StagingComponent.LIQUIDITY_HEATMAP to ComponentAvailability.READY,
            StagingComponent.ENSEMBLE to ComponentAvailability.READY
        )
    )

    @Test
    fun `given the readiness framework and every governing horizon at or above 85 percent when evaluated then the campaign transitions to Staged`() {
        val gate = CampaignReadinessGate()
        val telemetry = fullyReadyTelemetry(
            mapOf("1m" to 94.0, "5m" to 92.5, "15m" to 88.0, "30m" to 85.5, "1h" to 90.4)
        )

        val verdict = gate.evaluate(telemetry, governingHorizons = listOf("15m", "30m", "1h"), nowMs = NOW)

        assertTrue(verdict.rejectionReasons.toString(), verdict.canArmOrders)
        assertEquals(CampaignLifecycleState.STAGED, verdict.nextState)
        assertTrue(verdict.rejectionReasons.isEmpty())
    }

    @Test
    fun `given a governing horizon at exactly 85 percent when evaluated then the boundary counts as proven`() {
        val gate = CampaignReadinessGate()
        val telemetry = fullyReadyTelemetry(
            mapOf("15m" to 85.0, "30m" to 85.0, "1h" to 85.0)
        )

        val verdict = gate.evaluate(telemetry, governingHorizons = listOf("15m", "30m", "1h"), nowMs = NOW)

        assertTrue(verdict.rejectionReasons.toString(), verdict.canArmOrders)
        assertEquals(CampaignLifecycleState.STAGED, verdict.nextState)
    }

    @Test
    fun `given a governing horizon below 85 percent when evaluated then staging is blocked and the floor is named`() {
        val gate = CampaignReadinessGate()
        val telemetry = fullyReadyTelemetry(
            mapOf("15m" to 84.99, "30m" to 92.0, "1h" to 91.5)
        )

        val verdict = gate.evaluate(telemetry, governingHorizons = listOf("15m", "30m", "1h"), nowMs = NOW)

        assertFalse(verdict.canArmOrders)
        assertEquals(CampaignLifecycleState.PLANNED, verdict.nextState)
        assertTrue(
            verdict.rejectionReasons.toString(),
            verdict.rejectionReasons.any {
                it.contains("HORIZON_15m_CONE_HIT_RATE_BELOW_85_PCT")
            }
        )
    }

    @Test
    fun `given a governing horizon with no measurement when evaluated then it fails closed rather than passing`() {
        val gate = CampaignReadinessGate()
        val telemetry = fullyReadyTelemetry(mapOf("30m" to 95.0, "1h" to 95.0))

        val verdict = gate.evaluate(telemetry, governingHorizons = listOf("15m"), nowMs = NOW)

        assertFalse(verdict.canArmOrders)
        assertTrue(verdict.rejectionReasons.any { it.contains("HORIZON_15m") })
    }

    @Test
    fun `given proven hit rates but the readiness framework is incomplete when evaluated then staging is blocked`() {
        val gate = CampaignReadinessGate()
        val unreadyTelemetry = fullyReadyTelemetry(
            hitRates = mapOf("15m" to 95.0, "30m" to 96.0, "1h" to 94.0),
            settled1hCount = 0,
            readyTsfmDockModels = 1
        )

        val verdict = gate.evaluate(unreadyTelemetry, governingHorizons = listOf("15m", "30m", "1h"), nowMs = NOW)

        assertFalse(verdict.canArmOrders)
        assertTrue(verdict.rejectionReasons.contains("HTF_NOT_READY"))
        assertTrue(verdict.rejectionReasons.contains("TSFM_DOCK_NOT_2_OF_2_READY"))
    }

    @Test
    fun `given the readiness helper when its floor is read then it is the layered gate's confirmed 85 percent`() {
        assertEquals(SignalEvidence.MIN_HIT_RATE_PCT, CampaignReadinessGate().minConeHitRateInclusivePct, 1e-9)
        assertEquals(85.0, CampaignReadinessGate().minConeHitRateInclusivePct, 1e-9)
    }
}
