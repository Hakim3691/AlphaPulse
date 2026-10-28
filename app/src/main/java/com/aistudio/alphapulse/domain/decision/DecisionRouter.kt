/***
 * DecisionRouter - Replaceable decision-router interface for trade signal determination.
 * The Android architecture defines this interface with support for:
 * - deterministic rule router
 * - lightweight classifier
 * - validated neural classifier
 * - on-device Needle 3 router ([Needle3DecisionRouter])
 *
 * Spec Ref: §29, Gap §3 line 108
 *
 * Decision State outputs:
 * STRONG LONG, LONG, NEUTRAL, SHORT, STRONG SHORT, NO TRADE
 *
 * The router shall not override the raw forecasts without recording the reason.
 */

package com.aistudio.alphapulse.domain.decision

import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.domain.quant.MarkovRegimeResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** DecisionState - The six possible trade signal outcomes from the DecisionRouter. */
enum class DecisionState {
    STRONG_LONG,
    LONG,
    NEUTRAL,
    SHORT,
    STRONG_SHORT,
    NO_TRADE
}

/**
 * Which router actually produced a verdict.
 *
 * Recorded with every decision so the §21 audit ledger can attribute a route to
 * the on-device model or to the rules that back it up. Callers must not branch on
 * it: §29 keeps the router replaceable, and this is an observation, not a switch.
 */
enum class DecisionSource {
    /** Cactus Needle 3 answered on device. */
    NEEDLE3,

    /** The deterministic rules answered, by choice or as the fallback. */
    DETERMINISTIC
}

/** DecisionInputs - The structured inputs the DecisionRouter evaluates. */
data class DecisionInputs(
    val triFactorForecast: Double, // Kinematic forecast in % (positive = bullish)
    val tsfmForecast: Double, // TSFM forecast in % (positive = bullish)
    val lobConfidence: Double, // LOB neural confidence [0.0, 1.0]
    val liquidityConfidence: Double, // Liquidity path confidence [0.0, 1.0]
    val regimeConfidence: Double, // Regime confidence [0.0, 1.0]
    val modelDisagreement: Double, // Disagreement between models [0.0, 1.0]
    val dataFreshness: Double, // Normalized freshness [0.0, 1.0], 1.0 = latest
    val coneBreaches: Int = 0, // Number of recent cone breaches
    val recentErrors: Double = 0.0 // Mean absolute error of recent predictions
)

/**
 * The verdict a caller can show: the chosen state, its calibrated confidence and
 * the router's own reason for choosing it.
 *
 * §29 forbids overriding the raw forecasts without recording the reason, so the
 * reason travels with the state instead of being logged and dropped.
 */
data class DecisionReport(
    val state: DecisionState,
    /** Calibrated confidence [0,1], or 0.0 when the router has no calibrated number. */
    val confidence: Double,
    val reason: String,
    /** Which router answered; carried into the audit ledger, never used to gate. */
    val source: DecisionSource = DecisionSource.DETERMINISTIC
) {
    /** True when [confidence] is a calibrated probability rather than an absence of one. */
    val isCalibrated: Boolean get() = confidence > 0.0
}

/**
 * Build the §29 routing snapshot from one live evaluation.
 *
 * The forecasts themselves are the ensemble's own legs at one horizon, so the
 * verdict is derived from the numbers behind the visible cone rather than from a
 * separate synthetic snapshot. Two inputs have no published value in the
 * evaluation path, so their rule is written down here, once:
 *
 *  * `lobConfidence` is how one-sided the L2 book is - 0.0 at a balanced 50/50
 *    book, 1.0 when every top-of-book dollar is on one side.
 *  * `modelDisagreement` is directional: the kinematic and TSFM legs either point
 *    the same way (0.0) or they do not (1.0). An unmounted TSFM leg contributes no
 *    direction to the drift, so it cannot disagree; the deterministic rules then
 *    find no convergence and stand aside.
 *
 * @param kinematicPct kinematic ensemble leg (K+P+Alpha) at the reference horizon, in %
 * @param tsfmPct mounted TSFM ensemble leg (Chronos+TimesFM) at the same horizon, in %
 * @param bidRatioPct share of top-of-book notional resting on the bid, 0..100
 * @param confluenceConfidence the engine's own confluence confidence [0,1]
 * @param regimeConfidence regime conviction [0,1] from the market-state classifier
 * @param coneBreaches settled predictions whose cone was breached recently
 * @param recentErrorPct mean absolute error of those settled predictions, in %
 * @param dataAvailable false when no live price or history was obtained
 */
