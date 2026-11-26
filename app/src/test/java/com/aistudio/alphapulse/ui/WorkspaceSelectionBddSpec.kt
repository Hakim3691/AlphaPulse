package com.aistudio.alphapulse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C-62 — one selection, carried by one store.
 *
 * AlphaPulse selected a coin and the Dashboard showed `SOLUSDT`, because the campaign
 * engine chose its own first row and re-chose it on every boot. The workspace now has a
 * single process-wide selection: AlphaPulse writes it, the engine reads it on boot, and a
 * refresh may update the coin but never replace it.
 *
 * The resolution rule is a pure function so the boot path, the poll path and the tests
 * cannot disagree about what "the selected coin" means.
 *
 * Spec Ref: C-22, C-58, C-62
 */
class WorkspaceSelectionBddSpec {

    @Test
    fun `given a coin selected in AlphaPulse when the engine resolves its starting coin then it is that coin, not the list's first row`() {
        // Given: AlphaPulse's screen selected PAXG while SOL ranked first.
        val available = listOf("SOLUSDT", "PAXGUSDT", "BTWUSDT")

        // When: the engine resolves the coin to inspect.
        val resolved = WorkspaceSelection.resolveSymbol("PAXGUSDT", available)

        // Then: the operator's coin wins and the first row is not substituted.
        assertEquals("PAXGUSDT", resolved)
    }

    @Test
    fun `given no coin has been selected yet when the engine resolves its starting coin then it takes the first admitted row`() {
        assertEquals(
            "SOLUSDT",
            WorkspaceSelection.resolveSymbol(null, listOf("SOLUSDT", "PAXGUSDT"))
        )
        assertEquals(
            "SOLUSDT",
            WorkspaceSelection.resolveSymbol("   ", listOf("SOLUSDT", "PAXGUSDT"))
        )
    }

    @Test
    fun `given the selected coin is missing from the admitted universe when resolved then it is null so the caller reloads instead of repointing the desk`() {
        // The coin may be withheld from the campaign universe (C-58) or the universe may
        // simply not be loaded yet. Neither is a reason to inspect a different coin.
        assertNull(
            WorkspaceSelection.resolveSymbol("USDBRLUSDT", listOf("SOLUSDT", "PAXGUSDT"))
        )
    }

    @Test
    fun `given an empty universe when resolved then no coin is invented`() {
        assertNull(WorkspaceSelection.resolveSymbol(null, emptyList()))
        assertNull(WorkspaceSelection.resolveSymbol("PAXGUSDT", emptyList()))
    }

    @Test
    fun `given the shared store when a coin is selected then every reader observes the same symbol`() {
        val store = WorkspaceSelectionStore()

        store.select("BTWUSDT")

        assertEquals("BTWUSDT", store.selectedSymbol.value)
        // A blank selection is not a selection: it must not blank the desk's coin.
        store.select("  ")
        assertEquals("BTWUSDT", store.selectedSymbol.value)
        assertEquals("BTWUSDT", store.selectedSymbol.value)
    }

    @Test
    fun `given the workspace wiring when read then the selection store is the one coin source`() {
        val workspace = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()
        val engine = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/MainViewModel.kt"
        ).readText()
        val alphaPulse = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/viewmodel/AlphaPulseViewModel.kt"
        ).readText()

        // AlphaPulse publishes what its institutional screen selected.
        assertEquals(
            true,
            alphaPulse.contains("WorkspaceSelectionStore") &&
                alphaPulse.contains("selectionStore.select")
        )
        // The engine boots and refreshes from that one selection.
        assertEquals(
            true,
            engine.contains("WorkspaceSelection") && engine.contains("selectionStore")
        )
        // The workspace has no second coin source of its own.
        assertEquals(true, workspace.contains("WorkspaceSelectionStore"))
    }

    private fun repoFile(relativePath: String): java.io.File = RepoFiles.of(relativePath)
}
