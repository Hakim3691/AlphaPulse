package com.aistudio.alphapulse.engine.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §28 / Contract C-42 — side-agnostic invalidation.
 *
 * Every trigger is evaluated twice, once on a long board and once on its mirrored
 * short board, so a semantics that only exists on one side fails the build.
 *
 * Spec Ref: Phase 2 §28
 */
class SideAgnosticInvalidationBddSpec {

    private val scorer = SideAgnosticInvalidation()

    private fun evidence(
        oneMinuteClose: Double = 100.0,
        zoneLow: Double = 99.0,
        zoneHigh: Double = 101.0,
        counterImpulseQuality: Double = 0.90,
        reclaimReversedInstantly: Boolean = false,
        priceChangePct: Double = 0.10,
        oiDelta1hPct: Double = 0.20,
        takerBuyRatio: Double = 0.50,
        minutesInsideZone: Long = 5L
    ) = SideAgnosticEvidence(
        oneMinuteClose = oneMinuteClose,
        zoneLow = zoneLow,
        zoneHigh = zoneHigh,
        counterImpulseQuality = counterImpulseQuality,
        reclaimReversedInstantly = reclaimReversedInstantly,
        priceChangePct = priceChangePct,
        oiDelta1hPct = oiDelta1hPct,
        takerBuyRatio = takerBuyRatio,
        minutesInsideZone = minutesInsideZone
    )

    private fun triggers(isLong: Boolean, evidence: SideAgnosticEvidence): List<SideAgnosticTrigger> =
        scorer.evaluate(isLong, evidence, timeBudgetMinutes = 180L).map { it.trigger }

    @Test
    fun `given an intact thesis when either side is scored then no trigger fires and the score is zero`() {
        assertEquals(emptyList<SideAgnosticTrigger>(), triggers(isLong = true, evidence()))
        assertEquals(emptyList<SideAgnosticTrigger>(), triggers(isLong = false, evidence()))
        assertEquals(0, scorer.score(true, evidence(), 180L))
        assertEquals(0, scorer.score(false, evidence(), 180L))
    }

    @Test
    fun `given price accepted past the zone edge when each side is scored then acceptance fires in its own direction only`() {
        // Long support: a 1m close below the shelf edge.
        assertEquals(
            listOf(SideAgnosticTrigger.ACCEPTANCE),
            triggers(isLong = true, evidence(oneMinuteClose = 98.5))
        )
        // The same below-shelf close is not an invalidation of a short board.
        assertEquals(
            emptyList<SideAgnosticTrigger>(),
            triggers(isLong = false, evidence(oneMinuteClose = 98.5))
        )
        // Short supply: a 1m close above the band.
        assertEquals(
            listOf(SideAgnosticTrigger.ACCEPTANCE),
            triggers(isLong = false, evidence(oneMinuteClose = 101.5))
        )
    }

    @Test
    fun `given no counter impulse at the level when either side is scored then rejection failure fires`() {
        assertEquals(
            listOf(SideAgnosticTrigger.REJECTION_FAILURE),
            triggers(isLong = true, evidence(counterImpulseQuality = 0.10))
        )
        assertEquals(
            listOf(SideAgnosticTrigger.REJECTION_FAILURE),
            triggers(isLong = false, evidence(counterImpulseQuality = 0.10))
        )
    }

    @Test
    fun `given a reclaim that reversed instantly when either side is scored then trap failure fires`() {
        assertEquals(
            listOf(SideAgnosticTrigger.TRAP_FAILURE),
            triggers(isLong = true, evidence(reclaimReversedInstantly = true))
        )
        assertEquals(
            listOf(SideAgnosticTrigger.TRAP_FAILURE),
            triggers(isLong = false, evidence(reclaimReversedInstantly = true))
        )
    }

    @Test
    fun `given open interest rising against a falling long or a rising short when scored then flow contradiction fires`() {
        val againstLong = evidence(priceChangePct = -1.20, oiDelta1hPct = 1.10, takerBuyRatio = 0.40)
        assertEquals(
            listOf(SideAgnosticTrigger.FLOW_CONTRADICTION),
            triggers(isLong = true, againstLong)
        )

        val againstShort = evidence(priceChangePct = 1.20, oiDelta1hPct = 1.10, takerBuyRatio = 0.60)
        assertEquals(
            listOf(SideAgnosticTrigger.FLOW_CONTRADICTION),
            triggers(isLong = false, againstShort)
        )

        // Remaining flow with a favourable price move is not a contradiction.
        assertEquals(
            emptyList<SideAgnosticTrigger>(),
            triggers(isLong = true, againstLong.copy(priceChangePct = 0.4))
        )
    }

    @Test
    fun `given price dead inside the zone beyond its time budget when either side is scored then time stagnation fires`() {
        assertEquals(
            listOf(SideAgnosticTrigger.TIME_STAGNATION),
            triggers(isLong = true, evidence(minutesInsideZone = 200L))
        )
        assertEquals(
            listOf(SideAgnosticTrigger.TIME_STAGNATION),
            triggers(isLong = false, evidence(minutesInsideZone = 200L))
        )
        // Inside the budget, or outside the zone, is not stagnation.
        assertEquals(
            emptyList<SideAgnosticTrigger>(),
            triggers(isLong = true, evidence(minutesInsideZone = 30L))
        )
        assertEquals(
            emptyList<SideAgnosticTrigger>(),
            triggers(
                isLong = true,
                evidence(oneMinuteClose = 105.0, minutesInsideZone = 400L)
            )
        )
    }

    @Test
    fun `given every co-fireable trigger firing together when scored then the 0 to 100 gauge clamps instead of overflowing`() {
        // Acceptance (a close past the edge) and Time Stagnation (a close inside the
        // zone) are mutually exclusive by definition, so the worst real evidence is
        // the other four at once: 35 + 25 + 30 + 25 = 115, clamped to 100.
        val everything = evidence(
            oneMinuteClose = 98.0,
            counterImpulseQuality = 0.0,
            reclaimReversedInstantly = true,
            priceChangePct = -2.0,
            oiDelta1hPct = 2.0,
            takerBuyRatio = 0.2
        )

        val readings = scorer.evaluate(isLong = true, everything, timeBudgetMinutes = 180L)
        assertEquals(4, readings.size)
        assertEquals(100, scorer.score(true, everything, 180L))
        assertTrue(scorer.isHardInvalidated(100))
    }
}
