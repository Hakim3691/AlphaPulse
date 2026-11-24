package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.repository.DecisionLedgerRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the trade decisions ledger, specified Given/When/Then.
 *
 * A verdict that has been graded must say so — the ledger used to show every row as
 * recorded forever — and settled rows must stay out of the way until the user asks,
 * exactly like the price and pattern ledgers above and below it. The header, the
 * horizon filter and the three actions are the shared ledger grammar (Directive §2).
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Decision Router, C-29
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class DecisionsLedgerSectionBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private val issued = 1_700_000_000_000L

    private fun row(
        id: String,
        state: String = "LONG",
        settled: Boolean = false,
        hit: Boolean? = null,
        realizedPrice: Double? = null,
        realizedPct: Double? = null,
        horizon: String = "15m",
        symbol: String = "BTCUSDT",
        issued: Long = this.issued
    ) = DecisionLedgerRow(
        record = DecisionRecordEntity(
            id = id,
            symbol = symbol,
            issuedTimestamp = issued,
            state = state,
            confidence = 0.81,
            source = "NEEDLE3",
            referenceHorizon = horizon,
            reason = "Needle 3: decide_long"
        ),
        settled = settled,
        hit = hit,
        realizedPrice = realizedPrice,
        realizedPct = realizedPct
    )

    @Test
    fun `given no verdicts when the ledger renders then it explains the silence and shows the shared grammar`() {
        compose.setContent { DecisionsLedgerSection(rows = emptyList(), onClearClick = {}) }

        compose.onAllNodesWithText(DecisionLogText.EMPTY).assertCountEquals(1)
        compose.onAllNodesWithText("TRADE DECISIONS").assertCountEquals(1)
        compose.onAllNodesWithText("SHOW ALL").assertCountEquals(1)
        compose.onAllNodesWithText("LEDGER").assertCountEquals(1)
        compose.onAllNodesWithText("TRASH").assertCountEquals(1)
        compose.onAllNodesWithText("HORIZON FILTER:").assertCountEquals(1)
    }

    @Test
    fun `given mixed verdicts when the ledger renders then only the pending one is visible`() {
        val pending = row(id = "a")
        val graded = row(id = "b", settled = true, hit = true, realizedPrice = 100.5, realizedPct = 0.5)

        compose.setContent { DecisionsLedgerSection(rows = listOf(pending, graded), onClearClick = {}) }

        compose.onAllNodesWithText("PENDING").assertCountEquals(1)
        compose.onAllNodesWithText("✓ HIT").assertCountEquals(0)
        compose.onAllNodesWithText("Settled: 1").assertCountEquals(1)
    }

    @Test
    fun `given mixed verdicts when the settled toggle is pressed then the graded rows appear`() {
        val pending = row(id = "a")
        val hit = row(id = "b", settled = true, hit = true, realizedPrice = 100.5, realizedPct = 0.5)
        val miss = row(id = "c", state = "SHORT", settled = true, hit = false, realizedPrice = 100.5, realizedPct = 0.5)

        compose.setContent { DecisionsLedgerSection(rows = listOf(pending, hit, miss), onClearClick = {}) }
        compose.onAllNodesWithText("✓ HIT").assertCountEquals(0)

        compose.onNodeWithText("SHOW ALL").performClick()

        compose.onAllNodesWithText("✓ HIT").assertCountEquals(1)
        compose.onAllNodesWithText("✗ MISS").assertCountEquals(1)
        compose.onAllNodesWithText("SHOW PENDING").assertCountEquals(1)
    }

    @Test
    fun `given verdicts on two horizons when the filter selects one then only that horizon is listed`() {
        compose.setContent {
            DecisionsLedgerSection(
                rows = listOf(
                    row(id = "a", horizon = "15m"),
                    row(id = "b", horizon = "1h")
                ),
                onClearClick = {},
                horizonFilter = "1h"
            )
        }

        // "15m" and "1h" are also filter labels, so count the pinned HORIZON cells by
        // their counter instead: only the filtered row contributes a pending verdict.
        compose.onAllNodesWithText("Pend: 1").assertCountEquals(1)
        compose.onAllNodesWithText("Settled: 0").assertCountEquals(1)
    }

    @Test
    fun `given every verdict settled when the toggle is off then the ledger says why it is empty`() {
        compose.setContent {
            DecisionsLedgerSection(
                rows = listOf(row(id = "a", settled = true, hit = true, realizedPrice = 100.5, realizedPct = 0.5)),
                onClearClick = {}
            )
        }

        compose.onAllNodesWithText("PENDING").assertCountEquals(0)
        compose.onAllNodesWithText("Settled: 1").assertCountEquals(1)
        compose.onAllNodesWithText("SHOW ALL to review them", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given a graded verdict when its result label is built then an unsettled one is never credited`() {
        assertEquals("PENDING", DecisionLogText.resultLabel(row(id = "a")))
        assertEquals("✓ HIT", DecisionLogText.resultLabel(row(id = "b", settled = true, hit = true)))
        assertEquals("✗ MISS", DecisionLogText.resultLabel(row(id = "c", settled = true, hit = false)))
    }

    @Test
    fun `given a coalesced neutral when the ledger renders then it reads as one verdict still in force`() {
        compose.setContent {
            DecisionsLedgerSection(rows = listOf(row(id = "a", state = "NEUTRAL")), onClearClick = {})
        }

        compose.onAllNodesWithText(DecisionLogText.IN_FORCE).assertCountEquals(1)
        compose.onAllNodesWithText("PENDING").assertCountEquals(0)
        compose.onAllNodesWithText(sinceLabel(issued)).assertCountEquals(1)
    }

    @Test
    fun `given two pending verdicts on one horizon when the ledger renders then only the newest is in force`() {
        val older = row(id = "a", state = "LONG", issued = issued - 600_000)
        val newest = row(id = "b", state = "NEUTRAL", issued = issued)

        compose.setContent { DecisionsLedgerSection(rows = listOf(newest, older), onClearClick = {}) }

        compose.onAllNodesWithText(DecisionLogText.IN_FORCE).assertCountEquals(1)
        compose.onAllNodesWithText("PENDING").assertCountEquals(1)
        compose.onAllNodesWithText("since ", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given the newest verdict is graded when the ledger renders then nothing claims to be in force`() {
        compose.setContent {
            DecisionsLedgerSection(
                rows = listOf(
                    row(id = "a", settled = true, hit = true, realizedPrice = 100.5, realizedPct = 0.5),
                    row(id = "b", state = "NEUTRAL", issued = issued - 600_000)
                ),
                onClearClick = {},
                initiallyShowSettled = true
            )
        }

        compose.onAllNodesWithText(DecisionLogText.IN_FORCE).assertCountEquals(0)
        compose.onAllNodesWithText("since ", substring = true).assertCountEquals(0)
    }

    /** The clock string the live row appends to `since`, in this JVM's default zone. */
    private fun sinceLabel(timestamp: Long): String =
        "since " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(timestamp))
}
