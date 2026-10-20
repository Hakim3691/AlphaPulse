package com.aistudio.alphapulse.domain.narrative

import com.aistudio.alphapulse.domain.quant.ModelState

/** 
 * Edge SLM Narrative Layer - §59 AI Narrator Requirements
 * 
 * The narrator shall never invent numerical facts.
 * All numerical claims must originate from the structured engine state.
 * The narrator shall be explicitly constrained to:
 *   Explain, Summarize, Diagnose, Compare
 * and shall not:
 *   Invent data, Change model outputs, Change ensemble weights,
 *   Create unsupported probabilities
 *   Modify the underlying numerical prediction directly.
 */

interface EdgeSlmNarrativeLayer {

    /** Generate institutional execution brief from engine state */
    fun generateExecutionBrief(
        symbol: String,
        regime: String,
        kinematic: Double,
        painPath: Double,
        alpha: Double,
        tsfm: Double,
        lobConfidence: Double,
        liquidationBias: String,
        coneBreaches: Int,
        modelAgreement: Double,
        confidenceTier: String
    ): String

    /** Generate model-convergence explanation */
    fun generateConvergenceExplanation(
        modelStates: List<ModelState>,
        disagreementScore: Double
    ): String

    /** Generate regime explanation */
    fun generateRegimeExplanation(regime: String, regimeConfidence: Double): String

    /** Generate anomaly warning when conditions exceed thresholds */
    fun generateAnomalyWarning(
        lobConfidence: Double,
        modelDisagreement: Double,
        dataFreshness: Double,
        requiredModelAvailable: Boolean
    ): String

    /** Generate post-session audit summary */
    fun generatePostSessionAudit(
        totalPredictions: Int,
        coneHitRate: Double,
        averageErrorPct: Double,
        modelPerformance: Map<String, Double>,
        learnedActions: List<String>
    ): String
}

/** 
 * Narrator constraint validator - ensures narrator output complies with §59 requirements
 */
object NarratorConstraintValidator {

    /** Validate that numerical values in narrator output originate from engine state */
    fun validateNumericalClaim(
        claim: String,
        sourceValue: Double?,
        tolerance: Double = 0.01
    ): Boolean {
        return if (sourceValue == null) {
            false // Cannot claim a value without a source
        } else {
            // Parse the claimed value from the narrative string
            val claimedValue = claim
                .replace("%", "")
                .replace("+", "")
                .replace("-", "")
                .replace("x", "")
                .trim()
                .toDoubleOrNull()

            claimedValue != null &&
                Math.abs(claimedValue - sourceValue) <= tolerance
        }
    }

    /** Validate narrator is not modifying model outputs */
    fun isOutputSafe(narrative: String, modelOutputs: Map<String, Double>): Boolean {
        // Check for phrases that indicate modification of original outputs
        val modificationPhrases = listOf(
            "changed to", "modified", "adjusted", "altered",
            "instead of", "replaced by", "overridden"
        )

        val lower Narrative = narrative.lowercase()
        return modificationPhrases.none { lower.contains(it) }
    }

    /** Validate narrator is not inventing data */
    fun containsInventedData(narrative: String): Boolean {
        val inventionPhrases = listOf(
            "approximately", "about", "roughly", "estimated",
            "generates", "creates", "produces", "yields"
        ).map { it.lowercase() }

        return inventionPhrases.any { lowerNarrative.contains(it) }
    }
}