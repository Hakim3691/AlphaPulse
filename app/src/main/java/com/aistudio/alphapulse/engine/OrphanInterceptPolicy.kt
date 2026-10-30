package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState

/**
 * Phase 1 (Spec §3 Orphaned Order OS Intercept): pure decision policy for what the app
 * must do when it is about to leave the foreground. Kept side-effect free so the
 * lifecycle trigger in MainActivity stays a thin forwarder and the rule is unit-testable.
 */
enum class OrphanInterceptDecision {
    /** Live credentials with resting orders: full-screen intercept is mandatory. */
    SHOW_INTERCEPT,

    /** Live credentials but nothing resting: wipe keys immediately (suspension policy). */
    WIPE_ONLY,

    /** No live session: nothing to orphan and nothing to wipe. */
    NO_ACTION
}

object OrphanInterceptPolicy {

    /**
     * A campaign holds exchange-side exposure or working orders if it is staged/partially
     * filled/active/reduced, or if any ladder slice is still resting on the book.
     */
    fun hasRestingOrLiveOrders(campaigns: List<Campaign>): Boolean = campaigns.any { campaign ->
        campaign.status == CampaignState.STAGED ||
            campaign.status == CampaignState.PARTIALLY_FILLED ||
            campaign.status == CampaignState.ACTIVE ||
            campaign.status == CampaignState.REDUCED ||
            campaign.entryLadder.any { it.isResting }
    }

    fun decide(hasActiveSession: Boolean, hasRestingOrders: Boolean): OrphanInterceptDecision =
        when {
            !hasActiveSession -> OrphanInterceptDecision.NO_ACTION
            hasRestingOrders -> OrphanInterceptDecision.SHOW_INTERCEPT
            else -> OrphanInterceptDecision.WIPE_ONLY
        }
}
