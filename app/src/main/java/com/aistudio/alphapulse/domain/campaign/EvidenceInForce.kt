package com.aistudio.alphapulse.domain.campaign

/**
 * C-61 — how long a measurement stays evidence, decided in one place.
 *
 * Why this exists
 * ---------------
 * A hit rate is a measurement of the past. Once it is written it never changed again:
 * a coin whose cone last settled an afternoon ago was still read as proven, a pattern
 * ledger that stopped receiving rows kept its 85%, and a campaign could stage on a
 * reading nobody had refreshed. The thresholds are configured (C-60), so the window a
 * reading stays in force belongs beside them, and every gate asks this helper rather
 * than deciding for itself.
 *
 * The rule is deliberately simple and pure: a measurement is in force while its age is
 * within the configured window, inclusive at the boundary. A record with no timestamp
 * fails closed — an unstamped reading is not a fresh reading, and silence must never be
 * trusted as evidence.
 *
 * Spec Ref: Phase 2 C-57/C-58/C-60/C-61, §22, §67, §68
 */

/**
 * Whether one measurement may still be treated as evidence.
 *
 * @param inForce true while the measurement is inside its window
 * @param ageMinutes whole minutes since measurement; -1 when the record is unstamped
 * @param windowMinutes the configured window this check used
 * @param reason the refusal, naming the layer, the age and the window; null when in force
 */
data class InForceStatus(
    val inForce: Boolean,
    val ageMinutes: Long,
    val windowMinutes: Int,
    val reason: String?
)

object EvidenceInForce {

    /** Reported age for a record that carries no measurement time. */
    const val UNSTAMPED_AGE_MINUTES: Long = -1L

    /**
     * @param label the layer the measurement belongs to (`PRICE`, `PATTERN`, `STRATEGY`
     *   or `HORIZON_15m_CONE`), used to name the refusal
     * @param measuredAt epoch millis the reading was taken; a non-positive value is unstamped
     * @param nowMs epoch millis the gate is evaluating at; a non-positive value cannot
     *   establish an age, so it fails closed
     */
    fun status(label: String, measuredAt: Long, nowMs: Long, windowMinutes: Int): InForceStatus {
        // No reading time, or no evaluation time, cannot establish an age. Both fail closed:
        // silence must never be read as "measured a moment ago".
        if (measuredAt <= 0L || nowMs <= 0L) {
            return InForceStatus(
                inForce = false,
                ageMinutes = UNSTAMPED_AGE_MINUTES,
                windowMinutes = windowMinutes,
                reason = "${label}_EVIDENCE_STALE(no measurement timestamp, " +
                    "in-force window ${windowMinutes}min)"
            )
        }
        // Whole minutes, floored, and never negative: a timestamp slightly ahead of the
        // evaluating clock is clock skew, not evidence from the future.
        val ageMinutes = ((nowMs - measuredAt) / MINUTE_MS).coerceAtLeast(0L)
        if (ageMinutes > windowMinutes.toLong()) {
            return InForceStatus(
                inForce = false,
                ageMinutes = ageMinutes,
                windowMinutes = windowMinutes,
                reason = "${label}_EVIDENCE_STALE(age ${ageMinutes}min, " +
                    "in-force window ${windowMinutes}min)"
            )
        }
        return InForceStatus(
            inForce = true,
            ageMinutes = ageMinutes,
            windowMinutes = windowMinutes,
            reason = null
        )
    }

    private const val MINUTE_MS = 60_000L
}
