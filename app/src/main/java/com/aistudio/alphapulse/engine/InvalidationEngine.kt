package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.InvalidationFamily
import com.aistudio.alphapulse.data.model.InvalidationReason
import com.aistudio.alphapulse.data.model.LevelMap
import kotlin.math.abs

data class InvalidationResult(
    val updatedCampaign: Campaign,
    val triggerAction: InvalidationAction,
    val primaryReason: String
)

enum class InvalidationAction {
    NO_ACTION,
    SOFT_CANCEL_RESTING,
    HARD_FLATTEN_COOLDOWN,
    PROMOTE_SECONDARY_TO_PRIMARY,
    REARM_CAMPAIGN
}

class InvalidationEngine {

    /**
     * Evaluates live evidence and updates campaign invalidation score and state
     */
    fun evaluateCampaign(
        campaign: Campaign,
        asset: CryptoAsset,
        evidence: EvidenceFrame,
        levelMap: LevelMap
    ): InvalidationResult {
        if (campaign.status == CampaignState.CANCELLED ||
            campaign.status == CampaignState.COMPLETED ||
            campaign.status == CampaignState.SUPPRESSED
        ) {
            return InvalidationResult(campaign, InvalidationAction.NO_ACTION, "Campaign inactive")
        }

        val reasons = mutableListOf<InvalidationReason>()
        var score = 0

        val currentPrice = asset.lastPrice

        // 1. Structural Failure Check
        if (campaign.isLong) {
            // Long invalidation: price acceptance below zone low
            val lowestEntry = campaign.entryLadder.minOfOrNull { it.price } ?: (currentPrice * 0.98)
            if (currentPrice < lowestEntry - (asset.atr5m * 0.4)) {
                val pts = 35
                score += pts
                reasons.add(
                    InvalidationReason(
                        InvalidationFamily.STRUCTURE_FAILURE,
                        pts,
                        "Price accepted below entry zone edge ($currentPrice < $lowestEntry)"
                    )
                )
            }
        } else {
            // Short invalidation: price acceptance above zone high
            val highestEntry = campaign.entryLadder.maxOfOrNull { it.price } ?: (currentPrice * 1.02)
            if (currentPrice > highestEntry + (asset.atr5m * 0.4)) {
                val pts = 35
                score += pts
                reasons.add(
                    InvalidationReason(
                        InvalidationFamily.STRUCTURE_FAILURE,
                        pts,
                        "Price pushed above resistance zone edge ($currentPrice > $highestEntry)"
                    )
                )
            }
        }

        // 2. Positioning Contradiction Check (OI + Price Direction)
        if (campaign.isLong) {
            // Price down with OI rising against long campaign (bearish trapping / aggressive short build)
            if (asset.priceChange24h < 0 && evidence.oiDelta1hPct > 0.8) {
                val pts = 25
                score += pts
                reasons.add(
                    InvalidationReason(
                        InvalidationFamily.POSITIONING_CONTRADICTION,
                        pts,
                        "Price declining while OI expanding (+${evidence.oiDelta1hPct}%) against long thesis"
                    )
                )
            }
        } else {
            // Price up with OI rising against short campaign (bullish squeeze / aggressive long build)
            if (asset.priceChange24h > 0 && evidence.oiDelta1hPct > 0.8) {
                val pts = 25
                score += pts
                reasons.add(
                    InvalidationReason(
                        InvalidationFamily.POSITIONING_CONTRADICTION,
                        pts,
                        "Price lifting while OI expanding (+${evidence.oiDelta1hPct}%) through short zone"
                    )
                )
            }
        }

        // 3. Flow Failure (Taker Buy/Sell Imbalance)
        if (campaign.isLong && evidence.takerBuyRatio < 0.38) {
            val pts = 18
            score += pts
            reasons.add(
                InvalidationReason(
                    InvalidationFamily.FLOW_FAILURE,
                    pts,
                    "No taker buy response at expected shelf (${(evidence.takerBuyRatio * 100).toInt()}% taker buy)"
                )
            )
        } else if (!campaign.isLong && evidence.takerBuyRatio > 0.62) {
            val pts = 18
            score += pts
            reasons.add(
                InvalidationReason(
                    InvalidationFamily.FLOW_FAILURE,
                    pts,
                    "Persistent aggressive taker buying into resistance (${(evidence.takerBuyRatio * 100).toInt()}% taker buy)"
                )
            )
        }

        // 4. Crowding Reversal Check
        if (campaign.isLong && asset.fundingRatePct > 0.02) {
            val pts = 15
            score += pts
            reasons.add(
                InvalidationReason(
                    InvalidationFamily.CROWDING_REVERSAL,
                    pts,
                    "Funding flipped positive (+${(asset.fundingRatePct * 100).toInt()} bps), removing short squeeze edge"
                )
            )
        } else if (!campaign.isLong && asset.fundingRatePct < -0.015) {
            val pts = 15
            score += pts
            reasons.add(
                InvalidationReason(
                    InvalidationFamily.CROWDING_REVERSAL,
                    pts,
                    "Funding deeply negative, crowding shorts and creating squeeze risk"
                )
            )
        }

        // 5. Liquidity Disorder (Spread blowout)
        if (asset.orderBookSpreadPct > 0.08) {
            val pts = 20
            score += pts
            reasons.add(
                InvalidationReason(
                    InvalidationFamily.LIQUIDITY_DISORDER,
                    pts,
                    "Order book spread blown out to ${asset.orderBookSpreadPct}%"
                )
            )
        }

        // 6. Time invalidation (§28): stagnant inside the zone beyond the campaign time
        // budget — the pain-path hypothesis decayed without either trigger printing.
        if (campaign.entryLadder.none { it.isFilled }) {
            val elapsedMinutes = (System.currentTimeMillis() - campaign.createdAt) / 60_000
            val budgetMinutes = timeBudgetMinutes(campaign)
            val lowestEntry = campaign.entryLadder.minOfOrNull { it.price } ?: 0.0
            val highestEntry = campaign.entryLadder.maxOfOrNull { it.price } ?: 0.0
            val stagnantInsideZone = currentPrice in lowestEntry..highestEntry
            if (elapsedMinutes > budgetMinutes && stagnantInsideZone) {
                val pts = 20
                score += pts
                reasons.add(
                    InvalidationReason(
                        InvalidationFamily.TIME_EXPIRATION,
                        pts,
                        "Stagnant inside zone for ${elapsedMinutes}m (budget ${budgetMinutes}m); pain-path hypothesis decayed"
                    )
                )
            }
        }

        val totalScore = score.coerceIn(5, 100)

        // Action Decision Matrix
        var action = InvalidationAction.NO_ACTION
        var newStatus = campaign.status
        var newLadder = campaign.entryLadder

        if (totalScore >= campaign.stopLogic.hardThreshold) {
            action = InvalidationAction.HARD_FLATTEN_COOLDOWN
            newStatus = CampaignState.CANCELLED
            newLadder = campaign.entryLadder.map { it.copy(isResting = false) }
        } else if (totalScore >= campaign.stopLogic.softThreshold) {
            action = InvalidationAction.SOFT_CANCEL_RESTING
            newStatus = if (campaign.entryLadder.any { it.isFilled }) CampaignState.REDUCED else CampaignState.CANCELLED
            // Cancel unfilled resting slices
            newLadder = campaign.entryLadder.map {
                if (!it.isFilled) it.copy(isResting = false) else it
            }
        }

        val updated = campaign.copy(
            invalidationScore = totalScore,
            topReasons = reasons.take(3),
            status = newStatus,
            entryLadder = newLadder,
            cooldownUntilTs = if (action == InvalidationAction.HARD_FLATTEN_COOLDOWN) System.currentTimeMillis() + (15 * 60 * 1000) else campaign.cooldownUntilTs
        )

        val reasonText = reasons.firstOrNull()?.explanation ?: "Evidence baseline within safety envelope"

        return InvalidationResult(updated, action, reasonText)
    }

