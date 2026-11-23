package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §21 — one row per verdict, not one row per evaluation cycle.
 *
 * The engine evaluates every ten seconds around the clock, so a router that answers
 * `NEUTRAL` for an hour wrote 360 identical rows: the trade-decisions ledger became a
 * column of the same verdict with different timestamps, and each new one pushed the
 * verdict that actually changed something further up the table.
 *
 * A cycle that reproduces the verdict already at the top of the log is the same verdict,
 * so it replaces nothing and adds nothing. A changed state, a changed reference horizon, a
 * changed answering router or a different coin is a new verdict and must still be
 * recorded. Nothing is deleted: the earlier row keeps its own timestamp, which is when the
 * state began.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, C-29
 */
class DecisionCoalescingBddSpec {

    @Test
    fun `given no earlier verdict when a verdict is evaluated then it is recorded`() {
        assertFalse(DecisionCoalescing.supersedes(previous = null, next = verdict()))
    }

    @Test
    fun `given the same neutral verdict at the top of the log when the next cycle repeats it then no second row is written`() {
        val previous = verdict(at = 1_000L, state = "NEUTRAL")

        assertTrue(DecisionCoalescing.supersedes(previous, verdict(at = 11_000L, state = "NEUTRAL")))
    }

    @Test
    fun `given a neutral verdict when the router changes its answer then the new verdict is recorded`() {
        val previous = verdict(at = 1_000L, state = "NEUTRAL")

        assertFalse(DecisionCoalescing.supersedes(previous, verdict(at = 11_000L, state = "LONG")))
    }

    @Test
    fun `given a verdict on one horizon when the reference horizon changes then the new verdict is recorded`() {
        val previous = verdict(at = 1_000L, horizon = "15m")

        assertFalse(DecisionCoalescing.supersedes(previous, verdict(at = 11_000L, horizon = "1h")))
    }

    @Test
    fun `given a verdict answered by rules when Needle 3 answers next then the new verdict is recorded`() {
        val previous = verdict(at = 1_000L, source = "DETERMINISTIC")

        assertFalse(DecisionCoalescing.supersedes(previous, verdict(at = 11_000L, source = "NEEDLE3")))
    }

    @Test
    fun `given a verdict for one coin when another coin is evaluated then its verdict is still recorded`() {
        val previous = verdict(at = 1_000L, symbol = "SOLUSDT")

        assertFalse(DecisionCoalescing.supersedes(previous, verdict(at = 11_000L, symbol = "USDBRLUSDT")))
    }

    private fun verdict(
        at: Long = 1_000L,
        state: String = "NEUTRAL",
        source: String = "NEEDLE3",
        horizon: String = "15m",
        symbol: String = "SOLUSDT"
    ) = DecisionRecordEntity(
        id = "$symbol#$at",
        symbol = symbol,
        issuedTimestamp = at,
        state = state,
        confidence = 0.42,
        source = source,
        referenceHorizon = horizon,
        reason = "Needle 3: hold"
    )
}