internal fun decisionInputsFor(
    kinematicPct: Double,
    tsfmPct: Double,
    bidRatioPct: Double,
    confluenceConfidence: Double,
    regimeConfidence: Double,
    coneBreaches: Int,
    recentErrorPct: Double,
    dataAvailable: Boolean
): DecisionInputs {
    val bookBalance = abs(bidRatioPct.coerceIn(0.0, 100.0) - 50.0) / 50.0
    val sameDirection = (kinematicPct > 0.0 && tsfmPct > 0.0) ||
        (kinematicPct < 0.0 && tsfmPct < 0.0)
    val noDirection = kinematicPct == 0.0 || tsfmPct == 0.0

    return DecisionInputs(
        triFactorForecast = kinematicPct,
        tsfmForecast = tsfmPct,
        lobConfidence = bookBalance,
        liquidityConfidence = confluenceConfidence.coerceIn(0.0, 1.0),
        regimeConfidence = regimeConfidence.coerceIn(0.0, 1.0),
        modelDisagreement = if (noDirection || sameDirection) 0.0 else 1.0,
        // Stale or missing data cannot be traded on, and the router says NO_TRADE for it.
        dataFreshness = if (dataAvailable) 1.0 else 0.0,
        coneBreaches = coneBreaches,
        recentErrors = recentErrorPct
    )
}

/** DecisionRouter - The replaceable interface for trade signal determination. */
interface DecisionRouter {

    /** Apply the routing logic and return the resulting DecisionState. */
    fun router(inputs: DecisionInputs): DecisionState

    /** Record the reason for the returned state (for audit/debug). */
    val lastReason: String

    /** Get the confidence level associated with the returned state. */
    val decisionConfidence: Double

    /**
     * Which implementation produced the last verdict.
     *
     * Defaulted rather than abstract so a swap stays a one-line change for callers
     * and existing implementations keep compiling; §29 forbids any caller from
     * changing behaviour based on it.
     */
    val decisionSource: DecisionSource
        get() = DecisionSource.DETERMINISTIC
}

/** DeterministicDecisionRouter - Rule-based router without neural dependencies.
 * Uses configurable thresholds on model convergence, confidence, and disagreement.
 */
class DeterministicDecisionRouter : DecisionRouter {

    // Thresholds (configurable via constructor or defaults)
    private val longConvergenceThreshold: Double = 0.60
    private val strongLongConvergenceThreshold: Double = 0.75
    private val disagreementThreshold: Double = 0.40
    private val dataFreshnessMinimum: Double = 0.50
    private val coneBreachMaximum: Int = 3

    override val lastReason: String = "Deterministic router: rule-based evaluation"
    override val decisionConfidence: Double = 0.0 // Not applicable for pure deterministic

    init {}

    override fun router(inputs: DecisionInputs): DecisionState {
        // Rule 1: Data freshness guard — if data is stale, NO TRADE
        if (inputs.dataFreshness < dataFreshnessMinimum) {
            return DecisionState.NO_TRADE
        }

        // Rule 2: Cone breach guard — too many breaches reduce confidence
        if (inputs.coneBreaches >= coneBreachMaximum) {
            // Still may trade but with reduced confidence; here we go to NO_TRADE
            return DecisionState.NO_TRADE
        }

        // Rule 3: Model disagreement guard — strongly disagreeing models carry no
        // tradable edge, so the router stands aside with a NEUTRAL state.
        if (inputs.modelDisagreement > disagreementThreshold) {
            return DecisionState.NEUTRAL
        }

        // Rule 4: Convergence analysis — both models agree on direction
        val bullishConvergence = inputs.triFactorForecast > 0.0 && inputs.tsfmForecast > 0.0
        val bearishConvergence = inputs.triFactorForecast < 0.0 && inputs.tsfmForecast < 0.0

        // Rule 5: High confidence strong direction
        val highConfidence = inputs.lobConfidence >= strongLongConvergenceThreshold &&
                inputs.liquidityConfidence >= strongLongConvergenceThreshold

        if (highConfidence && bullishConvergence) {
            return DecisionState.STRONG_LONG
        }
        if (highConfidence && bearishConvergence) {
            return DecisionState.STRONG_SHORT
        }

        // Rule 6: Moderate confidence direction
        val moderateConfidence = inputs.lobConfidence >= longConvergenceThreshold &&
                inputs.liquidityConfidence >= longConvergenceThreshold

        if (moderateConfidence && bullishConvergence) {
            return DecisionState.LONG
        }
        if (moderateConfidence && bearishConvergence) {
            return DecisionState.SHORT
        }

        // Rule 7: Insufficient convergence
        return DecisionState.NEUTRAL
    }
}

