package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth

data class OverrideWarning(
    val title: String,
    val warningMessage: String,
    val structuralPlanValue: String,
    val userOverrideValue: String
)

class RiskEngine {

    /**
     * Checks if new campaign violates symbol or gross exposure caps
     */
    fun validateCampaignStaging(
        newCampaign: Campaign,
        existingCampaigns: List<Campaign>,
        riskEnvelope: RiskEnvelope,
        venueHealth: VenueHealth
    ): Pair<Boolean, String?> {
        if (riskEnvelope.isKillSwitchEngaged) {
            return Pair(false, "Emergency Kill-Switch is ENGAGED. Order placement locked.")
        }
        if (riskEnvelope.isDailyLossExceeded) {
            return Pair(false, "Daily loss limit (${riskEnvelope.dailyLossLimitR}R) hit. Automation paused.")
        }
        if (!venueHealth.isHealthy) {
            return Pair(false, "Venue or hardware health guardrail tripped (Check Diagnostics).")
        }

        // Section 20 Exposure Safety Rules:
        // Do not allow simultaneous full-size proactive long and full-size breakdown short on the same symbol
        if (newCampaign.family == CampaignFamily.BREAKDOWN_SHORT) {
            val hasFullSizeLong = existingCampaigns.any {
                it.asset == newCampaign.asset &&
                        it.family == CampaignFamily.SUPPORT_LONG &&
                        it.status == CampaignState.ACTIVE
            }
            if (hasFullSizeLong) {
                return Pair(false, "Conflict: Active full-size Support Long exists on ${newCampaign.asset}.")
            }
        }

        // Max gross exposure check
        if (riskEnvelope.currentGrossExposureUsd + newCampaign.sizeBudgetUsd > riskEnvelope.maxGrossExposureUsd) {
            return Pair(false, "Gross exposure cap ($${riskEnvelope.maxGrossExposureUsd}) would be exceeded.")
        }

        return Pair(true, null)
    }

    /**
     * Evaluates manual override against engine's structural calculations (Section 24)
     */
    fun checkManualOverrideGuardrails(
        engineCalculatedStop: Double,
        userStopPrice: Double,
        isLong: Boolean,
        engineCalculatedSize: Double,
        userSizeBudget: Double
    ): List<OverrideWarning> {
        val warnings = mutableListOf<OverrideWarning>()

        // Check stop placement relative to structural invalidation zone
        if (isLong && userStopPrice < engineCalculatedStop * 0.97) {
            warnings.add(
                OverrideWarning(
                    title = "Over-Extended Structural Stop Risk",
                    warningMessage = "Your override places the stop far below the structural invalidation zone, absorbing unnecessary adverse drawdown.",
                    structuralPlanValue = "$$engineCalculatedStop (Zone Edge)",
                    userOverrideValue = "$$userStopPrice (-3%+ Stretched)"
                )
            )
        } else if (!isLong && userStopPrice > engineCalculatedStop * 1.03) {
            warnings.add(
                OverrideWarning(
                    title = "Over-Extended Structural Stop Risk",
                    warningMessage = "Your override places the stop far above the supply invalidation band, holding through bullish acceptance.",
                    structuralPlanValue = "$$engineCalculatedStop (Zone Edge)",
                    userOverrideValue = "$$userStopPrice (+3%+ Stretched)"
                )
            )
        }

        // Check size budget override
        if (userSizeBudget > engineCalculatedSize * 1.5) {
            warnings.add(
                OverrideWarning(
                    title = "Excessive Position Sizing Allocation",
                    warningMessage = "Allocating >150% of the regime-approved size budget violates symbol portfolio risk caps.",
                    structuralPlanValue = "$$engineCalculatedSize (Regime Cap)",
                    userOverrideValue = "$$userSizeBudget (Over-Sized)"
                )
            )
        }

        return warnings
    }

    /**
     * Calculates worst-case concurrent stop-out across all staged/active campaigns
     */
    fun computeWorstCaseStopOutR(campaigns: List<Campaign>, accountEquity: Double): Double {
        val activeNotional = campaigns.filter { it.status == CampaignState.STAGED || it.status == CampaignState.ACTIVE }
            .sumOf { it.sizeBudgetUsd }
        val worstLoss = activeNotional * 0.025 // assuming ~2.5% stop distance
        val oneR = accountEquity * 0.005 // 0.5% = 1R
        return Math.round((worstLoss / oneR) * 100.0) / 100.0
    }
}
