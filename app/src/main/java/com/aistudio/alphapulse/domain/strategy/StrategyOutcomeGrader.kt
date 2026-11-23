package com.aistudio.alphapulse.domain.strategy

/**
 * C-58 — a §74 strategy signal is graded, not merely recorded.
 *
 * Why this exists
 * ---------------
 * The strategy ledger (C-56) persists what each rule said on the bar it read, but a
 * signal with no outcome cannot answer the only question that matters to the layered
 * gate: *was this rule right?* The gate therefore grades every row, once, against the
 * bar that follows the one it was issued on.
 *
 * The grading rule was chosen deliberately:
 *
 *  - **No lookahead.** A signal issued on bar `T` is graded on bar `T+1` only. Using the
 *    same bar's high/low would let the rule be judged by the wick it was reacting to.
 *  - **Fill before invalidation is a hit.** A signal hits when its entry is traded
 *    before its invalidation in the grading bar.
 *  - **Invalidation first, or neither touched, is a miss.** A rule that never fills has
 *    not earned a hit; treating "nothing happened" as neutral would inflate the rate.
 *  - **A bar that touched both is a miss.** Without tick data the order inside a bar is
 *    unknown, and the conservative reading is the one that cannot overstate the rule.
 *  - **NEUTRAL, or a signal with no entry/invalidation, is ungraded and excluded.**
 *    "This rule saw nothing" is evidence that it was silent, not evidence that it was
 *    right.
 *
 * Spec Ref: §74, C-58
 */
enum class StrategyOutcome {
    /** The entry traded before the invalidation in the grading bar. */
    HIT,

    /** The invalidation came first, both levels were touched, or neither was. */
    MISS,

    /** A neutral or level-less signal: no claim was made, so no grade is credited. */
    UNGRADED
}

object StrategyOutcomeGrader {

    /**
     * Grades one signal against the bar after the one it was issued on.
     *
     * @param direction the rule's claim
     * @param entry the level the rule wanted to trade
     * @param invalidation the level at which the claim is wrong
     * @param open the grading bar's open
     * @param high the grading bar's high
     * @param low the grading bar's low
     * @param close the grading bar's close (reserved for callers that need it; the
     *   conservative two-level rule above does not depend on the close)
     */
    fun grade(
        direction: SignalDirection,
        entry: Double?,
        invalidation: Double?,
        open: Double,
        high: Double,
        low: Double,
        close: Double
    ): StrategyOutcome {
        // A silent rule made no claim. Neither did a rule that named only one of the two
        // levels it needs to be judged by.
        if (direction == SignalDirection.NEUTRAL) return StrategyOutcome.UNGRADED
        if (entry == null || invalidation == null) return StrategyOutcome.UNGRADED
        if (!entry.isFinite() || !invalidation.isFinite()) return StrategyOutcome.UNGRADED
        if (high < low) return StrategyOutcome.UNGRADED

        return when (direction) {
            SignalDirection.BULLISH -> {
                // A long entry is below the market; its invalidation is below the entry.
                if (invalidation >= entry) return StrategyOutcome.UNGRADED
                val entryTouched = low <= entry
                val invalidated = low <= invalidation
                if (invalidated) StrategyOutcome.MISS
                else if (entryTouched) StrategyOutcome.HIT
                else StrategyOutcome.MISS
            }

            SignalDirection.BEARISH -> {
                // A short entry is above the market; its invalidation is above the entry.
                if (invalidation <= entry) return StrategyOutcome.UNGRADED
                val entryTouched = high >= entry
                val invalidated = high >= invalidation
                if (invalidated) StrategyOutcome.MISS
                else if (entryTouched) StrategyOutcome.HIT
                else StrategyOutcome.MISS
            }

            SignalDirection.NEUTRAL -> StrategyOutcome.UNGRADED
        }
    }
}