/** NeuralDecisionRouter - Lightweight classifier router.
 * Uses simple learned thresholds (placeholder for actual NN inference).
 * In production, this would use ExecuTorch/ONNX Runtime Mobile inference.
 */
class NeuralDecisionRouter : DecisionRouter {

    // Placeholder learned thresholds (would be trained/calibrated in production)
    private val neuralLongThreshold: Double = 0.65
    private val neuralStrongThreshold: Double = 0.85
    private val neuralDisagreementThreshold: Double = 0.45

    override val lastReason: String = "Neural router: lightweight classifier"
    override val decisionConfidence: Double = neuralStrongThreshold

    init {}

    override fun router(inputs: DecisionInputs): DecisionState {
        // Compute a combined score from all inputs
        val combinedScore = calculateCombinedScore(inputs)

        // Decision boundaries
        if (combinedScore >= neuralStrongThreshold) {
            // Check direction: if both forecasts bullish → STRONG_LONG, both bearish → STRONG_SHORT
            if (inputs.triFactorForecast > 0.0 && inputs.tsfmForecast > 0.0) {
                return DecisionState.STRONG_LONG
            }
            if (inputs.triFactorForecast < 0.0 && inputs.tsfmForecast < 0.0) {
                return DecisionState.STRONG_SHORT
            }
            // Mixed signals with high confidence → NEUTRAL
            return DecisionState.NEUTRAL
        }

        if (combinedScore >= neuralLongThreshold) {
            // Moderate confidence long/short
            if (inputs.triFactorForecast > 0.0 && inputs.tsfmForecast > 0.0) {
                return DecisionState.LONG
            }
            if (inputs.triFactorForecast < 0.0 && inputs.tsfmForecast < 0.0) {
                return DecisionState.SHORT
            }
            return DecisionState.NEUTRAL
        }

        // Low confidence — check if at least one model is bullish/bearish
        if (abs(inputs.triFactorForecast) > abs(inputs.tsfmForecast)) {
            // Tri-Factor dominates direction
            return if (inputs.triFactorForecast > 0.0) DecisionState.LONG else DecisionState.SHORT
        } else {
            // TSFM dominates direction
            return if (inputs.tsfmForecast > 0.0) DecisionState.LONG else DecisionState.SHORT
        }
    }

    /** Calculate a combined confidence score from all inputs.
     *
     * The score is direction-agnostic: conviction comes from model agreement,
     * order-book quality and forecast magnitude, while the sign of the forecasts
     * alone picks long versus short. A signed sum would score a strong short the
     * same as a weak long, which is not a confidence measure.
     */
    private fun calculateCombinedScore(inputs: DecisionInputs): Double {
        val triSign = sign(inputs.triFactorForecast)
        val tsfmSign = sign(inputs.tsfmForecast)
        val directionAgreement = if (triSign != 0 && triSign == tsfmSign) 1.0 else 0.0

        val magnitude = (abs(inputs.triFactorForecast) + abs(inputs.tsfmForecast)) / 4.0
        val conviction = min(1.0, magnitude)

        val score = 0.35 * directionAgreement +
                0.25 * inputs.lobConfidence +
                0.15 * inputs.liquidityConfidence +
                0.15 * inputs.regimeConfidence +
                0.10 * conviction

        // Penalize for model disagreement and stale data
        val disagreementPenalty = inputs.modelDisagreement * 0.5
        val stalenessPenalty = (1.0 - inputs.dataFreshness) * 0.3

        return max(0.0, min(1.0, score - disagreementPenalty - stalenessPenalty))
    }

    /** Helper: sign of a double, returning -1, 0, or 1. */
    private fun sign(x: Double): Int = if (x > 0) 1 else if (x < 0) -1 else 0
}