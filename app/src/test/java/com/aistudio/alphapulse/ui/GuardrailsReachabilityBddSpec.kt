package com.aistudio.alphapulse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.ui.screens.RiskGuardrailsScreen
import com.aistudio.alphapulse.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-67 — the Guardrails tab is one scroll container, so the API key manager is reachable.
 *
 * The tab used to be a fixed Column holding three gate cards above a `weight(1f)` box.
 * On a phone the cards consumed the viewport, the weighted LazyColumn collapsed, and the
 * session key fields could not be scrolled to. That also kept Capped and Scaled Live
 * refused, because no session could ever be unlocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class GuardrailsReachabilityBddSpec {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given the gate cards above the guardrails when the tab is scrolled then the API key fields and start button are reachable`() {
        compose.setContent {
            MyApplicationTheme {
                RiskGuardrailsScreen(
                    riskEnvelope = RiskEnvelope(),
                    venueHealth = VenueHealth(),
                    isSessionActive = false,
                    executionMode = ExecutionMode.PAPER,
                    liveWalletUsd = null,
                    onToggleKillSwitch = {},
                    onStartSession = { _, _ -> },
                    onTerminateSession = {},
                    onUpdatePaperWallet = { _, _, _, _ -> },
                    isDiagnosticsRunning = false,
                    hasLiveDiagnostics = false,
                    onRunDiagnostics = {},
                    leading = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1_200.dp)
                                .testTag("guardrails_gate_cards")
                        ) {
                            Text("LAYERED EVIDENCE GATE")
                        }
                    }
                )
            }
        }

        compose.onNodeWithTag("risk_guardrails_screen")
            .performScrollToNode(hasTestTag("api_key_input"))
        compose.onNodeWithTag("api_key_input").assertIsDisplayed()

        compose.onNodeWithTag("risk_guardrails_screen")
            .performScrollToNode(hasTestTag("start_session_button"))
        compose.onNodeWithTag("start_session_button").assertIsDisplayed()
    }

    @Test
    fun `given the workspace source when read then the guardrails tab uses one scroll container with the gate cards inside it`() {
        val workspace = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()
        val tab = workspace.substringAfter("private fun ProactiveGuardrailsTab")
            .substringBefore("@Composable")

        assertTrue(
            "The gate cards must scroll with the guardrails, not above them",
            tab.contains("RiskGuardrailsScreen(") && tab.contains("leading = {")
        )
        assertFalse(
            "The guardrails may not be wrapped in a weighted box under a fixed column",
            tab.contains(".weight(1f)")
        )

        val screen = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/screens/RiskGuardrailsScreen.kt"
        ).readText()
        assertTrue("The guardrails root must be a LazyColumn", screen.contains("LazyColumn("))
        assertTrue(
            "The leading cards must be items of that list",
            screen.contains("leading?.let { header -> item { header() } }")
        )
        assertTrue("The API key field must still exist", screen.contains("testTag(\"api_key_input\")"))
        assertTrue("The secret field must still exist", screen.contains("testTag(\"api_secret_input\")"))
        assertTrue("The session start action must still exist", screen.contains("testTag(\"start_session_button\")"))
    }

    @Test
    fun `given a reachable API session when Capped or Scaled Live is selected then the modes are allowed`() {
        assertTrue(
            "Capped Live must unlock once a session exists",
            ExecutionModeGate.evaluate(
                ExecutionMode.CAPPED_LIVE,
                hasActiveSession = true,
                preLaunchUnlocked = false
            ).allowed
        )
        assertTrue(
            "Scaled Live must unlock once a session and the §38 gate exist",
            ExecutionModeGate.evaluate(
                ExecutionMode.SCALED_LIVE,
                hasActiveSession = true,
                preLaunchUnlocked = true
            ).allowed
        )
        assertFalse(
            "Scaled Live must still refuse without the §38 gate; the scroll fix must not weaken it",
            ExecutionModeGate.evaluate(
                ExecutionMode.SCALED_LIVE,
                hasActiveSession = true,
                preLaunchUnlocked = false
            ).allowed
        )
    }

    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
