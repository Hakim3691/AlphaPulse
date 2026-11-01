package com.aistudio.alphapulse.domain.risk

/**
 * §69 / Contract C-20 — the cancellation-first invalidation decision.
 *
 * Why this exists
 * ---------------
 * Campaign boards are staged *before* confirmation, so a position is usually already
 * in a small loss at the moment its thesis first degrades. Waiting for the structural
 * stop turns a cancelled hypothesis into a full-size loss. This engine decides, from
 * the same evidence the campaign was built on, whether to cancel and exit while the
 * loss is still inside the tolerance the walk-forward selected.
 *
 * Two evidence paths trigger it: a cone breach that is statistically significant and
 * persists across bars (a single intrabar wick is noise), and a walk-forward SGD
 * re-ranking shift large enough to say the ensemble no longer favours this side.
 *
 * The tolerance is a parameter on purpose — `ProactiveMS_MaxLoss_R` defaults to 0.50R
 * and is validated across 0.25R..1.50R by the §70 matrix — while the 2.00R hard stop
 * is fixed. [InvalidationConfig] refuses any other hard-stop value, so no candidate
 * configuration can widen it.
 */

/** What made the thesis stop holding. */
enum class TriggerType {
    /** Nothing qualified: the loss is noise or the thesis is intact. */
    NONE,

    /** A cone breach that is significant and persisted across the required bars. */
    STATISTICALLY_SIGNIFICANT_CONE_BREACH,

    /** The ensemble re-ranked this side downwards by the required margin. */
    SGD_RERANKING_SHIFT,

    /** Both fired together: the strongest cancellation evidence. */
    DUAL_CONE_AND_SGD_SHIFT,

    /** The fixed 2.00R backstop, reached regardless of any other evidence. */
    HARD_STOP_BACKSTOP
}

/** What the engine says to do about it. */
enum class ProactiveAction {
    /** Keep the position; nothing qualified. */
    HOLD,

    /** Cancel working orders and exit, because the loss is still inside tolerance. */
    PROACTIVE_CANCEL_AND_EXIT,

    /**
     * Evidence qualified, but the loss already exceeds the proactive tolerance, so the
     * position keeps being risk-evaluated and the hard stop stays in charge.
     */
    CONTINUE_RISK_EVALUATION_NO_HARD_STOP_OVERRIDE,

    /** The fixed backstop: flatten now. */
    FORCE_HARD_STOP_2R
}

/**
 * The walk-forward parameters in force.
 *
 * @param proactiveMaxLossR the selected `ProactiveMS_MaxLoss_R` candidate; must be one
 *   of the six values the §70 matrix evaluates, so production cannot run a threshold
 *   that was never validated.
 * @param hardStopMaxLossR fixed at 2.00R; a candidate may never widen or override it.
 * @param minConeSignificanceZScore 1.96 is the two-sided 95% level — the breach must be
 *   statistically significant, not merely outside the cone.
 * @param minConePersistenceBars how many bars the breach must persist, so an intrabar
 *   wick cannot trigger an exit.
 * @param minSgdRankDrop how many places the position must fall in the ensemble ranking
 *   before the shift counts as a re-ranking.
 */
data class InvalidationConfig(
    val proactiveMaxLossR: Double = 0.50,
    val hardStopMaxLossR: Double = 2.00,
    val minConeSignificanceZScore: Double = 1.96,
    val minConePersistenceBars: Int = 2,
    val minSgdRankDrop: Int = 3
) {
    init {
        require(proactiveMaxLossR in SUPPORTED_PROACTIVE_THRESHOLDS) {
            "ProactiveMS_MaxLoss_R must be a supported walk-forward parameter candidate " +
                "$SUPPORTED_PROACTIVE_THRESHOLDS, was $proactiveMaxLossR"
        }
        require(hardStopMaxLossR == HARD_STOP_R) {
            "Hard stop backstop must remain fixed at ${HARD_STOP_R}R, was $hardStopMaxLossR"
        }
    }

    companion object {
        /** The six candidates §70 evaluates. */
        val SUPPORTED_PROACTIVE_THRESHOLDS = listOf(0.25, 0.50, 0.75, 1.00, 1.25, 1.50)

        /** Fixed by contract: no configuration may widen it. */
        const val HARD_STOP_R = 2.00
    }
}

