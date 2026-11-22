package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import com.aistudio.alphapulse.data.model.ExecutionMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 §7 / Phase 2 §32 — the six-to-seven ProactiveMS tabs are a surface, not a Toast.
 *
 * `TraderHostActivity` declared its own six-label list (`Analysis` instead of the
 * canonical `AlphaPulse`, no `Replay`), contradicted `ProactiveMsWorkspaceState.TAB_ORDER`,
 * and every tab's click handler did nothing but raise a Toast. The workspace selection the
 * contract requires — one symbol shared by every tab — was therefore never exercised in
 * the shipped app.
 *
 * This spec drives the real renderer and asserts the shipped host uses the canonical tab
 * order and mounts a rendering body, so a tab cannot regress to a dead button.
 *
 * Spec Ref: Phase 1 §7, Phase 2 §32, C-22
 */
class ProactiveTabsBddSpec {

    private val workspace = ProactiveMsWorkspaceState(
        activeSymbol = "PAXGUSDT",
        verifiedSymbols = listOf("BTCUSDT", "PAXGUSDT", "SOPHUSDT")
    )

    @Test
    fun `given a screened universe when the Screener tab renders then it shows the verified symbols and the shared selection`() {
        val lines = ProactiveTabRenderer.lines(ProactiveMsTab.SCREENER, MainUiState(), workspace)

        assertTrue(lines.any { it.contains("BTCUSDT") })
        assertTrue(lines.any { it.contains("SOPHUSDT") })
        assertTrue(lines.any { it.contains("PAXGUSDT") })
        assertTrue(
            "The tab must name the selection every other tab shares",
            lines.any { it.contains("Active", ignoreCase = true) }
        )
    }

    @Test
    fun `given no coin is selected when any tab renders then it says so instead of inventing a symbol`() {
        val empty = ProactiveMsWorkspaceState()

        ProactiveMsTab.entries.forEach { tab ->
            val lines = ProactiveTabRenderer.lines(tab, MainUiState(), empty)
            assertTrue("The $tab tab must render something", lines.isNotEmpty())
            assertFalse(
                "The $tab tab must not fabricate a coin: $lines",
                lines.any { it.contains("BTCUSDT") && tab != ProactiveMsTab.ALPHA_PULSE }
            )
        }
    }

    @Test
    fun `given a production configuration when Guardrails renders then it publishes the §34 values in force`() {
        val lines = ProactiveTabRenderer.lines(
            ProactiveMsTab.GUARDRAILS,
            MainUiState(executionMode = ExecutionMode.PAPER),
            workspace
        )

        assertTrue(lines.any { it.contains("PAPER") })
        assertTrue("The 2.0R daily stop must be visible", lines.any { it.contains("2.0R") })
        assertTrue("The 30-minute lockout must be visible", lines.any { it.contains("30") })
    }

    @Test
    fun `given recorded decisions when Audit renders then it reads the ledger rather than a placeholder`() {
        val lines = ProactiveTabRenderer.lines(
            ProactiveMsTab.AUDIT,
            MainUiState(
                auditLogs = listOf(
                    AuditLogEntry(
                        asset = "PAXGUSDT",
                        campaignId = "campaign-1",
                        action = AuditLogAction.SOFT_CANCEL,
                        reasonCode = "OPERATOR_SOFT_CANCEL",
                        message = "Unfilled resting slices cancelled"
                    )
                )
            ),
            workspace
        )

        assertTrue(lines.any { it.contains("OPERATOR_SOFT_CANCEL") || it.contains("SOFT_CANCEL") })
    }

    @Test
    fun `given the shipped host when its source is read then it uses the canonical tab order and mounts a real body`() {
        val host = repoFile(
            "proactive_trader/src/main/java/com/alphapulse/proactive/TraderHostActivity.kt"
        ).readText()

        assertTrue(
            "The host must render the canonical seven-tab contract, not its own list",
            host.contains("ProactiveMsWorkspaceState.TAB_ORDER")
        )
        assertTrue(
            "The host must render tab content through the shared renderer",
            host.contains("ProactiveTabRenderer")
        )
        assertFalse(
            "A tab click must not be a Toast placeholder",
            host.contains("Toast.makeText")
        )
        assertFalse(
            "The retired six-label list must not come back",
            host.contains("PROACTIVE_MS_TABS")
        )
    }

    @Test
    fun `given the feature host when read then the seven tabs are its bottom bar below the content`() {
        val host = repoFile(
            "proactive_trader/src/main/java/com/alphapulse/proactive/TraderHostActivity.kt"
        ).readText()

        assertTrue(
            "C-22: the seven tabs must be the workspace's bottom bar, or the shipped screen " +
                "shows a top strip and the bottom navigation the contract names is missing",
            host.contains("bottomBar")
        )
        assertTrue(
            "The bar must be added to the root after the scrollable tab body, so it sits at " +
                "the bottom of the screen: $host",
            host.indexOf("bottomBar =") > host.indexOf("bodyText =")
        )
        assertTrue(
            "Each of the seven tabs must get one equal slot, so none is pushed off-screen: $host",
            host.contains("LinearLayout.LayoutParams(0") &&
                host.contains("1f") &&
                host.contains("TAB_ORDER.forEach")
        )
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
