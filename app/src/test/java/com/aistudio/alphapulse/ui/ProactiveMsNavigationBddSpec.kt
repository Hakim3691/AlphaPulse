package com.aistudio.alphapulse.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §72 / Contract C-22 — the unified tab navigation and its shared active symbol.
 *
 * The tab order and the workspace state live in production
 * (`ui/ProactiveMsNavigation.kt`); this spec drives them, so dropping a tab, reordering
 * the bar, or letting a tab keep its own symbol breaks the build.
 *
 * The legacy `Screener` tab is retired (C-22): AlphaPulse's institutional screen is the
 * one coin selector, so there is no second list and no `+ BTC ONLY` universe lock that
 * could disagree with it.
 *
 * Spec Ref: §72, C-22
 */
class ProactiveMsNavigationBddSpec {

    @Test
    fun `given the ProactiveMS bottom navigation when inspected then exactly six tabs are mounted in the specified order`() {
        assertEquals(
            listOf(
                "AlphaPulse",
                "Dashboard",
                "Telemetry",
                "Guardrails",
                "Replay",
                "Audit"
            ),
            ProactiveMsWorkspaceState.TAB_ORDER.map { it.label }
        )
        assertEquals(6, ProactiveMsTab.entries.size)
        assertTrue(
            "The retired Screener tab must not come back as a second coin selector",
            ProactiveMsTab.entries.none { it.name == "SCREENER" }
        )
    }

    @Test
    fun `given the workspace opens when no tab has been chosen then it opens on AlphaPulse, the one coin selector`() {
        assertEquals(
            ProactiveMsTab.ALPHA_PULSE,
            ProactiveMsWorkspaceState().activeTab
        )
    }

    @Test
    fun `given a coin is selected in AlphaPulse when the operator moves to the Audit tab then the same symbol is active with no re-selection`() {
        var workspace = ProactiveMsWorkspaceState()

        workspace = workspace.selectSymbol("PAXGUSDT")
        workspace = workspace.selectTab(ProactiveMsTab.AUDIT)

        assertEquals(ProactiveMsTab.AUDIT, workspace.activeTab)
        assertEquals("PAXGUSDT", workspace.activeSymbol)

        // Every other tab reports the same selection: it is one value, not six copies.
        for (tab in ProactiveMsTab.entries) {
            assertEquals("PAXGUSDT", workspace.selectTab(tab).activeSymbol)
        }
    }

    @Test
    fun `given no coin has been selected yet when the workspace is read then no symbol is invented for the tabs`() {
        val workspace = ProactiveMsWorkspaceState()

        assertNull(workspace.activeSymbol)
    }

    @Test
    fun `given the workspace sources when scanned then the retired screener screen, symbol strip and BTC lock are gone`() {
        // C-22 retired the second selector on purpose: it listed the same coins AlphaPulse
        // had already screened, under the `$50M-$750M` band C-17 removed, and its selection
        // could disagree with the campaign engine's.
        val appSources = repoFile("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

        val removed = listOf(
            "ScreenerScreen.kt" to "the legacy mid-cap screener screen",
            "SymbolStrip" to "the shared symbol strip",
            "availableSymbols" to "the strip's universe list",
            "BTC_ONLY_SYMBOL" to "the `+ BTC ONLY` universe lock"
        )
        for ((token, what) in removed) {
            val offenders = appSources.filter { file ->
                if (token.endsWith(".kt")) file.name == token else file.readText().contains(token)
            }
            assertTrue(
                "C-22 retired $what; it is still present in: " +
                    offenders.joinToString { it.name },
                offenders.isEmpty()
            )
        }

        assertFalse(
            "The workspace must not mount a second screener tab",
            repoFile("app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt")
                .readText()
                .contains("ProactiveMsTab.SCREENER")
        )
    }

    @Test
    fun `given AlphaPulse selects a coin when the workspace is wired then the campaign engine adopts the screened coin`() {
        // The rest of the tabs work from the screened universe, so AlphaPulse's selection
        // must reach the engine, not only the workspace's own label.
        val workspace = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()
        val mainViewModel = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/MainViewModel.kt"
        ).readText()

        assertTrue(
            "The workspace must mirror AlphaPulse's selection into the engine",
            workspace.contains("adoptScreenedUniverse(")
        )
        assertTrue(
            "The engine must adopt a screened coin, reloading the screened universe when needed",
            mainViewModel.contains("fun adoptScreenedUniverse(") &&
                mainViewModel.contains("alphaPulseUniverseSnapshot()")
        )
    }

    @Test
    fun `given the AlphaPulse tab when its source is read then no ProactiveMS campaign or live-order board is mounted on it`() {
        // The merge that added the ProactiveMS boards put "LIVE CAMPAIGNS & ORDERS" under
        // the AlphaPulse ledgers and was described as rolled back while it was still there.
        // This is the rollback, asserted: AlphaPulse renders AlphaPulse only (C-22).
        val screen = repoFile("app/src/main/java/com/aistudio/alphapulse/ui/AlphaPulseScreen.kt").readText()
        assertFalse(
            "AlphaPulseScreen must not mount the ProactiveMS campaign workspace",
            screen.contains("CampaignWorkspaceSection")
        )
        assertFalse(
            "AlphaPulseScreen must not take the campaign view model",
            screen.contains("campaignViewModel")
        )

        val activity = repoFile("app/src/main/java/com/aistudio/alphapulse/MainActivity.kt").readText()
        assertFalse(
            "MainActivity must not hand the campaign view model to the AlphaPulse screen",
            activity.contains("campaignViewModel")
        )
        assertTrue(
            "The app root must mount the AlphaPulse screen with its own view model only",
            activity.contains("AlphaPulseScreen(viewModel = viewModel)")
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
