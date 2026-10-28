package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.aistudio.alphapulse.domain.decision.DecisionReport
import com.aistudio.alphapulse.domain.decision.DecisionState
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBearish
import com.aistudio.alphapulse.ui.theme.BrandBullish
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the decision card, specified as Given/When/Then.
 *
 * §29 produces a state, a calibrated confidence and a reason. The dashboard showed
 * the forecasts but never the verdict, so the reason was dropped on the floor and
 * a user could not tell which router had answered. These scenarios pin what the
 * card must say - including that a missing calibration is never dressed up as a
 * number.
 *
 * Spec Ref: §29 Quantitative Decision Router, §39 User Interface, §40 Primary Dashboard
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class DecisionCardBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private fun needleLong() = DecisionReport(
        state = DecisionState.LONG,
        confidence = 0.88,
        // No digits in the reason: the confidence assertion below must count one node.
        reason = "Needle 3: decide_long - both forecasts positive"
    )

    private fun deterministicNeutral() = DecisionReport(
        state = DecisionState.NEUTRAL,
        confidence = 0.0,
        reason = "Fallback: Deterministic router: rule-based evaluation (no mounted checkpoint)"
    )

    @Test
    fun `given no evaluation yet when the card renders then it says so instead of showing a stale verdict`() {
        compose.setContent { DecisionCard(decision = null) }

        compose.onNodeWithTag(DECISION_CARD_TAG).assertExists()
        compose.onAllNodesWithText(DecisionCardText.AWAITING_TITLE, substring = true).assertCountEquals(1)
        // No state is named and no confidence is implied before the router has run.
        compose.onAllNodesWithText("LONG", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("CONF 0", substring = true).assertCountEquals(0)
    }

    @Test
    fun `given the router chose a long when the card renders then the state its confidence and its reason are all visible`() {
        compose.setContent { DecisionCard(decision = needleLong()) }

        compose.onNodeWithTag(DECISION_CARD_TAG).assertExists()
        compose.onAllNodesWithText("LONG", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("0.88", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("both forecasts positive", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given the deterministic rules answered when the card renders then its zero confidence is not shown as a calibrated number`() {
        compose.setContent { DecisionCard(decision = deterministicNeutral()) }

        compose.onAllNodesWithText(DecisionCardText.UNSCORED, substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("0.00", substring = true).assertCountEquals(0)
        // The fallback must still say why it, and not the model, decided.
        compose.onAllNodesWithText("no mounted checkpoint", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given every decision state when its title is built then it reads as words rather than as an enum constant`() {
        DecisionState.values().forEach { state ->
            val title = DecisionCardText.titleOf(DecisionReport(state, 0.5, "reason"))

            assertEquals(
                "§29's six states must be readable on screen: $state",
                state.name.replace('_', ' '),
                title
            )
            assertEquals("No underscore may survive into the UI", -1, title.indexOf('_'))
        }
    }

    @Test
    fun `given a directional state when its tint is read then it uses the colour of that direction`() {
        fun tintOf(state: DecisionState) =
            DecisionCardText.tintOf(DecisionReport(state, 0.5, "reason"))

        assertEquals(BrandBullish, tintOf(DecisionState.STRONG_LONG))
        assertEquals(BrandBullish, tintOf(DecisionState.LONG))
        assertEquals(BrandBearish, tintOf(DecisionState.SHORT))
        assertEquals(BrandBearish, tintOf(DecisionState.STRONG_SHORT))
        assertEquals(BrandAmber, tintOf(DecisionState.NEUTRAL))
    }

    @Test
    fun `given a calibrated report when its confidence is built then it is a probability to two places`() {
        assertEquals("CONF 0.88", DecisionCardText.confidenceOf(needleLong()))
        assertEquals(DecisionCardText.UNSCORED, DecisionCardText.confidenceOf(deterministicNeutral()))
        assertEquals("CONF --", DecisionCardText.confidenceOf(null))
    }
}
