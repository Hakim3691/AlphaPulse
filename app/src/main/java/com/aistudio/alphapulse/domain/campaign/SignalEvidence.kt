package com.aistudio.alphapulse.domain.campaign

/**
 * C-55 — evidence before advice: a horizon's forecast feeds a campaign only once this
 * coin's own settlements have proven it.
 *
 * Why this exists
 * ---------------
 * AlphaPulse issues a forecast at every horizon the ensemble mounts. Campaigns used to
 * consume all of them, so a board could be planned on a horizon that had never settled
 * — or had settled nine times and been wrong four — as readily as on one with a proven
 * cone. The hit rate was displayed, but nothing downstream was gated by it.
 *
 * The gate is per coin **and** per horizon, because that is the unit the forecast is
 * issued and graded in: a coin's 5m cone can be excellent while its 1h cone is worse
 * than a coin flip, and a single blended rate hides exactly that difference.
 *
 * The threshold is 80% over at least [MIN_SETTLED] settled predictions. Below that
 * sample the rate is not evidence, it is an anecdote, so an unproven horizon is
 * withheld rather than trusted — the gate fails closed. This is the **feeding** gate;
 * §22's staging gate (C-19) still requires its own > 90% before a board may stage.
 *
 * Spec Ref: Phase 2 C-55, §22, §67
 */
object SignalEvidence {

    /** A horizon must have called its cone at least this often. */
    const val MIN_HIT_RATE_PCT = 80.0

    /** Below this many settled predictions a hit rate is not yet evidence. */
    const val MIN_SETTLED = 20

    /**
     * One horizon's settlement record for one coin.
     *
     * @param settled predictions whose outcome is known
     * @param hits settled predictions whose realized price finished inside the cone
     */
    data class HorizonEvidence(
        val horizonId: String,
        val settled: Int,
        val hits: Int
    ) {
        /** Hits as a percentage of settled predictions; 0.0 when nothing settled. */
        val hitRatePct: Double get() = if (settled > 0) hits * 100.0 / settled else 0.0

        /** True when the sample is large enough and the rate clears the threshold. */
        val qualified: Boolean get() = isQualified(settled, hits)

        /**
         * The one-line account of why a horizon is (or is not) feeding campaigns, e.g.
         * `1h 71% of 34 settled (needs 80% of 20)`.
         */
        fun describe(): String {
            val rate = Math.round(hitRatePct * 10.0) / 10.0
            return "$horizonId $rate% of $settled settled " +
                "(needs $MIN_HIT_RATE_PCT% of $MIN_SETTLED)"
        }
    }

    /**
     * The gate itself: enough settled predictions, and enough of them called correctly.
     *
     * Both halves are required, so nineteen perfect calls and twenty-five mediocre ones
     * are equally withheld.
     */
    fun isQualified(settled: Int, hits: Int): Boolean {
        if (settled < MIN_SETTLED) return false
        if (hits < 0 || hits > settled) return false
        return hits * 100.0 / settled >= MIN_HIT_RATE_PCT
    }

    /** Evidence for one horizon, clamped to a coherent record. */
    fun assess(horizonId: String, settled: Int, hits: Int): HorizonEvidence =
        HorizonEvidence(
            horizonId = horizonId,
            settled = settled.coerceAtLeast(0),
            hits = hits.coerceIn(0, settled.coerceAtLeast(0))
        )
}
