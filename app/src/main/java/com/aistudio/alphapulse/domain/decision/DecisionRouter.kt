/***
 * DecisionRouter - Replaceable decision-router interface for trade signal determination.
 * The Android architecture defines this interface with support for:
 * - deterministic rule router
 * - lightweight classifier
 * - validated neural classifier
 * - future Decision-1-compatible model
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

/** DecisionState - The six possible trade signal outcomes from the DecisionRouter. */
enum class DecisionState {
    STRONG_LONG,
    LONG,
    NEUTRAL,
    SHORT,
    STRONG_SHORT,
    NO_TRADE
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
    val coneBreaches: Int, // Number of recent cone breaches
    val recentErrors: Double // Mean absolute error of recent predictions
)

/** DecisionRouter - The replaceable interface for trade signal determination. */
interface DecisionRouter {

    /** Apply the routing logic and return the resulting DecisionState. */
    fun router(inputs: DecisionInputs): DecisionState

    /** Record the reason for the returned state (for audit/debug). */
    val lastReason: String

    /** Get the confidence level associated with the returned state. */
    val decisionConfidence: Double
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

        // Rule 3: Model disagreement guard — if models disagree strongly, NEUTRAL
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
    private val neuralStrongThreshold: Double = 0.80
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
        if (inputs.triFactorForecast.abs() > inputs.tsfmForecast.abs()) {
            // Tri-Factor dominates direction
            return if (inputs.triFactorForecast > 0.0) DecisionState.LONG else DecisionState.SHORT
        } else {
            // TSFM dominates direction
            return if (inputs.tsfmForecast > 0.0) DecisionState.LONG else DecisionState.SHORT
        }
    }

    /** Calculate a combined confidence score from all inputs. */
    private fun calculateCombinedScore(inputs: DecisionInputs): Double {
        // Weighted combination (these weights would be learned/calibrated in production)
        val score = 0.3 * sign(inputs.triFactorForecast) +
                0.3 * sign(inputs.tsfmForecast) +
                0.2 * inputs.lobConfidence +
                0.1 * inputs.liquidityConfidence +
                0.1 * inputs.regimeConfidence

        // Penalize for model disagreement and stale data
        val disagreementPenalty = inputs.modelDisagreement * 0.5
        val stalenessPenalty = (1.0 - inputs.dataFreshness) * 0.3

        return max(0.0, min(1.0, score - disagreementPenalty - stalenessPenalty))
    }

    /** Helper: sign of a double, returning -1, 0, or 1. */
    private fun sign(x: Double): Int = if (x > 0) 1 else if (x < 0) -1 else 0
}