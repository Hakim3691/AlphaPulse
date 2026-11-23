package com.aistudio.alphapulse.domain.strategy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C-58 — a §74 strategy signal is graded, not merely recorded.
 *
 * Given/When/Then:
 *  - given a long whose entry trades before its invalidation, then the call is a hit.
 *  - given a long whose invalidation trades, then the call is a miss.
 *  - given a bar that touches both levels, then the call is a miss (no tick data, so the
 *    conservative reading is the only one that cannot overstate the rule).
 *  - given a bar that touches neither, then the call is a miss rather than a neutral.
 *  - given a neutral or level-less signal, then it is ungraded and excluded.
 *
 * Spec Ref: §74, C-58
 */
class StrategyOutcomeGraderBddSpec {

    @Test
    fun `given a long whose entry trades above its invalidation when graded then it is a hit`() {
        val outcome = StrategyOutcomeGrader.grade(
            direction = SignalDirection.BULLISH,
            entry = 100.0,
            invalidation = 98.0,
            open = 101.0,
            high = 101.5,
            low = 99.5,
            close = 100.5
        )

        assertEquals(StrategyOutcome.HIT, outcome)
    }

    @Test
    fun `given a long whose invalidation trades when graded then it is a miss`() {
        val outcome = StrategyOutcomeGrader.grade(
            direction = SignalDirection.BULLISH,
            entry = 100.0,
            invalidation = 98.0,
            open = 101.0,
            high = 101.5,
            low = 97.5,
            close = 98.5
        )

        assertEquals(StrategyOutcome.MISS, outcome)
    }

    @Test
    fun `given a bar that touches the entry and the invalidation when graded then the unknown order is read as a miss`() {
        val outcome = StrategyOutcomeGrader.grade(
            direction = SignalDirection.BULLISH,
            entry = 100.0,
            invalidation = 98.0,
            open = 101.0,
            high = 101.5,
            low = 97.0,
            close = 100.0
        )

        assertEquals(StrategyOutcome.MISS, outcome)
    }

    @Test
    fun `given a short whose entry trades below its invalidation when graded then it is a hit`() {
        val outcome = StrategyOutcomeGrader.grade(
            direction = SignalDirection.BEARISH,
            entry = 100.0,
            invalidation = 102.0,
            open = 99.0,
            high = 100.5,
            low = 98.5,
            close = 99.5
        )

        assertEquals(StrategyOutcome.HIT, outcome)
    }

    @Test
    fun `given a short whose invalidation trades when graded then it is a miss`() {
        val outcome = StrategyOutcomeGrader.grade(
            direction = SignalDirection.BEARISH,
            entry = 100.0,
            invalidation = 102.0,
            open = 99.0,
            high = 102.5,
            low = 98.5,
            close = 101.0
        )

        assertEquals(StrategyOutcome.MISS, outcome)
    }

    @Test
    fun `given a bar that touches neither level when graded then silence is a miss not a neutral`() {
        val outcome = StrategyOutcomeGrader.grade(
            direction = SignalDirection.BULLISH,
            entry = 100.0,
            invalidation = 98.0,
            open = 102.0,
            high = 103.0,
            low = 101.0,
            close = 102.5
        )

        assertEquals(StrategyOutcome.MISS, outcome)
    }

    @Test
    fun `given a neutral or level-less signal when graded then it makes no claim and is excluded`() {
        assertEquals(
            StrategyOutcome.UNGRADED,
            StrategyOutcomeGrader.grade(
                SignalDirection.NEUTRAL, 100.0, 98.0, 100.0, 101.0, 99.0, 100.0
            )
        )
        assertEquals(
            StrategyOutcome.UNGRADED,
            StrategyOutcomeGrader.grade(
                SignalDirection.BULLISH, null, 98.0, 100.0, 101.0, 99.0, 100.0
            )
        )
        assertEquals(
            StrategyOutcome.UNGRADED,
            StrategyOutcomeGrader.grade(
                SignalDirection.BULLISH, 100.0, null, 100.0, 101.0, 99.0, 100.0
            )
        )
    }

    @Test
    fun `given a long whose invalidation is above its entry when graded then the malformed claim is ungraded`() {
        assertEquals(
            StrategyOutcome.UNGRADED,
            StrategyOutcomeGrader.grade(
                SignalDirection.BULLISH, 100.0, 102.0, 100.0, 101.0, 99.0, 100.0
            )
        )
    }
}
