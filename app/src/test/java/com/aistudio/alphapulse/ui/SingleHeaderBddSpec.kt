package com.aistudio.alphapulse.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.alphapulse.contract.ModuleInstallState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.ui.components.AlphaPulseHeader
import com.aistudio.alphapulse.ui.components.VipHeaderState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-57 — one AlphaPulse header and one navigation bar for the whole app.
 *
 * The app used to draw a `HeaderBar` on the analysis screen and a second `TopBar` on the
 * ProactiveMS workspace, plus an `AppShell` bar and a second `ProactiveBottomBar`. These
 * scenarios pin the merged surface and the entitlement boundary that decides which
 * navigation exists.
 *
 * Spec Ref: C-57, Phase 1 §7, Phase 2 §32/§72
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class SingleHeaderBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private fun header(vip: VipHeaderState? = null) {
        compose.setContent {
            AlphaPulseHeader(
                sessionElapsedSeconds = 0,
                isLTFReady = false,
                isMTFReady = false,
                isHTFReady = false,
                pipelineRunning = false,
                pipelineSecondsRemaining = 0,
                onRefreshClick = {},
                vip = vip
            )
        }
    }

    @Test
    fun `given the base app before entitlement when the shared navigation renders then it has exactly one VIP Features slot`() {
        compose.setContent {
            AlphaPulseNavBar(
                installState = ModuleInstallState.ENTITLEMENT_REQUIRED,
                downloadProgress = 0,
                activeTab = ProactiveMsTab.ALPHA_PULSE,
                onSelectTab = {},
                onUnlockVip = {}
            )
        }

        compose.onNodeWithTag(TraderNavigation.VIP_TAB_TAG).assertExists()
        compose.onAllNodesWithText("VIP Features").assertCountEquals(1)
        // The six workspace tabs do not exist until the feature does.
        ProactiveMsWorkspaceState.TAB_ORDER.forEach { tab ->
            assertTrue(
                "The ${tab.label} tab must not be advertised before entitlement",
                compose.onAllNodesWithText(tab.navLabel).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `given the module installed when the shared navigation renders then the six tabs appear with Alpha Pulse first`() {
        compose.setContent {
            AlphaPulseNavBar(
                installState = ModuleInstallState.INSTALLED,
                downloadProgress = 100,
                activeTab = ProactiveMsTab.ALPHA_PULSE,
                onSelectTab = {},
                onUnlockVip = {}
            )
        }

        ProactiveMsWorkspaceState.TAB_ORDER.forEach { tab ->
            compose.onNodeWithTag(ProactiveWorkspaceTags.tab(tab)).assertExists()
        }
        compose.onNodeWithText("Alpha Pulse").assertExists()
        assertEquals(
            "The first slot is the analysis screen once unlocked",
            "Alpha Pulse",
            ProactiveMsWorkspaceState.TAB_ORDER.first().navLabel
        )
        compose.onAllNodesWithText("VIP Features").assertCountEquals(0)
    }

    @Test
    fun `given the base app header when it renders then it carries no VIP controls or model quick actions`() {
        header(vip = null)

        compose.onNodeWithText("AlphaPulse").assertExists()
        compose.onAllNodesWithText("SESSION:", substring = true).assertCountEquals(1)
        // The two TSFM artefacts moved to the ML ensemble card (C-57).
        assertEquals(
            0,
            compose.onAllNodesWithText("Download Chronos-Bolt").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            compose.onAllNodesWithText("Download TimesFM-2.5").fetchSemanticsNodes().size
        )
        // No VIP furniture leaks into the unpaid surface.
        assertEquals(0, compose.onAllNodesWithText("PAPER").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Stop-out", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `given the VIP workspace header when it renders then the mode kill switch orphan guard stop out and hardware rows are present`() {
        header(
            vip = VipHeaderState(
                executionMode = ExecutionMode.PAPER,
                riskEnvelope = RiskEnvelope(worstCaseStopOutR = 0.8),
                venueHealth = VenueHealth(binancePingMs = 42, availableRamMb = 2_048),
                isSessionActive = true,
                isLiveUniverse = true,
                onModeChange = {},
                onToggleKillSwitch = {},
                onOpenOrphanWarning = {}
            )
        )

        compose.onNodeWithTag("mode_selector_pill").assertExists()
        compose.onNodeWithTag("kill_switch_button").assertExists()
        compose.onNodeWithTag("orphan_warning_button").assertExists()
        compose.onNodeWithTag("data_source_chip").assertExists()
        compose.onNodeWithText("PAPER", substring = true).assertExists()
        compose.onNodeWithText("Stop-out 0.8R").assertExists()
        compose.onNodeWithText("Ping: 42ms").assertExists()
        compose.onNodeWithText("RAM: 2048MB").assertExists()
        compose.onNodeWithText("LIVE SESSION").assertExists()
    }

    @Test
    fun `given the app sources when read then there is exactly one header and one tab bar`() {
        val sources = repoFile("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

        assertFalse(
            "TopBar was the second header and must not return",
            sources.any { it.name == "TopBar.kt" }
        )
        assertFalse(
            "HeaderBar was the second header and must not return",
            sources.any { it.name == "HeaderBar.kt" }
        )
        val topBarUsers = sources.filter { it.readText().contains("TopBar(") }
        assertTrue(
            "No surface may mount the retired header: ${topBarUsers.map { it.name }}",
            topBarUsers.isEmpty()
        )

        // Both hosts mount the one header, and the workspace the one tab bar.
        val workspace = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()
        assertTrue(workspace.contains("AlphaPulseHeader("))
        assertTrue(workspace.contains("AlphaPulseTabBar("))
        assertFalse(
            "The workspace must not keep a second, private bottom bar",
            workspace.contains("private fun ProactiveBottomBar")
        )

        val shell = repoFile("app/src/main/java/com/aistudio/alphapulse/ui/AppShell.kt").readText()
        assertTrue(shell.contains("AlphaPulseNavBar("))
        assertFalse(
            "The shell must not build its own NavigationBar",
            shell.contains("NavigationBar(")
        )

        // The quick actions live on the weights card now.
        val cards = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/components/TopWorkspaceCards.kt"
        ).readText()
        assertTrue(cards.contains("HeaderModelActions("))
        val headerFile = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/components/AlphaPulseHeader.kt"
        ).readText()
        assertFalse(
            "The header itself must not mount the model icons",
            headerFile.contains("HeaderModelActions(")
        )
    }

    @Test
    fun `given the shipped host when read then it refuses an unentitled launch and honours the requested tab`() {
        val host = repoFile(
            "proactive_trader/src/main/java/com/alphapulse/proactive/TraderHostActivity.kt"
        ).readText()

        assertTrue(
            "The paid host must re-check the persisted entitlement",
            host.contains("isVipEntitled()") && host.contains("finish()")
        )
        assertTrue(
            "The shared bar launches a specific tab",
            host.contains("EXTRA_INITIAL_TAB") || host.contains("requestedInitialTab()")
        )
        assertFalse(
            "An unknown tab name must not throw",
            host.contains("valueOf(name)\n") && !host.contains("runCatching")
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
