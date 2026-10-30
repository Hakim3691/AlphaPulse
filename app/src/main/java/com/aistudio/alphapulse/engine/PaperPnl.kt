package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign

/**
 * §5/§36 Paper-wallet accounting: pure, deterministic functions that derive exposure
 * and PnL from the campaign ladders actually filled in paper mode, so the RiskEnvelope
 * reflects what the paper wallet is really doing — no invented demo values, no random
 * draws, and never mixed with the §37 replay-benchmark PnL.
 *
 * Model:
 *  - Open exposure  = sum of filled-slice notional (a filled slice is an open position).
 *  - Unrealized PnL = direction-aware (mark - entry) * qty per filled slice.
 *  - Realized PnL   = booked once when a filled board is flattened: at the board's hard
 *    stop price for invalidation/operator flattens (conservative), or at the caller's
 *    price for a kill-switch flatten.
 *  - 1R             = accountEquityUsd * riskPerCampaignPct / 100 (the configured
 *    per-campaign risk budget), matching the sizing math.
 */
object PaperPnl {

    /** Gross notional of open paper positions (every still-filled ladder slice). */
    fun openExposureUsd(campaigns: List<Campaign>): Double =
        campaigns.sumOf { campaign ->
            campaign.entryLadder.filter { it.isFilled }.sumOf { it.notionalUsd }
        }

    /** Direction-aware unrealized PnL of open paper positions vs the live mark price. */
    fun unrealizedPnlUsd(campaigns: List<Campaign>, lastPrice: Double): Double =
        campaigns.sumOf { campaign ->
            val direction = if (campaign.isLong) 1.0 else -1.0
            campaign.entryLadder.filter { it.isFilled }.sumOf { slice ->
                (lastPrice - slice.price) * slice.qty * direction
            }
        }

    /** Realized PnL booked when [campaign]'s currently-filled slices close at [exitPrice]. */
    fun realizedPnlAtFlatten(campaign: Campaign, exitPrice: Double): Double {
        val direction = if (campaign.isLong) 1.0 else -1.0
        return campaign.entryLadder.filter { it.isFilled }.sumOf { slice ->
            (exitPrice - slice.price) * slice.qty * direction
        }
    }

    /** Converts realized USD into R using the configured per-campaign risk budget. */
    fun pnlToR(pnlUsd: Double, accountEquityUsd: Double, riskPerCampaignPct: Double): Double {
        val oneR = accountEquityUsd * riskPerCampaignPct / 100.0
        return if (oneR > 0.0) pnlUsd / oneR else 0.0
    }
}
