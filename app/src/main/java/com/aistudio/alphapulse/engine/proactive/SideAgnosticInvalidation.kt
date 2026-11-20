package com.aistudio.alphapulse.engine.proactive

/**
 * §28 — the side-agnostic invalidation model.
 *
 * A long board and a short board are invalidated by the same five semantics, only
 * mirrored: acceptance past the zone edge, a failed counter-impulse (rejection
 * failure), a reclaim that reverses instantly (trap failure), flow that contradicts
 * the thesis, and price that simply stagnates inside the zone beyond its budget.
 * The scorer takes the side as a parameter and evaluates every trigger for it rather
 * than keeping a long-only branch, so a short board can never be missing a trigger
 * the long board has.
 *
 * Spec Ref: Phase 2 §28, §27
 */
enum class SideAgnosticTrigger(val label: String, val points: Int) {
    NONE("None", 0),
    ACCEPTANCE("Acceptance", 35),
    REJECTION_FAILURE("Rejection Failure", 25),
    TRAP_FAILURE("Trap Failure", 30),
    FLOW_CONTRADICTION("Flow Contradiction", 25),
    TIME_STAGNATION("Time Stagnation", 20)
}

/** One observation of the evidence the five triggers read. */
data class SideAgnosticEvidence(
    /** The last closed 1m candle; the cadence that owns acceptance. */
    val oneMinuteClose: Double,
    val zoneLow: Double,
    val zoneHigh: Double,
    /** Buy response at a support shelf, or sell response at a supply shelf, 0..1. */
    val counterImpulseQuality: Double,
    /** A reclaim that printed and immediately reversed (trap). */
    val reclaimReversedInstantly: Boolean,
    /** Price change over the evidence window, in percent. */
    val priceChangePct: Double,
    /** Open-interest change over the last hour, in percent. */
    val oiDelta1hPct: Double,
    /** Aggressive taker buys as a share of taker volume, 0..1. */
    val takerBuyRatio: Double,
    /** How long price has sat inside the board's zone. */
    val minutesInsideZone: Long
)

/** One triggered semantic, with the points it contributes and why it fired. */
data class SideAgnosticReading(
    val trigger: SideAgnosticTrigger,
    val isLong: Boolean,
    val points: Int,
    val explanation: String
)

class SideAgnosticInvalidation(
    private val config: ProductionExecutionConfig = ProductionExecutionConfig()
) {

    /**
     * Evaluates all five triggers for [isLong] and returns the ones that fired.
     * An empty list means the thesis is intact; the caller maps the points onto the
     * §27 45/70 thresholds.
     */
    fun evaluate(
        isLong: Boolean,
        evidence: SideAgnosticEvidence,
        timeBudgetMinutes: Long
    ): List<SideAgnosticReading> {
        val readings = mutableListOf<SideAgnosticReading>()
        val side = if (isLong) "Long" else "Short"

        // (1) Acceptance: price builds value past the zone edge — below the shelf for
        // a long, above the supply band for a short.
        val acceptedPastEdge = if (isLong) {
            evidence.oneMinuteClose < evidence.zoneLow
        } else {
            evidence.oneMinuteClose > evidence.zoneHigh
        }
        if (acceptedPastEdge) {
            val edge = if (isLong) evidence.zoneLow else evidence.zoneHigh
            readings += SideAgnosticReading(
                SideAgnosticTrigger.ACCEPTANCE,
                isLong,
                SideAgnosticTrigger.ACCEPTANCE.points,
                "$side board: 1m close ${evidence.oneMinuteClose} accepted past zone edge $edge"
            )
        }

        // (2) Rejection Failure: no counter-impulse at the level. A support bid with
        // no buy response (or a supply offer with no sell response) has no thesis.
        if (evidence.counterImpulseQuality < MIN_COUNTER_IMPULSE_QUALITY) {
            readings += SideAgnosticReading(
                SideAgnosticTrigger.REJECTION_FAILURE,
                isLong,
                SideAgnosticTrigger.REJECTION_FAILURE.points,
                "$side board: counter-impulse quality " +
                    "${(evidence.counterImpulseQuality * 100).toInt()}% below the " +
                    "${(MIN_COUNTER_IMPULSE_QUALITY * 100).toInt()}% floor"
            )
        }

        // (3) Trap Failure: the reclaim printed and reversed instantly.
        if (evidence.reclaimReversedInstantly) {
            readings += SideAgnosticReading(
                SideAgnosticTrigger.TRAP_FAILURE,
                isLong,
                SideAgnosticTrigger.TRAP_FAILURE.points,
                "$side board: reclaim reversed instantly; the level never held"
            )
        }

        // (4) Flow Contradiction: OI rising against the position while taker flow
        // leans the other way.
        val flowContradicts = if (isLong) {
            evidence.priceChangePct < 0.0 &&
                evidence.oiDelta1hPct >= MIN_CONTRA_OI_DELTA_PCT &&
                evidence.takerBuyRatio < NEUTRAL_TAKER_RATIO
        } else {
            evidence.priceChangePct > 0.0 &&
                evidence.oiDelta1hPct >= MIN_CONTRA_OI_DELTA_PCT &&
                evidence.takerBuyRatio > NEUTRAL_TAKER_RATIO
        }
        if (flowContradicts) {
            val flow = if (isLong) "net taker selling" else "persistent taker buying"
            readings += SideAgnosticReading(
                SideAgnosticTrigger.FLOW_CONTRADICTION,
                isLong,
                SideAgnosticTrigger.FLOW_CONTRADICTION.points,
                "$side board: price ${evidence.priceChangePct}% with OI " +
                    "+${evidence.oiDelta1hPct}% and $flow contradicts the thesis"
            )
        }

        // (5) Time Stagnation: price dead inside the zone beyond its time budget.
        val insideZone = evidence.oneMinuteClose in evidence.zoneLow..evidence.zoneHigh
        if (insideZone && evidence.minutesInsideZone > timeBudgetMinutes) {
            readings += SideAgnosticReading(
                SideAgnosticTrigger.TIME_STAGNATION,
                isLong,
                SideAgnosticTrigger.TIME_STAGNATION.points,
                "$side board: stagnant inside zone for ${evidence.minutesInsideZone}m " +
                    "(budget ${timeBudgetMinutes}m)"
            )
        }

        return readings
    }

    /** The §27 0-100 score for one board, clamped so it can never exceed the gauge. */
    fun score(
        isLong: Boolean,
        evidence: SideAgnosticEvidence,
        timeBudgetMinutes: Long
    ): Int = evaluate(isLong, evidence, timeBudgetMinutes)
        .sumOf { it.points }
        .coerceIn(0, 100)

    /** True once the score reaches the §34 hard threshold. */
    fun isHardInvalidated(score: Int): Boolean = score >= config.hardInvalidationThreshold

    /** True once the score reaches the §34 soft threshold but not the hard one. */
    fun isSoftInvalidated(score: Int): Boolean =
        score >= config.softInvalidationThreshold && score < config.hardInvalidationThreshold

    companion object {
        const val MIN_COUNTER_IMPULSE_QUALITY = 0.35
        const val MIN_CONTRA_OI_DELTA_PCT = 0.8
        const val NEUTRAL_TAKER_RATIO = 0.5
    }
}