    /**
     * Board Promotion Check: upgrades secondary board to primary if original primary decayed
     */
    fun checkBoardPromotion(campaign: Campaign, isPrimaryDead: Boolean): Campaign {
        if (campaign.role == BoardRole.SECONDARY && isPrimaryDead && campaign.invalidationScore < 30) {
            return campaign.copy(
                role = BoardRole.PRIMARY,
                sizeMultiplier = 1.25,
                priorityScore = campaign.priorityScore + 20.0
            )
        }
        return campaign
    }

    /**
     * §26 Rearm: after the cooldown lapses, the same family may be re-staged at the same
     * level only if the level is still detected near the original ladder and the board
     * was cancelled with nothing filled (no orphaned position to re-protect).
     */
    fun evaluateRearm(
        campaign: Campaign,
        levelMap: LevelMap,
        asset: CryptoAsset,
        nowTs: Long = System.currentTimeMillis()
    ): InvalidationResult {
        val eligible = campaign.status == CampaignState.CANCELLED &&
            nowTs >= campaign.cooldownUntilTs &&
            campaign.entryLadder.none { it.isFilled }
        if (!eligible) {
            return InvalidationResult(campaign, InvalidationAction.NO_ACTION, "Rearm conditions unmet")
        }
        val ladderCenter = campaign.entryLadder.map { it.price }.average()
        val atr = asset.atr5m.coerceAtLeast(1e-9)
        val zoneStillValid = levelMap.zones.any { abs(it.center - ladderCenter) <= atr * 0.5 }
        if (!zoneStillValid) {
            return InvalidationResult(
                campaign,
                InvalidationAction.NO_ACTION,
                "Original level no longer detected; rearm denied"
            )
        }
        val rearmed = campaign.copy(
            status = CampaignState.STAGED,
            invalidationScore = 12,
            topReasons = emptyList(),
            entryLadder = campaign.entryLadder.map { it.copy(isFilled = false, isResting = true) },
            cooldownUntilTs = 0L,
            createdAt = nowTs
        )
        return InvalidationResult(
            rearmed,
            InvalidationAction.REARM_CAMPAIGN,
            "Level re-detected after cooldown; board re-armed at same family"
        )
    }

    /**
     * §28 mirror activation: when a campaign hard-invalidates (trap/rejection), its
     * mirrored family wakes from SUPPRESSED as a defensive board — the mirrored
     * hypothesis is now live and must be proactively armed.
     */
    fun activateMirrorOnTrap(campaign: Campaign, mirror: Campaign): Campaign? {
        val trigger = campaign.status == CampaignState.CANCELLED &&
            campaign.invalidationScore >= campaign.stopLogic.hardThreshold
        val mirrorSuppressed = mirror.status == CampaignState.SUPPRESSED
        return if (trigger && mirrorSuppressed) {
            mirror.copy(
                role = BoardRole.DEFENSIVE,
                status = CampaignState.STAGED,
                sizeMultiplier = 0.5,
                priorityScore = mirror.priorityScore + 10.0
            )
        } else null
    }

    /** §29: tactical boards hold tighter than strategic; defensive boards fastest. */
    private fun timeBudgetMinutes(campaign: Campaign): Long = when (campaign.role) {
        BoardRole.PRIMARY -> 240L
        BoardRole.SECONDARY -> 180L
        BoardRole.DEFENSIVE -> 90L
        BoardRole.DISABLED -> 60L
    }
}
