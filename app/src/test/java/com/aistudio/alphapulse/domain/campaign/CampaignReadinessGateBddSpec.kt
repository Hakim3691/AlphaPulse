package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BDD Guard Spec for §68 and Finalized Contract C-19:
 * Multi-State Readiness & >90.0% Per-Horizon Cone Hit-Rate Gate.
 */
class CampaignReadinessGateBddSpec {

    enum class CampaignLifecycleState {
        PLANNED,
        STAGED,
        SUPPRESSED
    }

    data class AlphaPulseReadinessTelemetry(
        val settled1mCount: Int,
        val settled5mCount: Int,
        val settled15mCount: Int,
        val settled30mCount: Int,
        val settled1hCount: Int,
        val readyEnsembleModels: Int,
        val totalEnsembleModels: Int,
        val readyTsfmDockModels: Int,
        val requiredTsfmDockModels: Int = 2,
        val horizonConeHitRatePct: Map<String, Double>
    ) {
        val isLtfReady: Boolean
            get() = settled1mCount >= 2 && settled5mCount >= 1

        val isMtfReady: Boolean
            get() = settled15mCount >= 2 && settled30mCount >= 1

        val isHtfReady: Boolean
            get() = settled30mCount >= 2 && settled1hCount >= 1

        val isEnsembleReady: Boolean
            get() = readyEnsembleModels == totalEnsembleModels && totalEnsembleModels >= 3

        val isTsfmDockReady: Boolean
            get() = readyTsfmDockModels == requiredTsfmDockModels
    }

    data class GateEvaluationResult(
        val nextState: CampaignLifecycleState,
        val canArmOrders: Boolean,
        val rejectionReasons: List<String>
    )

    class CampaignReadinessGate(
        private val minConeHitRateExclusivePct: Double = 90.0
    ) {
        fun evaluate(
            telemetry: AlphaPulseReadinessTelemetry,
            governingHorizons: List<String>
        ): GateEvaluationResult {
            val reasons = mutableListOf<String>()
            if (!telemetry.isLtfReady) reasons.add("LTF_NOT_READY")
            if (!telemetry.isMtfReady) reasons.add("MTF_NOT_READY")
            if (!telemetry.isHtfReady) reasons.add("HTF_NOT_READY")
            if (!telemetry.isEnsembleReady) reasons.add("ENSEMBLE_NOT_5_OF_5_READY")
            if (!telemetry.isTsfmDockReady) reasons.add("TSFM_DOCK_NOT_2_OF_2_READY")

            governingHorizons.forEach { horizon ->
                val hitRate = telemetry.horizonConeHitRatePct[horizon] ?: 0.0
                if (hitRate <= minConeHitRateExclusivePct) {
                    reasons.add("HORIZON_${horizon}_CONE_HIT_RATE_BELOW_OR_EQUAL_90_PCT($hitRate%)")
                }
            }

            return if (reasons.isEmpty()) {
                GateEvaluationResult(
                    nextState = CampaignLifecycleState.STAGED,
                    canArmOrders = true,
                    rejectionReasons = emptyList()
                )
            } else {
                GateEvaluationResult(
                    nextState = CampaignLifecycleState.PLANNED,
                    canArmOrders = false,
                    rejectionReasons = reasons
                )
            }
        }
    }

    private fun fullyReadyTelemetry(hitRates: Map<String, Double>) = AlphaPulseReadinessTelemetry(
        settled1mCount = 4,
        settled5mCount = 2,
        settled15mCount = 3,
        settled30mCount = 2,
        settled1hCount = 2,
        readyEnsembleModels = 5,
        totalEnsembleModels = 5,
        readyTsfmDockModels = 2,
        requiredTsfmDockModels = 2,
        horizonConeHitRatePct = hitRates
    )

    @Test
    fun `given LTF MTF HTF Ready 5 of 5 Ensemble Ready 2 of 2 TSFM Dock Ready and all governing horizons above 90 percent cone hit rate when evaluated then campaign transitions to Staged`() {
        val gate = CampaignReadinessGate()
        val telemetry = fullyReadyTelemetry(
            mapOf("1m" to 94.0, "5m" to 92.5, "15m" to 91.2, "30m" to 93.8, "1h" to 90.4)
        )

        val verdict = gate.evaluate(telemetry, governingHorizons = listOf("15m", "30m", "1h"))

        assertTrue(verdict.canArmOrders)
        assertEquals(CampaignLifecycleState.STAGED, verdict.nextState)
        assertTrue(verdict.rejectionReasons.isEmpty())
    }

    @Test
    fun `given all readiness badges active but 15m horizon cone hit rate is exactly 90_0 percent or lower when evaluated then campaign staging is blocked`() {
        val gate = CampaignReadinessGate()
        val telemetry = fullyReadyTelemetry(
            mapOf("1m" to 95.0, "5m" to 94.0, "15m" to 90.0, "30m" to 92.0, "1h" to 91.5)
        )

        val verdict = gate.evaluate(telemetry, governingHorizons = listOf("15m", "30m", "1h"))

        assertFalse(verdict.canArmOrders)
        assertEquals(CampaignLifecycleState.PLANNED, verdict.nextState)
        assertTrue(verdict.rejectionReasons.any { it.contains("HORIZON_15m_CONE_HIT_RATE_BELOW_OR_EQUAL_90_PCT") })
    }

    @Test
    fun `given cone hit rates above 90 percent but TSFM Model Dock is 1 of 2 or HTF Ready is false when evaluated then campaign staging is blocked`() {
        val gate = CampaignReadinessGate()
        val unreadyTelemetry = fullyReadyTelemetry(
            mapOf("15m" to 95.0, "30m" to 96.0, "1h" to 94.0)
        ).copy(settled1hCount = 0, readyTsfmDockModels = 1)

        val verdict = gate.evaluate(unreadyTelemetry, governingHorizons = listOf("15m", "30m", "1h"))

        assertFalse(verdict.canArmOrders)
        assertTrue(verdict.rejectionReasons.contains("HTF_NOT_READY"))
        assertTrue(verdict.rejectionReasons.contains("TSFM_DOCK_NOT_2_OF_2_READY"))
    }
}