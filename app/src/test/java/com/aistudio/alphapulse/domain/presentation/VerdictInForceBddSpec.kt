package com.aistudio.alphapulse.domain.presentation

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.repository.DecisionLedgerRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behaviour of the trade-decisions ledger's "still in force" marker, Given/When/Then.
 *
 * A coalesced verdict is written once with the timestamp its state began, so the row on
 * screen is not a stale row — it is the call that is still live. Nothing in the record
 * said that, though: a lone `NEUTRAL` painted `PENDING` reads like an abandoned decision
 * from hours ago. So the ledger projects the newest verdict per coin and reference
 * horizon: while it is still pending, it is the one in force and carries the time it
 * began. Once it is graded there is nothing left in force for that horizon.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Decision Router, C-29
 */
class VerdictInForceBddSpec {

    private val base = 1_700_000_000_000L

    private fun row(
        id: String,
        issued: Long,
        state: String = "NEUTRAL",
        horizon: String = "15m",
        symbol: String = "BTCUSDT",
        settled: Boolean = false
    ) = DecisionLedgerRow(
        record = DecisionRecordEntity(
            id = id,
            symbol = symbol,
            issuedTimestamp = issued,
            state = state,
            confidence = 0.4,
            source = "NEEDLE3",
            referenceHorizon = horizon,
            reason = "Needle 3: neutral"
        ),
        settled = settled,
        hit = if (settled) true else null
    )

    @Test
    fun `given a repeated neutral coalesced into one row when projected then that row is the one in force`() {
        val rows = listOf(row(id = "a", issued = base))

        assertEquals(setOf("a"), VerdictInForce.ids(rows))
    }

    @Test
    fun `given two pending verdicts on one horizon when projected then only the newest is in force`() {
        val older = row(id = "a", issued = base, state = "LONG")
        val newer = row(id = "b", issued = base + 600_000, state = "NEUTRAL")

        assertEquals(setOf("b"), VerdictInForce.ids(listOf(newer, older)))
    }

    @Test
    fun `given the newest verdict on a horizon is graded when projected then nothing on it is in force`() {
        val older = row(id = "a", issued = base)
        val newestSettled = row(id = "b", issued = base + 600_000, settled = true)

        assertEquals(emptySet<String>(), VerdictInForce.ids(listOf(newestSettled, older)))
    }

    @Test
    fun `given pending verdicts on two horizons when projected then each horizon keeps its own live call`() {
        val fast = row(id = "a", issued = base, horizon = "5m")
        val slow = row(id = "b", issued = base - 3_600_000, horizon = "1h")

        assertEquals(setOf("a", "b"), VerdictInForce.ids(listOf(fast, slow)))
    }

    @Test
    fun `given two coins in one list when projected then a coin never inherits the other's marker`() {
        val btc = row(id = "a", issued = base, symbol = "BTCUSDT")
        val eth = row(id = "b", issued = base - 600_000, symbol = "ETHUSDT", settled = true)

        assertEquals(setOf("a"), VerdictInForce.ids(listOf(btc, eth)))
    }

    @Test
    fun `given no verdicts when projected then nothing is in force`() {
        assertEquals(emptySet<String>(), VerdictInForce.ids(emptyList()))
    }
}
