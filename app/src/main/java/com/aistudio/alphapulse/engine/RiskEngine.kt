package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import kotlin.math.abs

data class OverrideWarning(
    val title: String,
    val warningMessage: String,
    val structuralPlanValue: String,
    val userOverrideValue: String
)

class RiskEngine {

    companion object {
        /** §23: at most two boards may be live on one asset at a time. */
        const val MAX_CONCURRENT_PER_ASSET = 2

        /** §23 fallback stop distance when a campaign carries no usable stop. */
        const val FALLBACK_STOP_DISTANCE_PCT = 0.025

        /** §23: 1R is the production 0.50% account-risk unit. */
        const val ONE_R_FRACTION = 0.005
    }

    /** Deterministic admission order: priority first, then the earlier id. */
    private fun outranks(peer: Campaign, candidate: Campaign): Boolean =
        peer.priorityScore > candidate.priorityScore ||
            (peer.priorityScore == candidate.priorityScore && peer.id < candidate.id)

    /** The live-board states that consume a symbol's concurrency budget. */
    private fun isLive(campaign: Campaign): Boolean = when (campaign.status) {
        CampaignState.STAGED,
        CampaignState.PARTIALLY_FILLED,
        CampaignState.ACTIVE,
        CampaignState.REDUCED -> true
        else -> false
    }

    /**
     * Checks if new campaign violates symbol or gross exposure caps
     */
    /**
     * @param mode C-63: venue and hardware health block live routing, not planning. The
     *   default keeps every existing planning caller on the same behaviour it had.
     */
    fun validateCampaignStaging(
        newCampaign: Campaign,
        existingCampaigns: List<Campaign>,
        riskEnvelope: RiskEnvelope,
        venueHealth: VenueHealth,
        mode: ExecutionMode = ExecutionMode.PAPER
    ): Pair<Boolean, String?> {
        if (riskEnvelope.isKillSwitchEngaged) {
            return Pair(false, "Emergency Kill-Switch is ENGAGED. Order placement locked.")
        }
        if (riskEnvelope.isDailyLossExceeded) {
            return Pair(false, "Daily loss limit (${riskEnvelope.dailyLossLimitR}R) hit. Automation paused.")
        }
        // C-63: only a genuine block refuses here. Latency, power and memory are
        // advisories in the planning modes, because those modes route nothing.
        val venue = VenueHealthVerdict.assess(venueHealth, mode)
        if (!venue.canStage) {
            return Pair(false, venue.hardBlock ?: "Venue health guardrail tripped (Check Diagnostics).")
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

        // §23 admission is deterministic and independent of list order: a peer holds the
        // symbol's slot only when it outranks the candidate (higher priority, or the
        // same priority with the earlier id). Exactly one board per side therefore
        // survives a whole re-planned book, and the winner does not depend on the order
        // in which the book happened to be built.
        val liveOnAsset = existingCampaigns.filter { it.id != newCampaign.id && it.asset == newCampaign.asset }
            .filter { isLive(it) }
        val outranking = liveOnAsset.filter { outranks(it, newCampaign) }

        // §23 per-asset concurrency cap: two live boards is the ceiling for one symbol.
        if (outranking.size >= MAX_CONCURRENT_PER_ASSET) {
            return Pair(
                false,
                "Per-asset concurrency cap: ${newCampaign.asset} already has " +
                    "${outranking.size} live boards (max $MAX_CONCURRENT_PER_ASSET)."
            )
        }

        // §23 single owner per side: one live board owns a symbol's long exposure (or
        // its short exposure). A second same-side board is a duplicate of the same
        // structural idea and would double the size the role budget already set.
        if (newCampaign.status == CampaignState.STAGED || newCampaign.status == CampaignState.PLANNED) {
            val sameSide = outranking.firstOrNull { it.isLong == newCampaign.isLong }
            if (sameSide != null) {
                val side = if (newCampaign.isLong) "long" else "short"
                return Pair(
                    false,
                    "Single owner per side: ${newCampaign.asset} already has a live $side board (${sameSide.id})."
                )
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
     * §23 worst-case concurrent stop-out, netted per symbol.
     *
     * A long and a short of the same size on one symbol are a hedge: if both stop, the
     * two moves offset rather than add. The figure therefore nets the long and short
     * budgets per symbol before applying that symbol's widest stop distance, and reports
     * the total alongside the per-asset breakdown the dashboard reads.
     */
    fun computeStopOutRisk(campaigns: List<Campaign>, accountEquity: Double): StopOutRisk {
        val live = campaigns.filter { isLive(it) }
        if (live.isEmpty() || accountEquity <= 0.0) return StopOutRisk(0.0, emptyMap())
        val oneR = accountEquity * ONE_R_FRACTION

        val perAsset = live.groupBy { it.asset }.mapValues { (_, boards) ->
            val netNotional = boards.sumOf { if (it.isLong) it.sizeBudgetUsd else -it.sizeBudgetUsd }
            val distancePct = boards.mapNotNull { stopDistancePct(it) }.maxOrNull()
                ?: FALLBACK_STOP_DISTANCE_PCT
            round2(abs(netNotional) * distancePct / oneR)
        }
        return StopOutRisk(
            worstCaseR = perAsset.values.maxOrNull() ?: 0.0,
            perAssetR = perAsset
        )
    }

    /** The shipped single-number view of [computeStopOutRisk] for existing callers. */
    fun computeWorstCaseStopOutR(campaigns: List<Campaign>, accountEquity: Double): Double =
        computeStopOutRisk(campaigns, accountEquity).worstCaseR

    /**
     * How far the board's ladder sits from its hard stop, as a fraction of its average
     * entry. Null when neither the ladder nor the stop carries a usable price.
     */
    private fun stopDistancePct(campaign: Campaign): Double? {
        val notional = campaign.entryLadder.sumOf { it.notionalUsd }
        val averageEntry = if (notional > 0.0) {
            campaign.entryLadder.sumOf { it.price * it.notionalUsd } / notional
        } else {
            campaign.entryLadder.map { it.price }.filter { it > 0.0 }.average().takeIf { !it.isNaN() }
        }
        val entry = averageEntry?.takeIf { it > 0.0 } ?: return null
        val stop = campaign.stopLogic.hardStopPrice
        if (stop <= 0.0) return null
        return abs(entry - stop) / entry
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}

/** §23's netted worst-case stop-out: the total and the per-symbol contribution. */
data class StopOutRisk(
    val worstCaseR: Double,
    val perAssetR: Map<String, Double>
)
