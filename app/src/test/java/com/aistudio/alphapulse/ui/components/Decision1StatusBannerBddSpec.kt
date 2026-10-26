package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.aistudio.alphapulse.domain.decision.Decision1ConfigError
import com.aistudio.alphapulse.domain.decision.Decision1ConfigOutcome
import com.aistudio.alphapulse.domain.decision.Decision1Configuration
import com.aistudio.alphapulse.domain.decision.Decision1Provider
import com.aistudio.alphapulse.domain.decision.Decision1Settings
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the Decision-1 status banner, specified as Given/When/Then.
 *
 * §29/§42/§43 require an unconfigured remote model to fail *visibly*. Before this
 * banner the typed error existed only in code, so a user saw deterministic routing
 * with no explanation. These tests pin both halves: the message appears when the
 * router cannot run, and disappears once it can.
 *
 * Spec Ref: §29 Quantitative Decision Router, §42 Security Requirements, §43 API Key Architecture
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Decision1StatusBannerBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private fun unconfigured() = Decision1ConfigOutcome.Unconfigured(
        Decision1ConfigError.MissingApiKey(Decision1Provider.OPENROUTER)
    )

    private fun configured() = Decision1Configuration.resolve(
        Decision1Settings(
            providerId = "openrouter",
            model = "microsoft/decision-1",
            apiKey = "key-present"
        )
    )

    @Test
    fun `given a router with no api key when the banner renders then the actionable reason is visible`() {
        compose.setContent { Decision1StatusBanner(status = unconfigured()) }

        compose.onNodeWithTag(DECISION1_STATUS_BANNER_TAG).assertExists()
        // The label makes clear this model is called over the network, not downloaded.
        compose.onNodeWithText("DECISION-1 · REMOTE").assertExists()
        // The reason itself must be on screen, not just a generic warning.
        compose.onAllNodesWithText("no API key was found", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Android Keystore", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given an unconfigured router when the banner renders then it states the consequence`() {
        compose.setContent { Decision1StatusBanner(status = unconfigured()) }

        compose.onAllNodesWithText("deterministic rules", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given a configured router when the banner renders then nothing is shown`() {
        compose.setContent { Decision1StatusBanner(status = configured()) }

        compose.onAllNodesWithTag(DECISION1_STATUS_BANNER_TAG).assertCountEquals(0)
    }

    @Test
    fun `given the default settings on a fresh install when resolved then the banner explains the missing key`() {
        // The screen's default state today: nothing has been configured yet.
        val status = Decision1Configuration.resolve(Decision1Settings())

        compose.setContent { Decision1StatusBanner(status = status) }

        compose.onNodeWithTag(DECISION1_STATUS_BANNER_TAG).assertExists()
        compose.onAllNodesWithText("not configured", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given the banner exists when the downloadable model list is read then decision 1 is still absent`() {
        // Surfacing the error must never turn into offering the model for download.
        assertFalse(
            "Microsoft-Decision-1 is API-only and must stay out of the download dock",
            "ms-decision-1" in MODEL_DOCK_IDS
        )
    }
}
