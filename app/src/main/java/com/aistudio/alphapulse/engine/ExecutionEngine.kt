package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.domain.campaign.StagingPreconditionVerdict
import com.aistudio.alphapulse.engine.proactive.PreLaunchVerdict

/** 
 * Phase 4 (Spec §36, §22, §25): the execution gate between the Campaign Library and
 * order routing.
 *
 * [gateStaging] runs the previously-dead [RiskEngine.validateCampaignStaging] on every
 * board (kill switch, daily loss stop, venue health, side conflict, gross exposure) and
 * then applies the operator's ExecutionMode:
 *  - SHADOW: campaigns stay PLANNED with no resting slices — alerts only, no orders.
 *  - PAPER: boards rest internally and fill against the live price via
 *    [simulatePaperFills] — no exchange calls.
 *  - CAPPED_LIVE: real routing, but each board's notional is clipped to
 *    [RiskEnvelope.cappedNotionalPerCampaignUsd].
 *  - SCALED_LIVE: full-size routing; requires an active signed session like CAPPED.
 *
 * Live modes without an active session hold boards as PLANNED rather than silently
 * dropping them, so the operator sees exactly what is waiting for credentials.
 */
class ExecutionEngine(private val riskEngine: RiskEngine) {

    data class GatedCampaign(val campaign: Campaign, val note: String?)

    /**
     * @param stagingPrecondition §22's four-component verdict for this board's asset.
     *   Required, not defaulted: every staging path has to state the readiness evidence
     *   it actually observed rather than inheriting a silent pass.
     */
    fun gateStaging(
        campaigns: List<Campaign>,
        mode: ExecutionMode,
        envelope: RiskEnvelope,
        venueHealth: VenueHealth,
        hasActiveSession: Boolean,
        stagingPrecondition: StagingPreconditionVerdict,
        preLaunchAcceptance: PreLaunchVerdict? = null
    ): List<GatedCampaign> = campaigns.map { c ->
        gateCampaign(
            c, mode, envelope, venueHealth, hasActiveSession, campaigns,
            stagingPrecondition, preLaunchAcceptance
        )
    }

    private fun gateCampaign(
        board: Campaign,
        mode: ExecutionMode,
        envelope: RiskEnvelope,
        venueHealth: VenueHealth,
        hasActiveSession: Boolean,
        existing: List<Campaign>,
        stagingPrecondition: StagingPreconditionVerdict,
        preLaunchAcceptance: PreLaunchVerdict?
    ): GatedCampaign {
        // Only this gate may put a reason on the card, so a board that no longer holds
        // never keeps the previous cycle's sentence.
        val campaign = board.copy(gateNote = null)
        // §22 step 7: stage only after all preconditions and account constraints pass.
        val (allowed, reason) = riskEngine.validateCampaignStaging(
            campaign, existing, envelope, venueHealth
        )
        if (!allowed) {
            return GatedCampaign(campaign.copy(status = CampaignState.SUPPRESSED), reason)
        }
        // §22: the four readiness components are a precondition on the order plan itself,
        // so an unmet component holds the board as a visible plan with no resting slices
        // instead of letting it arm on half-observed evidence.
        if (!stagingPrecondition.canStage) {
            return GatedCampaign(
                campaign.copy(
                    status = CampaignState.PLANNED,
                    entryLadder = campaign.entryLadder.map { it.copy(isResting = false) },
                    gateNote = stagingPrecondition.displayNote()
                ),
                stagingPrecondition.note()
            )
        }
        return when (mode) {
            ExecutionMode.SHADOW -> GatedCampaign(
                campaign.copy(
                    status = CampaignState.PLANNED,
                    entryLadder = campaign.entryLadder.map { it.copy(isResting = false) }
                ),
                "Shadow mode: board generated as plan only, no orders routed"
            )
            ExecutionMode.PAPER -> GatedCampaign(
                campaign.copy(status = CampaignState.STAGED),
                "Paper mode: board rests internally against live book"
            )
            ExecutionMode.CAPPED_LIVE -> {
                if (!hasActiveSession) {
                    GatedCampaign(
                        campaign.copy(status = CampaignState.PLANNED),
                        "Capped live requires an active session; board held as plan"
                    )
                } else {
                    val cap = envelope.cappedNotionalPerCampaignUsd
                    val budget = minOf(campaign.sizeBudgetUsd, cap)
                    val scale = if (campaign.sizeBudgetUsd > 0.0) budget / campaign.sizeBudgetUsd else 1.0
                    GatedCampaign(
                        campaign.copy(
                            sizeBudgetUsd = budget,
                            entryLadder = campaign.entryLadder.map { it.copy(qty = it.qty * scale, notionalUsd = it.notionalUsd * scale)
                        }),
                        "Capped live: board notional clipped to $cap"
                    )
                }
            }
            ExecutionMode.SCALED_LIVE -> {
                if (!hasActiveSession) {
                    GatedCampaign(
                        campaign.copy(status = CampaignState.PLANNED),
                        "Scaled live requires an active session; board held as plan"
                    )
                } else if (preLaunchAcceptance?.unlocked != true) {
                    // §38: Scaled Live stays locked until the mandatory pre-launch
                    // evidence passes; a missing verdict is a locked gate, not a pass.
                    GatedCampaign(
                        campaign.copy(status = CampaignState.PLANNED),
                        preLaunchAcceptance?.summary
                            ?: "Scaled live locked until the §38 pre-launch acceptance gate passes"
                    )
                } else {
                    GatedCampaign(campaign, "Scaled live: full board routed")
                }
            }
        }
    }

    // ---------------------------------------------------------------- paper fills

    /** §36 Paper mode: internal fill simulation of resting slices against live price. */
    fun isPaperFillTriggered(slice: LadderSlice, campaign: Campaign, lastPrice: Double): Boolean =
        when (slice.orderType) {
            OrderType.PASSIVE_LIMIT -> if (campaign.isLong) lastPrice <= slice.price else lastPrice >= slice.price
            OrderType.STOP_LIMIT, OrderType.STOP_MARKET -> if (campaign.isLong) lastPrice >= slice.price else lastPrice <= slice.price
        }

    fun simulatePaperFills(campaign: Campaign, lastPrice: Double): Campaign {
        val restable = campaign.status == CampaignState.STAGED ||
            campaign.status == CampaignState.PARTIALLY_FILLED
        if (!restable) return campaign
        var changed = false
        val ladder = campaign.entryLadder.map { slice ->
            if (slice.isResting && !slice.isFilled && isPaperFillTriggered(slice, campaign, lastPrice)) {
                changed = true
                slice.copy(isFilled = true, isResting = false)
            } else slice
        }
        if (!changed) return campaign
        return campaign.copy(entryLadder = ladder, status = statusAfterFills(ladder))
    }

    /** §19 state transitions from fill events: partial -> partially filled, full -> active. */
    fun statusAfterFills(ladder: List<LadderSlice>): CampaignState = when {
        ladder.none { it.isFilled } -> CampaignState.STAGED
        ladder.all { it.isFilled } -> CampaignState.ACTIVE
        else -> CampaignState.PARTIALLY_FILLED
    }

    // ---------------------------------------------------------------- reconciliation

    /** Client order id encoding `<campaignId>_<sliceIndex>` for deterministic routing. */
    fun clientOrderId(campaignId: String, sliceIndex: Int): String =
        "${campaignId}_$sliceIndex".take(36)

}