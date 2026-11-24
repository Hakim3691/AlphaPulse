package com.aistudio.alphapulse.domain.campaign

import java.util.Locale

/**
 * C-60 — the hit-rate thresholds, configured once and read live by every gate.
 *
 * Why this exists
 * ---------------
 * The 85% floor was a constant in [SignalEvidence], re-read by [LayeredEvidence] and
 * defaulted again by [CampaignReadinessGate], and the sample floors were constants beside
 * it. Three copies of one number cannot be tuned: raising the bar for a quiet market meant
 * editing all three and hoping none drifted. The floor is now one persisted value, and every
 * consumer reads it through [Thresholds] at evaluation time, so a single change moves every
 * verdict that depends on it.
 *
 * The range is bounded on purpose. Below 50% a "proven" cone is a coin flip with a nicer
 * label; above 95% no horizon can prove itself and the desk is simply locked. The sample
 * floors are bounded too: a floor of zero would let PRICE — the layer that withholds
 * boards — qualify on no evidence at all, which is the one configuration this gate must
 * refuse rather than accept quietly.
 *
 * Spec Ref: Phase 2 C-57/C-58/C-60, §22, §67, §73, §74
 */

/**
 * @param floorPct the hit rate a layer must reach, in percent
 * @param minSettledFast settled rows required for the 1m/5m tier
 * @param minSettledSlow settled rows required for the 15m/30m/1h tier
 * @param minSettledStrategy graded strategy calls required for the pooled strategy layer
 */
