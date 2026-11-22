package com.aistudio.alphapulse.engine

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * §11 priority formula: a campaign's structural priority is the *product* of the path's
 * probability, the path's cleanliness and the confidence of the level it stages on.
 *
 * Why this exists
 * ---------------
 * The published priority rank used to be a fixed per-role base plus fifteen percent of
 * the path probability, so a primary board with no structure outranked a defensive board
 * with a perfect one — the number repeated the role the UI already showed. The product
 * keeps the three inputs that actually decide whether a board is worth arming: how likely
 * the path is, how clean it is, and how much the mapped level can be trusted (which falls
 * when a shelf has been tested to exhaustion, §8).
 *
 * Every input is a 0-100 score, the result is bounded to 0-100 and rounded to one decimal
 * so the dashboard's "PRIORITY RANK" prints a stable figure.
 *
 * Spec Ref: Phase 2 §11, §15
 */
object CampaignPriority {

    /** Input score used when a campaign has no directional path to read (§11). */
    const val FALLBACK_PATH_SCORE = 50

    /** Level confidence used when a board stages on no mapped zone. */
    const val FALLBACK_LEVEL_CONFIDENCE = 60

    fun of(pathProbability: Int, cleanliness: Int, levelConfidence: Int): Double {
        val probability = pathProbability.coerceIn(0, 100) / 100.0
        val clean = cleanliness.coerceIn(0, 100) / 100.0
        val confidence = levelConfidence.coerceIn(0, 100) / 100.0
        val score = probability * clean * confidence * 100.0
        return BigDecimal(score).setScale(1, RoundingMode.HALF_UP).toDouble()
    }
}
