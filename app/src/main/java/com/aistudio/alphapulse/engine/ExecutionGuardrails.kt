package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.VenueHealth

/**
 * Phase 4 (Spec §33 Operational Guardrails): pure decision functions wired into the
 * execution path by MainViewModel. Kept side-effect free so guardrail behavior is
 * unit-testable without network or Android bindings (Spec §37).
 */
object ExecutionGuardrails {

    /** Consecutive order rejects before the venue is paused for that asset (§33). */
    const val REJECT_PAUSE_THRESHOLD = 3

    /** Relative mismatch tolerated between expected and exchange position (§33). */
    const val POSITION_MISMATCH_TOLERANCE = 0.05

    /** §33 "Data feed stale": suppress staging and cancel nonessential resting orders. */
    fun staleFeedAction(venueHealth: VenueHealth): StaleFeedAction =
        if (venueHealth.isFeedStale) StaleFeedAction.SUPPRESS_AND_TRIM else StaleFeedAction.NONE

    enum class StaleFeedAction { NONE, SUPPRESS_AND_TRIM }

    /**
     * Cancels nonessential resting orders (secondary/defensive boards keep their
     * primary-expression counterparts alive; everything unfilled on them rests).
     */
    fun trimNonessentialRestingOrders(campaigns: List<Campaign>): List<Campaign> =
        campaigns.map { campaign ->
            val nonessential = campaign.role == BoardRole.SECONDARY ||
                campaign.role == BoardRole.DEFENSIVE
            if (nonessential && campaign.entryLadder.any { it.isResting && !it.isFilled }) {
                campaign.copy(
                    entryLadder = campaign.entryLadder.map {
                        if (it.isFilled) it else it.copy(isResting = false)
                    }
                )
            } else campaign
        }

    /**
     * §33 "Spread exceeds volatility-adjusted limit": passive staging disabled. The
     * spread limit widens with volatility: 8 bps base, scaled by the ATR ratio.
     */
    fun isSpreadBlowout(asset: CryptoAsset): Boolean {
        if (asset.lastPrice <= 0.0) return true
        val atrPct = asset.atr5m / asset.lastPrice * 10_000.0 // ATR in bps
        val limitBps = (8.0 + atrPct * 0.25).coerceAtLeast(8.0)
        return asset.orderBookSpreadPct * 100.0 > limitBps
    }

    /** §33 "Order rejects exceed threshold": pause venue for that asset. */
    fun shouldPauseVenue(consecutiveRejects: Int): Boolean =
        consecutiveRejects >= REJECT_PAUSE_THRESHOLD

    enum class PositionMismatchAction { NONE, FLATTEN_AND_LOCK }

    /**
     * §33 "Unexpected position mismatch": flatten the symbol and lock until
     * reconciliation succeeds. Compares the engine's expected signed position
     * (filled notional / price, long positive) against the exchange amount.
     */
    fun positionMismatchAction(
        expectedQty: Double,
        exchangeQty: Double,
        tolerancePct: Double = POSITION_MISMATCH_TOLERANCE
    ): PositionMismatchAction {
        val expectedAbs = Math.abs(expectedQty)
        val exchangeAbs = Math.abs(exchangeQty)
        return if (expectedAbs < 1e-9 && exchangeAbs < 1e-9) {
            PositionMismatchAction.NONE
        } else {
            val reference = maxOf(expectedAbs, exchangeAbs, 1e-9)
            if (Math.abs(expectedAbs - exchangeAbs) / reference > tolerancePct) {
                PositionMismatchAction.FLATTEN_AND_LOCK
            } else PositionMismatchAction.NONE
        }
    }

    /** Expected signed position for a set of campaigns on one symbol (long positive). */
    fun expectedPositionQty(campaigns: List<Campaign>, lastPrice: Double): Double {
        if (lastPrice <= 0.0) return 0.0
        val live = campaigns.filter {
            it.status == CampaignState.PARTIALLY_FILLED ||
                it.status == CampaignState.ACTIVE ||
                it.status == CampaignState.REDUCED
        }
        val longQty = live.filter { it.isLong }.sumOf { it.filledNotional } / lastPrice
        val shortQty = live.filter { !it.isLong }.sumOf { it.filledNotional } / lastPrice
        return longQty - shortQty
    }
}
