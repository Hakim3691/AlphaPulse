package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Directive §2: the three ledgers are one instrument in three tables.
 *
 * The price, pattern and trade-decision ledgers must paint the same header and the
 * same three actions (`SHOW ALL`, `LEDGER`, `TRASH`) with the same `Pend:`/`Settled:`
 * counters, and the universe export belongs to the Screened Universe card rather than
 * the price ledger.
 *
 * Spec Ref: Directive §1 Universe Export, §2 Ledger Harmonization, C-29
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LedgerHarmonizationBddSpec {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given an empty price ledger when it renders then it carries the shared actions and no universe export`() {
        compose.setContent {
            PredictionLedgerSection(
                predictions = emptyList(),
                horizonFilter = "ALL",
                onFilterChange = {},
                onClearClick = {},
                onExportClick = {}
            )
        }

        compose.onAllNodesWithText("PRICE PREDICTION").assertCountEquals(1)
        compose.onAllNodesWithText("Pend: 0").assertCountEquals(1)
        compose.onAllNodesWithText("Settled: 0").assertCountEquals(1)
        compose.onAllNodesWithText("SHOW ALL").assertCountEquals(1)
        compose.onAllNodesWithText("LEDGER").assertCountEquals(1)
        compose.onAllNodesWithText("TRASH").assertCountEquals(1)
        compose.onAllNodesWithText("HORIZON FILTER:").assertCountEquals(1)

        // The export moved to the Screened Universe card.
        compose.onAllNodesWithText("UNIVERSE").assertCountEquals(0)
    }

    @Test
    fun `given an empty pattern ledger when it renders then it carries the same shared actions`() {
        compose.setContent {
            PatternLedgerSection(
                entries = emptyList(),
                horizonFilter = "ALL",
                onFilterChange = {},
                onExportClick = {},
                onClearClick = {}
            )
        }

        compose.onAllNodesWithText("PATTERN PREDICTION").assertCountEquals(1)
        compose.onAllNodesWithText("SHOW ALL").assertCountEquals(1)
        compose.onAllNodesWithText("LEDGER").assertCountEquals(1)
        compose.onAllNodesWithText("TRASH").assertCountEquals(1)
    }

    @Test
    fun `given the screened universe card when its export is tapped then the universe CSV is requested`() {
        var exported = false
        compose.setContent {
            ScreenedUniverseCard(
                screenedCoins = emptyList(),
                selectedSymbol = "",
                under10Only = false,
                onSelectCoin = {},
                onToggleUnder10 = {},
                onExportUniverseClick = { exported = true }
            )
        }

        compose.onAllNodesWithText("SCREENED UNIVERSE").assertCountEquals(1)
        compose.onAllNodesWithText("EXPORT UNIVERSE").assertCountEquals(1)

        compose.onNodeWithTag("universe_export").performClick()
        assertTrue("The header export must trigger the universe CSV", exported)
    }
}
