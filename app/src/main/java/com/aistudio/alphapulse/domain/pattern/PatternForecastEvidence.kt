package com.aistudio.alphapulse.domain.pattern

/**
 * The mounted TSFM ensemble's read on one pattern horizon.
 *
 * Why this exists
 * ---------------
 * The price ledger already blends Chronos-Bolt and TimesFM into the cone it audits, but the
 * pattern ledger was scoring pure candle geometry. That made "pattern prediction" a second,
 * weaker product hiding inside the same screen: two ledgers that disagree about whether a
 * model was consulted. This type carries the same models' weighted median move into the
 * pattern prediction, so the pattern row records which TSFMs spoke and whether they agreed
 * with the formation the classifier found.
 *
 * Rules
 * -----
 * The classifier remains the only source of the *pattern*: settlement is graded against
 * realized geometry, so a model must never fabricate the thing it would later be scored on.
 * The ensemble may only move the prior by a bounded amount ([AGREEMENT_BONUS] /
 * [CONTRADICTION_PENALTY]) and only when its drift clears [MIN_DRIFT_PCT]. No mounted model,
 * no forecast, or a flat forecast leaves the geometry untouched.
 *
 * Spec Ref: Directive §4 SGD Pipeline, §16 Model Strategy, C-25
 */
data class PatternForecastEvidence(
    /** `chronos`, `timesfm`, `chronos+timesfm`; empty when no model produced a forecast. */
    val provider: String = "",
    /** Weighted ensemble median move over the horizon, in percent of spot. */
    val driftPct: Double = 0.0,
    /** True when the models point the same way as the formation; null when undecidable. */
    val aligned: Boolean? = null
) {

    /** Whether a mounted model actually contributed to this row. */
    val available: Boolean get() = provider.isNotEmpty()

    companion object {

        /** A move smaller than this is noise; it must not re-rank a formation. */
        const val MIN_DRIFT_PCT = 0.005

        /** Confidence added when the ensemble confirms the formation's direction. */
        const val AGREEMENT_BONUS = 0.05

        /** Confidence removed when the ensemble contradicts it. */
        const val CONTRADICTION_PENALTY = 0.10

        /** Recorded when no mounted model produced a forecast for the horizon. */
        val NONE = PatternForecastEvidence()
    }
}
