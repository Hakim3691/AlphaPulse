package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.BoardRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §26 / Contract C-41 — board survival monitoring.
 *
 * Spec Ref: Phase 2 §26
 */
class BoardSurvivalMonitorBddSpec {

    private val monitor = BoardSurvivalMonitor()

    private fun input(
        role: BoardRole = BoardRole.PRIMARY,
        isLong: Boolean = true,
        priceChangePct: Double = 0.10,
        oiDelta1hPct: Double = 0.20,
        hasFilled: Boolean = false,
        primaryStalled: Boolean = false,
        secondaryVolumeConfirmed: Boolean = false,
        structuralIntegrityBroken: Boolean = false
    ) = BoardSurvivalInput(
        campaignId = "SOLUSDT_SUPPORT_LONG",
        role = role,
        isLong = isLong,
        priceChangePct = priceChangePct,
        oiDelta1hPct = oiDelta1hPct,
        hasFilled = hasFilled,
        primaryStalled = primaryStalled,
        secondaryVolumeConfirmed = secondaryVolumeConfirmed,
        structuralIntegrityBroken = structuralIntegrityBroken
    )

    @Test
    fun `given open interest spiking against a stalled board when evaluated then it is degraded and cancelled before fills`() {
        val verdict = monitor.evaluate(
            input(priceChangePct = 0.05, oiDelta1hPct = 1.80)
        )

        assertEquals(BoardSurvivalAction.DEGRADE_AND_CANCEL, verdict.action)
        assertEquals(0.0, verdict.sizeMultiplier, 0.0)
        assertTrue(verdict.explanation.contains("OI"))
    }

    @Test
    fun `given open interest spiking while price is moving with the board when evaluated then the board is kept`() {
        val verdict = monitor.evaluate(
            input(priceChangePct = 1.20, oiDelta1hPct = 1.80)
        )

        assertEquals(BoardSurvivalAction.KEEP, verdict.action)
    }

    @Test
    fun `given a stalled primary and a volume-confirmed secondary when evaluated then the secondary is promoted with more size`() {
        val verdict = monitor.evaluate(
            input(
                role = BoardRole.SECONDARY,
                primaryStalled = true,
                secondaryVolumeConfirmed = true
            )
        )

        assertEquals(BoardSurvivalAction.PROMOTE_TO_PRIMARY, verdict.action)
        assertTrue(
            "Promotion must carry more size than the secondary role had",
            verdict.sizeMultiplier > 1.0
        )
    }

    @Test
    fun `given a secondary whose structural zone is gone when evaluated then it is suppressed rather than left resting`() {
        val verdict = monitor.evaluate(
            input(
                role = BoardRole.SECONDARY,
                primaryStalled = true,
                secondaryVolumeConfirmed = true,
                structuralIntegrityBroken = true
            )
        )

        assertEquals(BoardSurvivalAction.SUPPRESS_SECONDARY, verdict.action)
        assertEquals(0.0, verdict.sizeMultiplier, 0.0)
    }

    @Test
    fun `given a board that already holds a fill when evaluated then survival leaves it to invalidation and the hard stop`() {
        val verdict = monitor.evaluate(
            input(hasFilled = true, priceChangePct = 0.0, oiDelta1hPct = 5.0)
        )

        assertEquals(BoardSurvivalAction.KEEP, verdict.action)
    }
}