data class ThresholdConfig(
    val floorPct: Double = DEFAULT_FLOOR_PCT,
    val minSettledFast: Int = DEFAULT_FAST_SAMPLES,
    val minSettledSlow: Int = DEFAULT_SLOW_SAMPLES,
    val minSettledStrategy: Int = DEFAULT_STRATEGY_SAMPLES,
    val evidenceTtlMinutes: Int = DEFAULT_EVIDENCE_TTL_MINUTES
) {

    /** This config's minimum settled rows for [horizonId]; unknown horizons get the slow tier. */
    fun minSettledFor(horizonId: String): Int = when (horizonId.trim().lowercase()) {
        "1m", "5m" -> minSettledFast
        "15m", "30m", "1h" -> minSettledSlow
        else -> minSettledSlow
    }

    /**
     * How many minutes a horizon id spans; a scope that is not a horizon (a coin symbol,
     * which is how the pooled STRATEGY layer is scoped) has no horizon length.
     */
    fun horizonMinutes(scope: String): Int = when (scope.trim().lowercase()) {
        "1m" -> 1
        "5m" -> 5
        "15m" -> 15
        "30m" -> 30
        "1h" -> 60
        else -> 0
    }

    /**
     * How long evidence measured at [scope] stays in force, in minutes.
     *
     * A long horizon settles slowly by design: a 1h cone's newest settlement is up to an
     * hour old the moment it lands, so holding it to the pooled window alone would declare
     * a perfectly current hourly read stale. Each horizon therefore gets the longer of the
     * configured window and twice its own span; scopes that are not horizons (the pooled
     * STRATEGY layer, scoped by coin) get the configured window unchanged.
     */
    fun inForceWindowMinutes(scope: String): Int =
        maxOf(evidenceTtlMinutes, horizonMinutes(scope) * 2)

    /** The in-force window as the operator reads it, e.g. `60 min`. */
    fun describeInForce(): String = "$evidenceTtlMinutes min"

    /** The floor as the operator reads it, e.g. `85.0%`. */
    fun describeFloor(): String = String.format(Locale.US, "%.1f%%", floorPct)

    /** The configured sample floor(s) for [layer], as the Thresholds tab prints them. */
    fun describeSamplesFor(layer: EvidenceLayer): String = when (layer) {
        EvidenceLayer.PRICE, EvidenceLayer.PATTERN -> "$minSettledFast fast / $minSettledSlow slow"
        EvidenceLayer.STRATEGY -> "$minSettledStrategy graded"
    }

    /** The configured value a [field] change moves, as recorded in the audit reason. */
    fun describeField(field: ThresholdField): String = when (field) {
        ThresholdField.FLOOR -> describeFloor()
        ThresholdField.FAST_SAMPLES -> minSettledFast.toString()
        ThresholdField.SLOW_SAMPLES -> minSettledSlow.toString()
        ThresholdField.STRATEGY_SAMPLES -> minSettledStrategy.toString()
        ThresholdField.IN_FORCE_MINUTES -> describeInForce()
        ThresholdField.RESET ->
            "${describeFloor()} · $minSettledFast fast · $minSettledSlow slow · " +
                "$minSettledStrategy graded · ${describeInForce()} in force"
    }

    /**
     * The inclusive floor comparison, at basis-point precision: 17 of 20 settled is exactly
     * 85.0% and qualifies, 84.99% does not, and a fractional configured floor is honoured
     * rather than truncated.
     */
    fun isFloorReached(settled: Int, hits: Int): Boolean {
        if (settled <= 0 || hits < 0 || hits > settled) return false
        return hits.toLong() * 10_000L >= floorBasisPoints.toLong() * settled.toLong()
    }

    /** [floorPct] in basis points, so the comparison is integer and exact. */
    val floorBasisPoints: Int get() = Math.round(floorPct * 100.0).toInt()

    /**
     * May this configuration become the live gate?
     *
     * Refusals name what is wrong rather than clamping silently, because the operator is
     * entitled to know that the number they entered was not the number in force.
     */
    fun validate(): ThresholdVerdict {
        if (!floorPct.isFinite() || floorPct < MIN_FLOOR_PCT || floorPct > MAX_FLOOR_PCT) {
            return ThresholdVerdict(
                accepted = false,
                rejection = "FLOOR_OUT_OF_RANGE_50_TO_95_PCT(got ${describeFloor()})"
            )
        }
        if (minSettledFast < 1 || minSettledSlow < 1) {
            // PRICE is the only blocking layer, so a zero sample floor there would let an
            // unproven price horizon read as proven and unblock staging.
            return ThresholdVerdict(
                accepted = false,
                rejection = "PRICE_UNPROVEN_BUT_UNBLOCKING(min settled " +
                    "${minOf(minSettledFast, minSettledSlow)}: a price horizon would " +
                    "qualify with no evidence)"
            )
        }
        if (minSettledStrategy < 1) {
            return ThresholdVerdict(
                accepted = false,
                rejection = "STRATEGY_SAMPLE_FLOOR_BELOW_1(got $minSettledStrategy)"
            )
        }
        if (evidenceTtlMinutes < MIN_EVIDENCE_TTL_MINUTES ||
            evidenceTtlMinutes > MAX_EVIDENCE_TTL_MINUTES
        ) {
            // A window under five minutes would expire 1m evidence before it could be read,
            // and one over a day is indistinguishable from trusting it forever.
            return ThresholdVerdict(
                accepted = false,
                rejection = "IN_FORCE_WINDOW_OUT_OF_RANGE_5_TO_1440_MIN(got ${evidenceTtlMinutes}min)"
            )
        }
        return ThresholdVerdict(accepted = true)
    }

    companion object {
        const val MIN_FLOOR_PCT = 50.0
        const val MAX_FLOOR_PCT = 95.0
        const val DEFAULT_FLOOR_PCT = 85.0
        const val DEFAULT_FAST_SAMPLES = 20
        const val DEFAULT_SLOW_SAMPLES = 4
        const val DEFAULT_STRATEGY_SAMPLES = 20
        const val MIN_EVIDENCE_TTL_MINUTES = 5
        const val MAX_EVIDENCE_TTL_MINUTES = 1440
        const val DEFAULT_EVIDENCE_TTL_MINUTES = 60

        /** The shipped configuration: exactly 85.0% and the documented sample floors. */
        val DEFAULT = ThresholdConfig()

        /** Keeps an operator's step inside the range; a non-finite input falls back. */
        fun clampFloor(pct: Double): Double =
            if (!pct.isFinite()) DEFAULT_FLOOR_PCT else pct.coerceIn(MIN_FLOOR_PCT, MAX_FLOOR_PCT)

        /** Keeps an in-force step inside the 5–1440 minute range. */
        fun clampInForceMinutes(minutes: Int): Int =
            minutes.coerceIn(MIN_EVIDENCE_TTL_MINUTES, MAX_EVIDENCE_TTL_MINUTES)
    }
}

/** Whether a candidate configuration may be applied, and why not when it may not. */
data class ThresholdVerdict(val accepted: Boolean, val rejection: String? = null)

/** Which threshold a change touches; a reset touches all of them. */
enum class ThresholdField {
    FLOOR,
    FAST_SAMPLES,
    SLOW_SAMPLES,
    STRATEGY_SAMPLES,
    IN_FORCE_MINUTES,
    RESET
}

/**
 * The one live configuration.
 *
 * Gates read it on every evaluation, and it is installed by the store at launch and by
 * [com.aistudio.alphapulse.data.repository.ThresholdConfigService] after every accepted
 * change — nowhere else. Holding it here rather than threading it through every call site
 * keeps the three consumers from growing optional copies again.
 */
object Thresholds {

    @Volatile
    private var current: ThresholdConfig = ThresholdConfig.DEFAULT

    fun current(): ThresholdConfig = current

    fun install(config: ThresholdConfig) {
        current = config
    }

    fun reset() {
        current = ThresholdConfig.DEFAULT
    }
}
