package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.BoardRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §29 / Contract C-43 — capital routing for live boards.
 *
 * Spec Ref: Phase 2 §29, §17
 */
class CapitalRouterBddSpec {

    private val router = CapitalRouter()

    private val boards = listOf(
        RoutedBoard("SOL_PRIMARY", BoardRole.PRIMARY, 92.0, 5_000.0),
        RoutedBoard("SOL_SECONDARY", BoardRole.SECONDARY, 80.0, 2_500.0),
        RoutedBoard("SOL_DEFENSIVE", BoardRole.DEFENSIVE, 40.0, 1_000.0)
    )

    @Test
    fun `given normal market correlation when routed then every board keeps its role budget`() {
        val decision = router.route(boards, marketCorrelation = 0.42)

        assertFalse(decision.crisisMode)
        assertTrue(decision.cancelledBoardIds.isEmpty())
        assertEquals(3, decision.retainedBoardIds.size)
        assertNull(decision.concentratedBoardId)
        assertEquals(0.0, decision.releasedNotionalUsd, 0.0)
    }

    @Test
    fun `given correlation above 0_85 when routed then defensive and secondary boards are cancelled first and capital concentrates on the primary`() {
        val decision = router.route(boards, marketCorrelation = 0.91)

        assertTrue(decision.crisisMode)
        assertEquals(
            setOf("SOL_SECONDARY", "SOL_DEFENSIVE"),
            decision.cancelledBoardIds.toSet()
        )
        assertEquals(listOf("SOL_PRIMARY"), decision.retainedBoardIds)
        assertEquals("SOL_PRIMARY", decision.concentratedBoardId)
        assertEquals(3_500.0, decision.releasedNotionalUsd, 0.0)
        assertTrue(decision.reason.contains("0.91"))
    }

    @Test
    fun `given two primaries in a correlation crisis when routed then only the highest-ranking primary survives`() {
        val twoPrimaries = boards + RoutedBoard("SOL_PRIMARY_2", BoardRole.PRIMARY, 70.0, 4_000.0)

        val decision = router.route(twoPrimaries, marketCorrelation = 0.95)

        assertEquals(
            listOf("SOL_PRIMARY"),
            decision.retainedBoardIds
        )
        assertTrue(decision.cancelledBoardIds.contains("SOL_PRIMARY_2"))
        assertTrue(decision.cancelledBoardIds.contains("SOL_SECONDARY"))
        assertTrue(decision.cancelledBoardIds.contains("SOL_DEFENSIVE"))
    }

    @Test
    fun `given no primary board in a correlation crisis when routed then the strongest remaining board is concentrated on`() {
        val noPrimary = boards.filter { it.role != BoardRole.PRIMARY }

        val decision = router.route(noPrimary, marketCorrelation = 0.90)

        assertEquals("SOL_SECONDARY", decision.concentratedBoardId)
        assertEquals(listOf("SOL_SECONDARY"), decision.retainedBoardIds)
    }

    @Test
    fun `given correlation exactly at the 0_85 threshold when routed then the normal band still holds`() {
        val decision = router.route(boards, marketCorrelation = 0.85)

        assertFalse(
            "The rule triggers only when correlation exceeds 0.85",
            decision.crisisMode
        )
    }
}
