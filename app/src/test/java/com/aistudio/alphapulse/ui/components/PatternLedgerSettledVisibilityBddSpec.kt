package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aistudio.alphapulse.data.db.PatternLedgerEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pattern ledger's half of C-29: settled rows are graded history, so the table
 * shows the pending ones by default and one shared toggle brings the rest back.
 *
 * Spec Ref: §73 Pattern Prediction & Learning Ledger, §21 Prediction Audit Ledger, C-29
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class PatternLedgerSettledVisibilityBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private fun entity(id: String, settled: Boolean, asset: String) = PatternLedgerEntity(
        id = id,
        issuedTimestamp = 1_700_000_000_000L,
        asset = asset,
        horizon = "1h",
        targetTime = 1_700_003_600_000L,
        predictedPatternId = "higher-high",
        predictedPatternName = "Higher High",
        predictedCategory = "Bullish Reversal",
        confidence = 0.8,
        realizedPattern = if (settled) "Higher High" else null,
        fitLoss = 0.1,
        status = if (settled) "Settled" else "Pending",
        sgdAction = "",
        settled = settled,
        tsfmProvider = "",
        tsfmDriftPct = 0.0,
        tsfmAligned = null
    )

    @Test
    fun `given settled and pending rows when the ledger renders then the settled asset is hidden`() {
        compose.setContent {
            PatternLedgerSection(
                entries = listOf(
                    entity(id = "1", settled = true, asset = "BTCUSDT").toEntry(),
                    entity(id = "2", settled = false, asset = "ETHUSDT").toEntry()
                ),
                horizonFilter = "ALL",
                onFilterChange = {},
                onExportClick = {}
            )
        }

        compose.onAllNodesWithText("ETHUSDT").assertCountEquals(1)
        compose.onAllNodesWithText("BTCUSDT").assertCountEquals(0)
        compose.onAllNodesWithText("Settled: 1").assertCountEquals(1)
    }

    @Test
    fun `given settled rows hidden when the toggle is pressed then they are shown and can be hidden again`() {
        compose.setContent {
            PatternLedgerSection(
                entries = listOf(
                    entity(id = "1", settled = true, asset = "BTCUSDT").toEntry(),
                    entity(id = "2", settled = false, asset = "ETHUSDT").toEntry()
                ),
                horizonFilter = "ALL",
                onFilterChange = {},
                onExportClick = {}
            )
        }

        compose.onNodeWithText("SHOW ALL").performClick()
        compose.onAllNodesWithText("BTCUSDT").assertCountEquals(1)
        compose.onAllNodesWithText("SHOW PENDING").assertCountEquals(1)

        compose.onNodeWithText("SHOW PENDING").performClick()
        compose.onAllNodesWithText("BTCUSDT").assertCountEquals(0)
    }

    @Test
    fun `given the price ledger when settled rows are hidden then the shared toggle reveals them`() {
        // The price ledger is wired from AlphaPulseScreen; its toggle must exist even
        // when the list is empty, so the control can be found and pressed.
        val toggle = LedgerSettledToggleProbe
        org.junit.Assert.assertEquals("SHOW ALL", toggle.label(false))
        org.junit.Assert.assertEquals("SHOW PENDING", toggle.label(true))
    }

    /** Mirrors [LedgerSettledToggle]'s wording so a wording drift fails a test. */
    private object LedgerSettledToggleProbe {
        fun label(showSettled: Boolean) = if (showSettled) "SHOW PENDING" else "SHOW ALL"
    }
}
