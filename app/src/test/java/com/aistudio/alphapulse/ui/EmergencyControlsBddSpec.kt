package com.aistudio.alphapulse.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.ui.components.AlphaPulseHeader
import com.aistudio.alphapulse.ui.components.VipHeaderState
import com.aistudio.alphapulse.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-68 — the two emergency controls say what they do, and both actually respond.
 *
 * The header drew two bare 28dp icons. The kill switch looked the same engaged or in
 * standby and toggled in both directions from one tap; the orphan guard set a flag only
 * the retired `TraderHostActivity` observed, so on the shipped single-host workspace it
 * did nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class EmergencyControlsBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private var killSwitchTaps = 0
    private var orphanTaps = 0

    private fun header(engaged: Boolean) {
        compose.setContent {
            MyApplicationTheme {
                AlphaPulseHeader(
                    sessionElapsedSeconds = 0,
                    isLTFReady = false,
                    isMTFReady = false,
                    isHTFReady = false,
                    pipelineRunning = false,
                    pipelineSecondsRemaining = 0,
                    onRefreshClick = {},
                    vip = VipHeaderState(
                        executionMode = ExecutionMode.PAPER,
                        riskEnvelope = RiskEnvelope(isKillSwitchEngaged = engaged),
                        venueHealth = VenueHealth(binancePingMs = 42, availableRamMb = 2_048),
                        isSessionActive = true,
                        isLiveUniverse = true,
                        onModeChange = {},
                        onToggleKillSwitch = { killSwitchTaps++ },
                        onOpenOrphanWarning = { orphanTaps++ }
                    )
                )
            }
        }
    }

    @Test
    fun `given the header in standby when it renders then both controls are labelled and the kill switch reads ARMED`() {
        header(engaged = false)

        compose.onNodeWithText("KILL-SWITCH · ARMED").assertIsDisplayed()
        compose.onNodeWithText("ORPHAN ORDER GUARD").assertIsDisplayed()
        compose.onNodeWithTag("kill_switch_button").assertIsDisplayed()
        compose.onNodeWithTag("orphan_warning_button").assertIsDisplayed()
    }

    @Test
    fun `given an engaged kill switch when it renders then the control says ENGAGED rather than looking identical`() {
        header(engaged = true)

        compose.onNodeWithText("KILL-SWITCH · ENGAGED").assertIsDisplayed()
    }

    @Test
    fun `given the labelled controls when tapped then both dispatch their action`() {
        header(engaged = false)

        compose.onNodeWithTag("kill_switch_button").performClick()
        compose.onNodeWithTag("orphan_warning_button").performClick()

        assertEquals(1, killSwitchTaps)
        assertEquals(1, orphanTaps)
    }

    @Test
    fun `given the shipped workspace when read then the orphan intercept is mounted and the kill switch is confirmed`() {
        val workspace = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()

        assertTrue(
            "The shipped host must mount the orphan intercept dialog",
            workspace.contains("OrphanedOrderWarningDialog(")
        )
        assertTrue(
            "The dialog must be driven by the state the header button sets",
            workspace.contains("isOpen = state.isOrphanedOrderWarningOpen")
        )
        assertTrue(
            "Acknowledging must reach the view model's wipe action",
            workspace.contains("onAcknowledgeOrphanWarning = viewModel::acknowledgeOrphanedOrderWarning")
        )
        assertTrue(
            "The kill switch must be a deliberate, confirmed action",
            workspace.contains("kill_switch_confirmation")
        )

        val header = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/components/AlphaPulseHeader.kt"
        ).readText()
        assertTrue(header.contains("KILL-SWITCH · ARMED"))
        assertTrue(header.contains("KILL-SWITCH · ENGAGED"))
        assertTrue(header.contains("ORPHAN ORDER GUARD"))
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
