package com.aistudio.alphapulse.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import com.aistudio.alphapulse.data.model.ExecutionMode
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
 * Phase 1 §7 / Phase 2 §32 / C-22 — the seven ProactiveMS tabs are a real workspace.
 *
 * The shipped host used to print each tab as joined plain text: a cyan heading, symbol
 * labels and one `TextView` body. Two of those tabs were visibly redundant with the
 * AlphaPulse screen (the AlphaPulse tab was a four-line summary instead of the analysis
 * screen, and the Screener tab was a bare list), and none of them looked like the rest of
 * the app.
 *
 * These scenarios drive the composed workspace that replaced it: the same one the
 * `:proactive_trader` host mounts. They assert what an operator sees and does — the shared
 * symbol, the §34 limits, the audit ledger, the real AlphaPulse screen — rather than the
 * retired string renderer.
 *
 * Spec Ref: Phase 1 §7, Phase 2 §32, C-22
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ProactiveTabsBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private val workspace = ProactiveMsWorkspaceState(
        activeSymbol = "PAXGUSDT",
        verifiedSymbols = listOf("BTCUSDT", "PAXGUSDT", "SOPHUSDT")
    )

    private fun render(
        state: MainUiState = MainUiState(),
        workspace: ProactiveMsWorkspaceState = this.workspace,
        actions: ProactiveWorkspaceActions = ProactiveWorkspaceActions(onSelectTab = {}),
        alphaPulseTab: @androidx.compose.runtime.Composable () -> Unit = {}
    ) {
        compose.setContent {
            ProactiveWorkspaceScreen(
                state = state,
                workspace = workspace,
                actions = actions,
                alphaPulseTab = alphaPulseTab
            )
        }
    }

    @Test
    fun `given a screened universe when the Screener tab renders then it shows the verified symbols and selecting one changes the shared symbol`() {
        var selected: String? = null
        render(
            workspace = workspace.copy(activeTab = ProactiveMsTab.SCREENER),
            actions = ProactiveWorkspaceActions(
                onSelectTab = {},
                onSelectSymbol = { selected = it }
            )
        )

        compose.onAllNodesWithText("BTCUSDT", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("SOPHUSDT", substring = true).assertCountEquals(1)
        assertTrue(
            "The active coin is shown in the shared symbol strip",
            compose.onAllNodesWithText("PAXGUSDT", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithTag("screener_screen").assertExists()

        compose.onNodeWithTag(ProactiveWorkspaceTags.symbol("SOPHUSDT")).performClick()

        assertEquals(
            "Selecting a coin anywhere selects it for every tab (C-22)",
            "SOPHUSDT",
            selected
        )
    }

    @Test
    fun `given no coin is selected yet when the workspace renders then it says so instead of inventing a symbol`() {
        render(
            state = MainUiState(),
            workspace = ProactiveMsWorkspaceState(activeTab = ProactiveMsTab.SCREENER)
        )

        compose.onNodeWithText("The screener has not admitted a coin yet").assertExists()
        compose.onAllNodesWithText("BTCUSDT", substring = true).assertCountEquals(0)
    }

    @Test
    fun `given no selected asset when the Dashboard tab renders then it asks for a screener selection instead of fabricating one`() {
        render(
            state = MainUiState(),
            workspace = workspace.copy(activeTab = ProactiveMsTab.DASHBOARD)
        )

        compose.onNodeWithText("Select an asset from the Screener").assertExists()
    }

    @Test
    fun `given a production configuration when Guardrails renders then the §34 values in force are visible`() {
        render(
            state = MainUiState(executionMode = ExecutionMode.PAPER),
            workspace = workspace.copy(activeTab = ProactiveMsTab.GUARDRAILS)
        )

        compose.onNodeWithTag(ProactiveWorkspaceTags.PRE_LAUNCH_GATE).assertExists()
        assertTrue(
            "The daily loss stop must be visible",
            compose.onAllNodesWithText("2.0R", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "The re-entry lockout must be visible",
            compose.onAllNodesWithText("30 min", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "The current execution mode must be visible",
            compose.onAllNodesWithText("PAPER", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun `given recorded decisions when the Audit tab renders then it reads the ledger rows rather than a placeholder`() {
        render(
            state = MainUiState(
                auditLogs = listOf(
                    AuditLogEntry(
                        id = 1,
                        asset = "PAXGUSDT",
                        campaignId = "campaign-1",
                        action = AuditLogAction.SOFT_CANCEL,
                        reasonCode = "OPERATOR_SOFT_CANCEL",
                        message = "Unfilled resting slices cancelled"
                    )
                )
            ),
            workspace = workspace.copy(activeTab = ProactiveMsTab.AUDIT)
        )

        compose.onNodeWithTag("proactive_audit_tab").assertExists()
        compose.onNodeWithText("OPERATOR_SOFT_CANCEL").assertExists()
        compose.onNodeWithText("Unfilled resting slices cancelled").assertExists()
        compose.onAllNodesWithTag("audit_row_1").assertCountEquals(1)
    }

    @Test
    fun `given the AlphaPulse tab when it renders then the workspace supplies the only header and the tab body adds none`() {
        render(
            workspace = workspace.copy(activeTab = ProactiveMsTab.ALPHA_PULSE),
            alphaPulseTab = { Text("ALPHA_PULSE_BODY") }
        )

        compose.onNodeWithText("ALPHA_PULSE_BODY").assertExists()
        // One header, not two: the workspace's identity appears exactly once above the
        // body, so nothing is stacked there twice.
        compose.onAllNodesWithText("ProactiveMS").assertCountEquals(1)
        compose.onAllNodesWithText("Cancellation-First Trading Automation").assertCountEquals(1)
    }

    @Test
    fun `given the embedded AlphaPulse tab when its source is read then it renders the analysis body without a second scaffold header or inset set`() {
        val screen = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/AlphaPulseScreen.kt"
        ).readText()
        val workspace = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()

        assertTrue(
            "The workspace must mount the body-only AlphaPulse entry",
            workspace.contains("AlphaPulseScreenContent(viewModel = alphaPulseViewModel)")
        )
        assertFalse(
            "Nesting the full AlphaPulse screen would double the header and the insets",
            workspace.contains("AlphaPulseScreen(viewModel = alphaPulseViewModel)")
        )

        // The standalone screen keeps its own Scaffold, header and safe-drawing insets...
        val standalone = functionBody(screen, "fun AlphaPulseScreen(")
        assertTrue(standalone.contains("Scaffold("))
        assertTrue(standalone.contains("HeaderBar("))
        assertTrue(standalone.contains("WindowInsets.safeDrawing"))

        // ...and the embedded entry is the same analysis surface with none of that.
        val embedded = functionBody(screen, "fun AlphaPulseScreenContent(")
        for (chrome in listOf("Scaffold(", "HeaderBar(", "WindowInsets")) {
            assertFalse(
                "AlphaPulseScreenContent must not draw '$chrome'; the workspace owns it",
                embedded.contains(chrome)
            )
        }
        assertTrue(
            "The embedded tab must render the same analysis surface as the screen",
            embedded.contains("AlphaPulseAnalysis(")
        )
    }

    @Test
    fun `given the workspace when it renders then the seven canonical tabs are its bottom bar`() {
        render()

        ProactiveMsWorkspaceState.TAB_ORDER.forEach { tab ->
            compose.onNodeWithTag(ProactiveWorkspaceTags.tab(tab)).assertExists()
        }
        assertEquals(7, ProactiveMsWorkspaceState.TAB_ORDER.size)
    }

    @Test
    fun `given the shipped host when its source is read then it mounts the professional workspace and renders no plain-text body`() {
        val host = repoFile(
            "proactive_trader/src/main/java/com/alphapulse/proactive/TraderHostActivity.kt"
        ).readText()
        val workspace = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()

        assertTrue(
            "The host must mount the shared workspace instead of building its own views",
            host.contains("mountProactiveWorkspace")
        )
        assertFalse(
            "A tab click must not be a Toast placeholder",
            host.contains("Toast.makeText")
        )
        assertFalse(
            "The plain-text tab body must not come back",
            host.contains("bodyText")
        )
        assertTrue(
            "C-22: the seven tabs are the workspace's bottom bar, one slot each",
            workspace.contains("ProactiveMsWorkspaceState.TAB_ORDER")
        )

        // Every tab is the product screen an operator expects, not a summary line.
        for (screen in listOf(
            "AlphaPulseScreenContent(",
            "ScreenerScreen(",
            "DashboardScreen(",
            "ProactiveTelemetryTab(",
            "RiskGuardrailsScreen(",
            "SimulationScreen(",
            "ProactiveAuditTab("
        )) {
            assertTrue("The workspace must mount $screen for its tab", workspace.contains(screen))
        }
    }

    @Test
    fun `given the app sources when scanned then exactly one tab order exists`() {
        val appSources = repoFile("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

        for (retired in listOf("AppDestination", "AppBottomNavigationBar")) {
            val offenders = appSources.filter { it.readText().contains(retired) }
            assertTrue(
                "C-22 has one tab order, `ProactiveMsWorkspaceState.TAB_ORDER`. '$retired' " +
                    "duplicated it with a different order and had no call site; found in: " +
                    "${offenders.joinToString { it.name }}",
                offenders.isEmpty()
            )
        }
        assertFalse(
            "The dead navigation model must not come back as a second tab surface",
            appSources.any { it.name == "Navigation.kt" }
        )
    }

    /** The brace-matched body of the composable whose signature starts with [signature]. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) throw AssertionError("Missing function: $signature")

        var index = source.indexOf('{', start)
        if (index < 0) throw AssertionError("No body for: $signature")
        val bodyStart = index
        var depth = 0
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
            index++
        }
        throw AssertionError("Unbalanced braces for: $signature")
    }

    /** Locates a repository file from whichever directory tests run in. */
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
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
