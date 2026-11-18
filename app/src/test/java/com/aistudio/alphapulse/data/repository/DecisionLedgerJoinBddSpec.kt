package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.db.PredictionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grading the decisions ledger against the price ledger.
 *
 * Before this, a verdict was never settled: the table stored the state, confidence,
 * source and reason and nothing ever compared it with what price did, so every row
 * stayed pending for good. These scenarios pin the grading rules.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Decision Router, C-10/C-29
 */
class DecisionLedgerJoinBddSpec {

    @Test
    fun `given a long verdict and a settled forecast that rose when graded then it is a hit`() {
        val rows = DecisionLedgerJoin.settle(
            records = listOf(verdict(state = "LONG", at = 10_000L, horizon = "5m")),
            predictions = listOf(forecast(horizon = "5m", issued = 10_000L, target = 310_000L, base = 100.0, realized = 101.0))
        )

        assertEquals(1, rows.size)
        assertTrue("the covering settled forecast grades the verdict", rows.single().settled)
        assertEquals(true, rows.single().hit)
        assertEquals(1.0, rows.single().realizedPct!!, 1e-9)
    }

    @Test
    fun `given a short verdict and a settled forecast that rose when graded then it is a miss`() {
        val rows = DecisionLedgerJoin.settle(
            records = listOf(verdict(state = "STRONG_SHORT", at = 10_000L, horizon = "5m")),
            predictions = listOf(forecast(horizon = "5m", issued = 10_000L, target = 310_000L, base = 100.0, realized = 101.0))
        )

        assertEquals(false, rows.single().hit)
    }

    @Test
    fun `given a neutral verdict when price stayed inside the cone then it is a hit and a breach is a miss`() {
        val inside = DecisionLedgerJoin.settle(
            records = listOf(verdict(state = "NEUTRAL", at = 10_000L, horizon = "15m")),
            predictions = listOf(
                forecast(horizon = "15m", issued = 10_000L, target = 910_000L, base = 100.0, realized = 100.4, inCone = true)
            )
        )
        val outside = DecisionLedgerJoin.settle(
            records = listOf(verdict(state = "NEUTRAL", at = 10_000L, horizon = "15m")),
            predictions = listOf(
                forecast(horizon = "15m", issued = 10_000L, target = 910_000L, base = 100.0, realized = 104.0, inCone = false)
            )
        )

        assertEquals(true, inside.single().hit)
        assertEquals(false, outside.single().hit)
    }

    @Test
    fun `given a verdict issued mid-forecast when the covering row settles then the verdict settles too`() {
        // Decisions are recorded every ten seconds, forecasts once per horizon window, so
        // most verdicts are issued *inside* a forecast rather than at its start.
        val rows = DecisionLedgerJoin.settle(
            records = listOf(verdict(state = "LONG", at = 70_000L, horizon = "5m")),
            predictions = listOf(
                forecast(horizon = "5m", issued = 60_000L, target = 360_000L, base = 100.0, realized = 99.0),
                // An older, expired forecast must not be preferred over the live one.
                forecast(horizon = "5m", issued = 0L, target = 60_000L, base = 90.0, realized = 120.0)
            )
        )

        assertTrue(rows.single().settled)
        assertEquals(false, rows.single().hit)
        assertEquals(99.0, rows.single().realizedPrice!!, 1e-9)
    }

    @Test
    fun `given a verdict whose covering forecast is still pending then it stays unsettled and uncredited`() {
        val rows = DecisionLedgerJoin.settle(
            records = listOf(verdict(state = "LONG", at = 10_000L, horizon = "5m")),
            predictions = listOf(
                forecast(horizon = "5m", issued = 10_000L, target = 310_000L, base = 100.0, realized = null, settled = false)
            )
        )

        assertFalse(rows.single().settled)
        assertNull("an unsettled verdict has no result", rows.single().hit)
        assertNull(rows.single().realizedPrice)
    }

    @Test
    fun `given a verdict with no forecast in its window then it is never credited`() {
        val rows = DecisionLedgerJoin.settle(
            records = listOf(verdict(state = "LONG", at = 10_000L, horizon = "1h")),
            predictions = listOf(
                forecast(horizon = "5m", issued = 10_000L, target = 310_000L, base = 100.0, realized = 101.0)
            )
        )

        assertFalse("a different horizon cannot grade this verdict", rows.single().settled)
        assertNull(rows.single().hit)
    }

    // ---------------------------------------------------------------- fixtures

    private fun verdict(state: String, at: Long, horizon: String) = DecisionRecordEntity(
        id = "d-$state-$at",
        symbol = "BTCUSDT",
        issuedTimestamp = at,
        state = state,
        confidence = 0.72,
        source = "NEEDLE3",
        referenceHorizon = horizon,
        reason = "test"
    )

    private fun forecast(
        horizon: String,
        issued: Long,
        target: Long,
        base: Double,
        realized: Double?,
        settled: Boolean = true,
        inCone: Boolean = true
    ) = PredictionEntity(
        id = "p-$horizon-$issued",
        symbol = "BTCUSDT",
        horizonId = horizon,
        horizonLabel = horizon,
        modelType = "Tri-Factor",
        minutes = 5,
        issuedTimestamp = issued,
        targetTimestamp = target,
        basePrice = base,
        predictedPrice = base,
        upperBound = base * 1.01,
        lowerBound = base * 0.99,
        muK = 0.0,
        muP = 0.0,
        muAlpha = 0.0,
        settled = settled,
        realizedPrice = realized,
        inCone = if (settled) inCone else null
    )
}