/**
 * One observation of an open position, in the units the campaign was staged with.
 *
 * @param currentPnlR profit and loss in R multiples; negative is a loss.
 * @param coneBreachZScore how far beyond the governing cone price has moved, in cone
 *   standard deviations.
 * @param coneBreachPersistenceBars how many bars the breach has held.
 */
data class PositionEvaluationInput(
    val currentPnlR: Double,
    val coneBreachZScore: Double,
    val coneBreachPersistenceBars: Int,
    val sgdRankBefore: Int,
    val sgdRankAfter: Int
) {
    /** The loss as a positive number of R, so it compares directly to the tolerance. */
    val currentLossR: Double
        get() = if (currentPnlR < 0.0) -currentPnlR else 0.0

    /** Places fallen in the ranking; a rise is not a deterioration and never counts. */
    val sgdRankDeterioration: Int
        get() = (sgdRankAfter - sgdRankBefore).coerceAtLeast(0)
}

/** The verdict for one observation. */
data class InvalidationDecision(
    val triggerType: TriggerType,
    val action: ProactiveAction,
    val proactiveMaxLossThresholdR: Double,
    val exitedBefore05R: Boolean,
    val exitedBefore10R: Boolean
)

/**
 * Decides whether an open position is cancelled, kept, or handed to the hard stop.
 *
 * Order matters: the fixed backstop is checked first, so no evidence path and no
 * candidate threshold can delay a 2.00R exit.
 */
class ProactiveInvalidationEngine(
    private val config: InvalidationConfig = InvalidationConfig()
) {

    fun evaluate(input: PositionEvaluationInput): InvalidationDecision {
        val lossR = input.currentLossR

        if (lossR >= config.hardStopMaxLossR) {
            return InvalidationDecision(
                triggerType = TriggerType.HARD_STOP_BACKSTOP,
                action = ProactiveAction.FORCE_HARD_STOP_2R,
                proactiveMaxLossThresholdR = config.proactiveMaxLossR,
                exitedBefore05R = false,
                exitedBefore10R = false
            )
        }

        val significantConeBreach = input.coneBreachZScore >= config.minConeSignificanceZScore &&
            input.coneBreachPersistenceBars >= config.minConePersistenceBars
        val qualifyingSgdShift = input.sgdRankDeterioration >= config.minSgdRankDrop

        val trigger = when {
            significantConeBreach && qualifyingSgdShift -> TriggerType.DUAL_CONE_AND_SGD_SHIFT
            significantConeBreach -> TriggerType.STATISTICALLY_SIGNIFICANT_CONE_BREACH
            qualifyingSgdShift -> TriggerType.SGD_RERANKING_SHIFT
            else -> TriggerType.NONE
        }

        if (trigger == TriggerType.NONE) {
            return InvalidationDecision(
                triggerType = TriggerType.NONE,
                action = ProactiveAction.HOLD,
                proactiveMaxLossThresholdR = config.proactiveMaxLossR,
                exitedBefore05R = false,
                exitedBefore10R = false
            )
        }

        // Inside the walk-forward tolerance: cancel and exit before the degraded thesis
        // costs a full stop. Past it, the hard stop is already in charge.
        return if (lossR <= config.proactiveMaxLossR) {
            InvalidationDecision(
                triggerType = trigger,
                action = ProactiveAction.PROACTIVE_CANCEL_AND_EXIT,
                proactiveMaxLossThresholdR = config.proactiveMaxLossR,
                exitedBefore05R = lossR < 0.50,
                exitedBefore10R = lossR < 1.00
            )
        } else {
            InvalidationDecision(
                triggerType = trigger,
                action = ProactiveAction.CONTINUE_RISK_EVALUATION_NO_HARD_STOP_OVERRIDE,
                proactiveMaxLossThresholdR = config.proactiveMaxLossR,
                exitedBefore05R = false,
                exitedBefore10R = false
            )
        }
    }
}
