package com.aistudio.alphapulse.domain.campaign

/**
 * C-57 — evidence before advice: a horizon's forecast feeds a campaign only once this
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
 * The threshold is one configured value (C-60): 85.0% as shipped, read live from
 * [Thresholds] so the Guardrails Thresholds tab moves every consumer at once. The sample
 * floor is configured too, and scales with how often the horizon actually settles:
 *
 * | horizon | minimum settled predictions |
 * | :------ | :-------------------------- |
 * | `1m`    | 20                          |
 * | `5m`    | 20                          |
 * | `15m`   | 4                           |
 * | `30m`   | 4                           |
 * | `1h`    | 4                           |
 *
 * A 1m cone settles sixty times an hour, so twenty settlements arrive in twenty minutes
 * and a 5% sampling error is not the dominant risk. A 1h cone needs four hours for four
 * settlements, so demanding twenty would withhold the horizon for most of a trading day;
 * four is the smallest sample that can still express 85% (4/4 = 100%, 3/4 = 75%) while
 * failing closed on anything smaller. The rate is compared with integer arithmetic
 * (`hits * 100 >= 85 * settled`), so 85.0% qualifies exactly and 84.99% does not.
 *
 * Below its floor a horizon's rate is not evidence, it is an anecdote, so an unproven
 * horizon is withheld rather than trusted — the gate fails closed. This is the **price
 * feeding** layer of the layered gate (`LayeredEvidenceGate`); §22's staging gate (C-19)
 * still requires its own evidence on top.
 *
 * Spec Ref: Phase 2 C-57, §22, §67
 */
object SignalEvidence {

    /**
     * A horizon must call its cone at least this often, as a percentage.
     *
     * Read from the one installed configuration on every access (C-60): this used to be a
     * constant that [com.aistudio.alphapulse.domain.campaign.LayeredEvidence] and
     * [CampaignReadinessGate] each re-read, so tuning the bar meant editing three files.
     */
    val MIN_HIT_RATE_PCT: Double get() = Thresholds.current().floorPct

    /** The shipped fast-tier floor, for documentation only; the live value is configured. */
    val MIN_SETTLED: Int get() = Thresholds.current().minSettledFast

    /**
     * The smallest sample that counts as evidence for [horizonId], as configured.
     *
     * Unknown horizon ids get the conservative short-horizon floor: an unregistered
     * horizon is not granted a smaller sample merely because nobody classified it.
     */
    fun minSettledFor(horizonId: String): Int = Thresholds.current().minSettledFor(horizonId)

    /**
     * One horizon's settlement record for one coin.
     *
     * @param settled predictions whose outcome is known
     * @param hits settled predictions whose realized price finished inside the cone
     */
    data class HorizonEvidence(
        val horizonId: String,
        val settled: Int,
        val hits: Int,
        val measuredAt: Long = 0L,
        val evaluatedAt: Long = 0L
    ) {
        /** This horizon's own sample floor. */
        val minSettled: Int get() = minSettledFor(horizonId)

        /** How long this horizon's evidence stays in force (C-61). */
        val windowMinutes: Int get() = Thresholds.current().inForceWindowMinutes(horizonId)

        /** Whether this measurement is still evidence (C-61). */
        val inForce: InForceStatus
            get() = EvidenceInForce.status("PRICE", measuredAt, evaluatedAt, windowMinutes)

        /** Whole minutes since this horizon was measured. */
        val ageMinutes: Long get() = inForce.ageMinutes

        /** Why this evidence is stale, or null while it is in force. */
        val staleReason: String? get() = inForce.reason

        /** Hits as a percentage of settled predictions; 0.0 when nothing settled. */
        val hitRatePct: Double get() = if (settled > 0) hits * 100.0 / settled else 0.0

        /** True when the sample is large enough, the rate clears the floor and the reading is fresh. */
        val qualified: Boolean get() = isQualified(horizonId, settled, hits, measuredAt, evaluatedAt)

        /**
         * The one-line account of why a horizon is (or is not) feeding campaigns, e.g.
         * `1h 71% of 34 settled (needs 85.0% of 4)`.
         */
        fun describe(): String {
            val rate = Math.round(hitRatePct * 10.0) / 10.0
            val base = "$horizonId $rate% of $settled settled " +
                "(needs ${Thresholds.current().describeFloor()} of $minSettled)"
            return if (staleReason == null) base else "$base · $staleReason"
        }
    }

    /**
     * The gate itself: enough settled predictions for this horizon, and enough of them
     * called correctly.
     *
     * Both halves are required, so nineteen perfect 1m calls and four mediocre 1h calls
     * are judged against their own floors rather than one shared sample size.
     */
    fun isQualified(
        horizonId: String,
        settled: Int,
        hits: Int,
        measuredAt: Long = 0L,
        nowMs: Long = 0L
    ): Boolean {
        val config = Thresholds.current()
        // C-61: a rate from outside its in-force window is not evidence, however good it
        // looks. Checked before the sample and the floor so a stale horizon is refused for
        // the reason that actually applies to it.
        val inForce = EvidenceInForce.status(
            label = "PRICE",
            measuredAt = measuredAt,
            nowMs = nowMs,
            windowMinutes = config.inForceWindowMinutes(horizonId)
        )
        if (!inForce.inForce) return false
        if (settled < config.minSettledFor(horizonId)) return false
        return config.isFloorReached(settled, hits)
    }

    /** Evidence for one horizon, clamped to a coherent record. */
    fun assess(
        horizonId: String,
        settled: Int,
        hits: Int,
        measuredAt: Long = 0L,
        nowMs: Long = 0L
    ): HorizonEvidence =
        HorizonEvidence(
            horizonId = horizonId,
            settled = settled.coerceAtLeast(0),
            hits = hits.coerceIn(0, settled.coerceAtLeast(0)),
            measuredAt = measuredAt,
            evaluatedAt = nowMs
        )
}
