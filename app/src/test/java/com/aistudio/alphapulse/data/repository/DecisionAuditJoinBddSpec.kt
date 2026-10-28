package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the decision columns in the audit export, specified Given/When/Then.
 *
 * Recording a verdict is only useful if the export can attribute it. These tests
 * pin the three ways an attribution can go wrong: a row borrows the verdict of a
 * different symbol evaluated in the same millisecond, a missing verdict is filled
 * with a plausible default, and a reason containing a comma splits into two fields
 * and shifts every later column.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, §38 Export System
 */
class DecisionAuditJoinBddSpec {

    private val issued = 1_700_000_000_000L

    private fun record(
        symbol: String = "BTCUSDT",
        issuedTimestamp: Long = issued,
        state: String = "LONG",
        confidence: Double = 0.88,
        source: String = "NEEDLE3",
        reason: String = "Needle 3: decide_long (confidence 88%)"
    ) = DecisionRecordEntity(
        id = DecisionAuditJoin.idOf(symbol, issuedTimestamp),
        symbol = symbol,
        issuedTimestamp = issuedTimestamp,
        state = state,
        confidence = confidence,
        source = source,
        referenceHorizon = "15m",
        reason = reason
    )

    @Test
    fun `given two symbols evaluated in the same millisecond when indexed then each row finds its own verdict`() {
        val btc = record(symbol = "BTCUSDT", state = "LONG")
        val eth = record(symbol = "ETHUSDT", state = "SHORT")

        val index = DecisionAuditJoin.indexOf(listOf(btc, eth))

        assertEquals("LONG", DecisionAuditJoin.lookup(index, "BTCUSDT", issued)?.state)
        assertEquals("SHORT", DecisionAuditJoin.lookup(index, "ETHUSDT", issued)?.state)
        assertEquals(2, index.size)
    }

    @Test
    fun `given two snapshots of one symbol when indexed then the newest does not overwrite the older verdict`() {
        val early = record(issuedTimestamp = issued, state = "NEUTRAL")
        val late = record(issuedTimestamp = issued + 10_000L, state = "STRONG_SHORT")

        val index = DecisionAuditJoin.indexOf(listOf(early, late))

        assertEquals("NEUTRAL", DecisionAuditJoin.lookup(index, "BTCUSDT", issued)?.state)
        assertEquals(
            "STRONG_SHORT",
            DecisionAuditJoin.lookup(index, "BTCUSDT", issued + 10_000L)?.state
        )
    }

    @Test
    fun `given a row whose cycle recorded no verdict when exported then the decision cells stay empty`() {
        val index = DecisionAuditJoin.indexOf(listOf(record(symbol = "ETHUSDT")))

        val missing = DecisionAuditJoin.lookup(index, "BTCUSDT", issued)

        assertNull("A different symbol must never lend its verdict", missing)
        assertEquals("\"\",\"\",\"\",\"\"", DecisionAuditJoin.csvCells(missing))
        assertEquals("", DecisionAuditJoin.confidenceCell(missing))
    }

    @Test
    fun `given the rules answered with no calibrated score when exported then confidence reads unscored rather than zero`() {
        val fromRules = record(
            state = "NO_TRADE",
            confidence = 0.0,
            source = "DETERMINISTIC",
            reason = "Needle 3 has no mounted checkpoint, so the decision stays deterministic."
        )

        assertEquals(DecisionAuditJoin.UNSCORED, DecisionAuditJoin.confidenceCell(fromRules))
        assertTrue(
            "An unscored verdict must not look like a calibrated 0.00: " +
                DecisionAuditJoin.csvCells(fromRules),
            DecisionAuditJoin.csvCells(fromRules).contains("\"UNSCORED\"")
        )
    }

    @Test
    fun `given a calibrated verdict when exported then state, confidence and answering router are all recorded`() {
        val routed = record(confidence = 0.7234, source = "NEEDLE3", state = "STRONG_LONG")

        assertEquals(
            "\"STRONG_LONG\",\"0.72\",\"NEEDLE3\",\"Needle 3: decide_long (confidence 88%)\"",
            DecisionAuditJoin.csvCells(routed)
        )
    }

    @Test
    fun `given a reason containing commas and quotes when exported then it stays one csv field`() {
        val quoted = record(
            source = "DETERMINISTIC",
            reason = "Needle 3 said \"not sure\", so the rules answered, again"
        )

        assertEquals(
            "\"LONG\",\"0.88\",\"DETERMINISTIC\"," +
                "\"Needle 3 said \"\"not sure\"\", so the rules answered, again\"",
            DecisionAuditJoin.csvCells(quoted)
        )
    }

    @Test
    fun `given a reason with a newline when exported to the line-per-record format then the row stays one line`() {
        val multiline = record(reason = "Needle 3: decide_long\nsecond line, with a comma")

        val reason = DecisionAuditJoin.singleLineReason(multiline)

        assertTrue("A newline would break the row: $reason", !reason.contains('\n'))
        assertTrue("Commas would add columns: $reason", !reason.contains(','))
    }

    @Test
    fun `given the same symbol re-evaluated in one millisecond when recorded then the id replaces instead of duplicating`() {
        // The predictions table is keyed the same way; REPLACE must not create a
        // second verdict for one snapshot, so the id cannot carry a counter.
        assertEquals(
            DecisionAuditJoin.idOf("BTCUSDT", issued),
            DecisionAuditJoin.idOf("BTCUSDT", issued)
        )
        assertTrue(
            DecisionAuditJoin.idOf("BTCUSDT", issued) != DecisionAuditJoin.idOf("ETHUSDT", issued)
        )
    }
}
