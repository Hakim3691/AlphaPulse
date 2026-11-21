package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.db.ProactiveAuditLedgerEntity
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.engine.InvalidationAction
import kotlin.math.abs

/**
 * §71 / Contract C-22 — the single writer of the 25-field audit ledger.
 *
 * The ledger answers "why was this position cancelled or held", so the row is built from
 * the decision itself (the campaign in force, the price it was judged at, the action taken)
 * rather than from a summary written after the fact.
 *
 * Values the engine genuinely does not measure at this point are recorded honestly:
 * - [ProactiveAuditContext.coneCentre] / `coneBoundary` / `coneConfidencePct` stay at 0
 *   when no cone was measured, instead of inventing a boundary the scorer never used.
 * - `sgdRankBefore` / `sgdRankAfter` stay at 0 until a reranking pass supplies them.
 * - `finalOutcome` is `PENDING` and [ProactiveAuditLedgerEntity.subsequentlyReachedMinus2R]
 *   is false, because the shadow outcome is not knowable when the exit is written. The
 *   ledger is append-only, so claiming an outcome here would be a lie the schema cannot
 *   correct.
 *
 * Spec Ref: §71 25-Field Audit Ledger, §21.2, C-22
 */
data class ProactiveAuditContext(
    val coneCentre: Double = 0.0,
    val coneBoundary: Double = 0.0,
    val coneConfidencePct: Double = 0.0,
    val sgdRankBefore: Int = 0,
    val sgdRankAfter: Int = 0,
    val walkForwardCycleId: String = NOT_RECORDED,
    val trainingWindowId: String = NOT_RECORDED,
    val outOfSampleWindowId: String = NOT_RECORDED,
    val parameterSetVersion: String = ProductionExecutionConfig.AUDIT_PARAMETER_SET_VERSION
) {
    companion object {
        const val NOT_RECORDED = "NOT_RECORDED"
    }
}

object ProactiveAuditRecorder {

    /** The shadow verdict is unknowable at exit time and must not be guessed. */
    const val OUTCOME_PENDING = "PENDING"

    fun record(
        campaign: Campaign,
        asset: CryptoAsset,
        action: InvalidationAction,
        config: ProductionExecutionConfig = ProductionExecutionConfig.PRODUCTION,
        context: ProactiveAuditContext = ProactiveAuditContext(),
        nowMillis: Long = System.currentTimeMillis()
    ): ProactiveAuditLedgerEntity {
        val entryPrice = campaign.entryLadder.firstOrNull { it.isFilled }?.price
            ?: campaign.entryLadder.firstOrNull()?.price
            ?: asset.lastPrice
        val riskPerUnit = abs(entryPrice - campaign.stopLogic.hardStopPrice)
        val moveInFavour = if (campaign.isLong) {
            asset.lastPrice - entryPrice
        } else {
            entryPrice - asset.lastPrice
        }
        val currentPnlR = if (riskPerUnit > 0.0) moveInFavour / riskPerUnit else 0.0
        val breachMagnitude = if (context.coneBoundary > 0.0) {
            abs(asset.lastPrice - context.coneBoundary)
        } else {
            0.0
        }

        return ProactiveAuditLedgerEntity(
            id = "${campaign.id}@$nowMillis",
            timestamp = nowMillis,
            asset = campaign.asset,
            positionDirection = if (campaign.isLong) "LONG" else "SHORT",
            entryPrice = entryPrice,
            currentPrice = asset.lastPrice,
            initialRiskR = 1.0,
            currentPnlR = currentPnlR,
            coneCentre = context.coneCentre,
            coneBoundary = context.coneBoundary,
            coneConfidencePct = context.coneConfidencePct,
            breachMagnitude = breachMagnitude,
            sgdRankBefore = context.sgdRankBefore,
            sgdRankAfter = context.sgdRankAfter,
            rerankingMagnitude = abs(context.sgdRankBefore - context.sgdRankAfter),
            triggerType = action.name,
            proactiveThresholdR = ProductionExecutionConfig.PROACTIVE_MAX_LOSS_R,
            actionTaken = action.name,
            exitBeforeHalfR = currentPnlR > -0.5,
            exitBeforeOneR = currentPnlR > -1.0,
            subsequentlyReachedMinus2R = false,
            finalOutcome = OUTCOME_PENDING,
            walkForwardCycleId = context.walkForwardCycleId,
            trainingWindowId = context.trainingWindowId,
            outOfSampleWindowId = context.outOfSampleWindowId,
            parameterSetVersion = context.parameterSetVersion
        )
    }
}
