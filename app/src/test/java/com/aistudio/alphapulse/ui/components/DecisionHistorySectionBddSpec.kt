package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBearish
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.TextTertiary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the recorded decision log, specified Given/When/Then.
 *
 * The log exists so a user can see what the engine concluded before the current
 * frame. These tests pin the ways it could mislead: an uncalibrated verdict
 * printed as `0.00`, a rules verdict that looks like a model verdict, the oldest
 * rows shown first, and the header claiming a count that does not match what was
 * read.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, §40 Primary Dashboard
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class DecisionHistorySectionBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private val issued = 1_700_000_000_000L

    private fun record(
        state: String = "LONG",
        confidence: Double = 0.88,
        source: String = "NEEDLE3",
        issuedTimestamp: Long = issued,
        reason: String = "Needle 3: decide_long (confidence 88%)",
        horizon: String = "15m"
    ) = DecisionRecordEntity(
        id = "decision-BTCUSDT-$issuedTimestamp",
        symbol = "BTCUSDT",
        issuedTimestamp = issuedTimestamp,
        state = state,
        confidence = confidence,
        source = source,
        referenceHorizon = horizon,
        reason = reason
    )

    @Test
    fun `given no recorded verdicts when the log renders then it says so instead of showing a blank panel`() {
        compose.setContent { DecisionHistorySection(history = emptyList()) }

        compose.onAllNodesWithText(DecisionLogText.EMPTY).assertCountEquals(1)
        compose.onAllNodesWithText("NONE RECORDED").assertCountEquals(1)
    }

    @Test
    fun `given recorded verdicts when the log renders then the newest is shown first`() {
        val older = record(state = "NEUTRAL", confidence = 0.5, issuedTimestamp = issued)
        val newer = record(
            state = "STRONG_SHORT",
            confidence = 0.93,
            issuedTimestamp = issued + 10_000L,
            reason = "Needle 3: decide_strong_short (confidence 93%)"
        )

        // The DAO returns newest first, so the log must render what it is given.
        compose.setContent { DecisionHistorySection(history = listOf(newer, older)) }

        compose.onAllNodesWithText("STRONG SHORT").assertCountEquals(1)
        compose.onAllNodesWithText("NEUTRAL").assertCountEquals(1)
        assertEquals("NEWEST 2 OF 2", DecisionLogText.countLabel(2, 2))
    }

    @Test
    fun `given a verdict from the rules when the log renders then it is not dressed up as a model verdict`() {
        compose.setContent {
            DecisionHistorySection(
                history = listOf(
                    record(
                        state = "NO_TRADE",
                        confidence = 0.0,
                        source = "DETERMINISTIC",
                        reason = "Needle 3 has no mounted checkpoint, so the decision stays deterministic."
                    )
                )
            )
        }

        compose.onAllNodesWithText("RULES").assertCountEquals(1)
        compose.onAllNodesWithText("NEEDLE 3").assertCountEquals(0)
        // UNCALIBRATED IS NOT 0.00: the router never claimed a probability.
        compose.onAllNodesWithText("UNSCORED").assertCountEquals(1)
        compose.onAllNodesWithText("0.00").assertCountEquals(0)
        compose.onAllNodesWithText("NO TRADE").assertCountEquals(1)
    }

    @Test
    fun `given more verdicts than the log shows when it renders then the newest are shown and the count still reports all`() {
        val history = (0 until 9).map { index ->
            record(state = "LONG", issuedTimestamp = issued + (9 - index) * 10_000L)
        }

        compose.setContent { DecisionHistorySection(history = history) }

        compose.onAllNodesWithText("LONG").assertCountEquals(DECISION_LOG_ROWS)
        compose.onAllNodesWithText("NEWEST $DECISION_LOG_ROWS OF 9").assertCountEquals(1)
    }

    @Test
    fun `given a verdict with an empty reason when the log renders then a placeholder explains the silence`() {
        compose.setContent { DecisionHistorySection(history = listOf(record(reason = ""))) }

        compose.onAllNodesWithText(DecisionLogText.reasonLabel("")).assertCountEquals(1)
        assertTrue(DecisionLogText.reasonLabel("").isNotBlank())
    }

    @Test
    fun `given each decision state when its label and colour are built then they match the live card`() {
        assertEquals("STRONG LONG", DecisionLogText.stateLabel("STRONG_LONG"))
        assertEquals("NO TRADE", DecisionLogText.stateLabel("NO_TRADE"))

        assertEquals(BrandBullish, DecisionLogText.tintOf("STRONG_LONG"))
        assertEquals(BrandBullish, DecisionLogText.tintOf("LONG"))
        assertEquals(BrandBearish, DecisionLogText.tintOf("SHORT"))
        assertEquals(BrandBearish, DecisionLogText.tintOf("STRONG_SHORT"))
        assertEquals(BrandAmber, DecisionLogText.tintOf("NEUTRAL"))
        assertEquals(
            "An unknown state must not borrow a directional colour",
            TextTertiary,
            DecisionLogText.tintOf("???")
        )
    }

    @Test
    fun `given each router when its badge is built then only needle is marked as on-device`() {
        assertEquals("NEEDLE 3", DecisionLogText.sourceLabel("NEEDLE3"))
        assertEquals("RULES", DecisionLogText.sourceLabel("DETERMINISTIC"))
        assertTrue(DecisionLogText.answeredOnDevice("NEEDLE3"))
        assertFalse(DecisionLogText.answeredOnDevice("DETERMINISTIC"))
        assertEquals("0.88", DecisionLogText.confidenceLabel(0.88))
        assertEquals("UNSCORED", DecisionLogText.confidenceLabel(0.0))
    }

    @Test
    fun `given an unscored verdict when the count is built then the header never implies zero records`() {
        assertEquals("NONE RECORDED", DecisionLogText.countLabel(0, 0))
        assertEquals("NEWEST 1 OF 1", DecisionLogText.countLabel(1, 1))
    }
}
