package com.aistudio.alphapulse.engine.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §25 / Contract C-40 — the four synchronised monitoring cadences.
 *
 * Spec Ref: Phase 2 §25
 */
class MonitoringCadenceBddSpec {

    @Test
    fun `given a fresh monitoring scheduler when a tick arrives then only the tick and fast telemetry cadences are due`() {
        val scheduler = MonitoringScheduler()

        val due = scheduler.dueCadences(
            nowMillis = 1_000L,
            oneMinuteCandleClosed = false,
            fiveMinuteCandleClosed = false,
            fifteenMinuteCandleClosed = false
        )

        assertEquals(
            listOf(MonitoringCadence.PER_TICK, MonitoringCadence.FAST_TELEMETRY),
            due
        )
    }

    @Test
    fun `given fast telemetry already ran when the next delta arrives within one second then only the tick cadence repeats`() {
        val scheduler = MonitoringScheduler()
        scheduler.dueCadences(1_000L, false, false, false)
            .forEach { scheduler.markRun(it, 1_000L) }

        val sameSecond = scheduler.dueCadences(1_200L, false, false, false)
        assertEquals(listOf(MonitoringCadence.PER_TICK), sameSecond)

        val nextSecond = scheduler.dueCadences(2_000L, false, false, false)
        assertTrue(
            "Fast telemetry is due again once its 1-5 second cadence elapses",
            nextSecond.contains(MonitoringCadence.FAST_TELEMETRY)
        )
    }

    @Test
    fun `given a closed one minute candle when the loop asks what is due then acceptance invalidation runs on that bar only`() {
        val scheduler = MonitoringScheduler()

        val onClose = scheduler.dueCadences(60_000L, true, false, false)
        assertTrue(onClose.contains(MonitoringCadence.CLOSED_1M))

        onClose.forEach { scheduler.markRun(it, 60_000L) }
        val midBar = scheduler.dueCadences(61_000L, false, false, false)
        assertFalse(
            "Acceptance invalidation must not run again inside the same 1m bar",
            midBar.contains(MonitoringCadence.CLOSED_1M)
        )
    }

    @Test
    fun `given a closed five minute candle when structural work already ran this bar then promotion waits for the next bar`() {
        val scheduler = MonitoringScheduler()

        val first = scheduler.dueCadences(300_000L, false, true, false)
        assertTrue(first.contains(MonitoringCadence.CLOSED_5M_15M))
        first.forEach { scheduler.markRun(it, 300_000L) }

        val duplicate = scheduler.dueCadences(301_000L, false, true, false)
        assertFalse(
            "A structural pass must not repeat inside the same 5m window",
            duplicate.contains(MonitoringCadence.CLOSED_5M_15M)
        )

        val nextBar = scheduler.dueCadences(600_000L, false, true, false)
        assertTrue(nextBar.contains(MonitoringCadence.CLOSED_5M_15M))
    }

    @Test
    fun `given each cadence when read then it orders exactly the actions section 25 assigns to it`() {
        assertEquals(
            listOf(
                MonitoringAction.RECONCILE_PARTIAL_FILLS,
                MonitoringAction.SPREAD_BLOWOUT_SAFETY
            ),
            MonitoringCadence.PER_TICK.actions()
        )
        assertEquals(
            listOf(MonitoringAction.RECOMPUTE_INVALIDATION_SCORE),
            MonitoringCadence.FAST_TELEMETRY.actions()
        )
        assertEquals(
            listOf(
                MonitoringAction.EVALUATE_ACCEPTANCE_INVALIDATION,
                MonitoringAction.CANCEL_UNFILLED_ON_ACCEPTANCE
            ),
            MonitoringCadence.CLOSED_1M.actions()
        )
        assertEquals(
            listOf(
                MonitoringAction.REFRESH_STRUCTURE_AND_ATR,
                MonitoringAction.EVALUATE_BOARD_PROMOTION,
                MonitoringAction.SUPPRESS_SECONDARY_OR_COOLDOWN
            ),
            MonitoringCadence.CLOSED_5M_15M.actions()
        )
    }
}
