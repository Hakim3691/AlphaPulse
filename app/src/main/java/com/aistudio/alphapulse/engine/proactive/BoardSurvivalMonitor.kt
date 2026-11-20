package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.BoardRole

/**
 * §26 — board survival monitoring.
 *
 * The loop continuously asks whether a staged board still deserves to live:
 *
 *  - **Degradation.** Open interest spikes against the position while price stalls,
 *    so the board is cancelled before any slice fills.
 *  - **Promotion.** The primary path stalls while a secondary path gains volume
 *    confirmation, so the secondary is promoted to primary with more size.
 *  - **Suppression.** A secondary whose structural zone no longer exists is
 *    suppressed rather than allowed to rest into a dead level.
 *
 * Spec Ref: Phase 2 §26
 */
enum class BoardSurvivalAction {
    KEEP,
    DEGRADE_AND_CANCEL,
    PROMOTE_TO_PRIMARY,
    SUPPRESS_SECONDARY
}

data class BoardSurvivalInput(
    val campaignId: String,
    val role: BoardRole,
    val isLong: Boolean,
    /** Price change over the monitoring window, in percent. */
    val priceChangePct: Double,
    /** Open-interest change over the last hour, in percent. */
    val oiDelta1hPct: Double,
    /** A filled slice means the board is a live position, not a resting hypothesis. */
    val hasFilled: Boolean,
    /** No primary board remains live, so a secondary can take over. */
    val primaryStalled: Boolean,
    /** The secondary's level is gaining volume confirmation. */
    val secondaryVolumeConfirmed: Boolean,
    /** The board's structural zone was breached or lost. */
    val structuralIntegrityBroken: Boolean
)

data class BoardSurvivalVerdict(
    val campaignId: String,
    val action: BoardSurvivalAction,
    val sizeMultiplier: Double,
    val explanation: String
)

class BoardSurvivalMonitor {

    fun evaluate(input: BoardSurvivalInput): BoardSurvivalVerdict {
        // A live position is no longer a hypothesis to promote or degrade; it belongs
        // to the invalidation scorer and the hard stop.
        if (input.hasFilled) {
            return BoardSurvivalVerdict(
                input.campaignId,
                BoardSurvivalAction.KEEP,
                1.0,
                "Board holds fills; survival is governed by invalidation and the hard stop"
            )
        }

        if (input.structuralIntegrityBroken && input.role != BoardRole.PRIMARY) {
            return BoardSurvivalVerdict(
                input.campaignId,
                BoardSurvivalAction.SUPPRESS_SECONDARY,
                0.0,
                "Secondary structure lost; board suppressed instead of resting into a dead level"
            )
        }

        if (input.role == BoardRole.SECONDARY &&
            input.primaryStalled &&
            input.secondaryVolumeConfirmed &&
            !input.structuralIntegrityBroken
        ) {
            return BoardSurvivalVerdict(
                input.campaignId,
                BoardSurvivalAction.PROMOTE_TO_PRIMARY,
                PROMOTION_SIZE_MULTIPLIER,
                "Secondary path gained volume confirmation while the primary stalled; " +
                    "promoted to Primary with ${PROMOTION_SIZE_MULTIPLIER}x size"
            )
        }

        // Degradation: OI piled in against the board while price refused to move, so
        // the premise is being absorbed by opposing positioning before any fill.
        val priceStagnant = kotlin.math.abs(input.priceChangePct) <= STAGNATION_PRICE_BAND_PCT
        val oiAgainstBoard = input.oiDelta1hPct >= DEGRADATION_OI_SPIKE_PCT
        if (priceStagnant && oiAgainstBoard) {
            val side = if (input.isLong) "long" else "short"
            return BoardSurvivalVerdict(
                input.campaignId,
                BoardSurvivalAction.DEGRADE_AND_CANCEL,
                0.0,
                "$side board degraded: OI +${input.oiDelta1hPct}% against the position while " +
                    "price stalled at ${input.priceChangePct}%; cancelled before fills"
            )
        }

        return BoardSurvivalVerdict(
            input.campaignId,
            BoardSurvivalAction.KEEP,
            1.0,
            "Board still deserves to live"
        )
    }

    companion object {
        /** Price is stagnant when it moved less than a quarter of a percent. */
        const val STAGNATION_PRICE_BAND_PCT = 0.25

        /** §26's "OI spikes against the position" threshold, in percent per hour. */
        const val DEGRADATION_OI_SPIKE_PCT = 1.5

        /** §17: a primary role carries 40-60% of symbol risk against a secondary's 20-35%. */
        const val PROMOTION_SIZE_MULTIPLIER = 1.25
    }
}
