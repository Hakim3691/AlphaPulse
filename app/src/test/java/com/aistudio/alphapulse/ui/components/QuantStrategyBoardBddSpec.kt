package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aistudio.alphapulse.domain.strategy.ScreenerSignal
import com.aistudio.alphapulse.domain.strategy.SignalDirection
import com.aistudio.alphapulse.domain.strategy.StrategySignal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The §74 board must show what the library actually computed.
 *
 * Why this spec exists
 * --------------------
 * The screeners and models are audited numbers, so the board is not allowed to embellish
 * them: an abstaining model must read FLAT with no level, a missing level must not render as
 * `0.0000`, and the active counter must match the rows it counts. Each of those is a way a
 * trading board misleads an operator while every unit test still passes.
 *
 * Spec Ref: §74 Auxiliary Confluence Screeners & Quantitative Strategy Models
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class QuantStrategyBoardBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private val turtle = StrategySignal(
        id = "turtle-breakout",
        name = "Turtle Breakout",
        direction = SignalDirection.BULLISH,
        entry = 119.2,
        exit = 109.8,
        invalidation = 109.8,
        detail = "20-bar high 119.2000 taken"
    )

    private val connors = StrategySignal(
        id = "connors-rsi",
        name = "Connors RSI",
        direction = SignalDirection.NEUTRAL,
        entry = null,
        exit = null,
        invalidation = null,
        detail = "CRSI 44.0000"
    )

    private val rsiScreener = ScreenerSignal(
        id = "rsi-14",
        name = "RSI Oversold/Overbought",
        direction = SignalDirection.BULLISH,
        detail = "RSI 22.0000 < 30 (oversold)"
    )

    @Test
    fun `given no evaluation yet when the board renders then it explains the wait`() {
        compose.setContent { QuantStrategyBoardSection(emptyList(), emptyList()) }

        compose.onNodeWithText("Waiting for the first evaluation cycle", substring = true).assertExists()
    }

    @Test
    fun `given a long model and an abstaining model when the board renders then it shows the levels and counts only the active one`() {
        compose.setContent {
            QuantStrategyBoardSection(
                screenerSignals = listOf(rsiScreener),
                strategySignals = listOf(turtle, connors)
            )
        }

        compose.onNodeWithText("1 / 2 ACTIVE").assertExists()
        compose.onNodeWithText("Turtle Breakout").assertExists()
        compose.onNodeWithText("ENTRY 119.2000", substring = true).assertExists()
        compose.onNodeWithText("RSI Oversold/Overbought: LONG").assertExists()
        compose.onNodeWithText("FLAT").assertExists()
    }

    @Test
    fun `given a model without levels when the board renders then it says staging suspended instead of empty dashes`() {
        compose.setContent { QuantStrategyBoardSection(emptyList(), listOf(connors)) }

        // Phase 1 §3: an unavailable setup publishes a status, not "ENTRY — EXIT —".
        compose.onNodeWithText("STATUS: Staging Suspended (Awaiting Trigger)", substring = true).assertExists()
        compose.onNodeWithText("ENTRY 0.0000", substring = true).assertDoesNotExist()
    }
}
