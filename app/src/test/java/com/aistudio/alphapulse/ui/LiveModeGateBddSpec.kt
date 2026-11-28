package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.data.model.ExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-63 — every execution mode is reachable, and a refusal is visible.
 *
 * Only Paper Mode appeared to work: the mode pill switched to Shadow or Scaled and
 * nothing on screen changed, while `isLiveModeBlocked` was set in the view model and
 * never rendered. A mode now either switches and says what it does, or refuses with the
 * missing prerequisite named.
 *
 * Spec Ref: §36, §38, C-63
 */
class LiveModeGateBddSpec {

    @Test
    fun `given Shadow and Paper when selected then they switch with no prerequisite`() {
        for (mode in listOf(ExecutionMode.SHADOW, ExecutionMode.PAPER)) {
            val decision = ExecutionModeGate.evaluate(
                mode = mode,
                hasActiveSession = false,
                preLaunchUnlocked = false
            )

            assertTrue("${mode.name} must be selectable", decision.allowed)
            assertNull(decision.refusal)
        }
    }

    @Test
    fun `given a live mode with no API session when selected then it refuses and names the missing session`() {
        for (mode in listOf(ExecutionMode.CAPPED_LIVE, ExecutionMode.SCALED_LIVE)) {
            val decision = ExecutionModeGate.evaluate(
                mode = mode,
                hasActiveSession = false,
                preLaunchUnlocked = true
            )

            assertFalse("${mode.name} must not switch without a session", decision.allowed)
            assertNotNull(decision.refusal)
            assertTrue(
                "the refusal must name the session: ${decision.refusal}",
                decision.refusal!!.contains("API session")
            )
        }
    }

    @Test
    fun `given Scaled Live with a session but no pre-launch evidence when selected then it refuses and names the §38 gate`() {
        val decision = ExecutionModeGate.evaluate(
            mode = ExecutionMode.SCALED_LIVE,
            hasActiveSession = true,
            preLaunchUnlocked = false
        )

        assertFalse(decision.allowed)
        assertTrue(
            "the refusal must name the pre-launch gate: ${decision.refusal}",
            decision.refusal!!.contains("pre-launch")
        )
    }

    @Test
    fun `given a live mode whose prerequisites are met when selected then it switches`() {
        assertTrue(
            ExecutionModeGate.evaluate(
                ExecutionMode.CAPPED_LIVE,
                hasActiveSession = true,
                preLaunchUnlocked = false
            ).allowed
        )
        assertTrue(
            ExecutionModeGate.evaluate(
                ExecutionMode.SCALED_LIVE,
                hasActiveSession = true,
                preLaunchUnlocked = true
            ).allowed
        )
    }

    @Test
    fun `given any mode when the workspace renders it then there is a banner that says what the mode actually does`() {
        val expected = mapOf(
            ExecutionMode.SHADOW to "plan",
            ExecutionMode.PAPER to "simulated",
            ExecutionMode.CAPPED_LIVE to "capped",
            ExecutionMode.SCALED_LIVE to "scaled"
        )

        for ((mode, word) in expected) {
            val banner = ExecutionModeGate.banner(mode)
            assertTrue(
                "the ${mode.name} banner must say what the mode does: $banner",
                banner.contains(word, ignoreCase = true)
            )
        }
        assertEquals(
            ExecutionMode.entries.size,
            ExecutionMode.entries.map { ExecutionModeGate.banner(it) }.distinct().size
        )
    }

    @Test
    fun `given a switched mode when the app restarts then the persisted mode is restored`() {
        val store = RecordingModeStore(initial = ExecutionMode.CAPPED_LIVE)

        assertEquals(ExecutionMode.CAPPED_LIVE, store.load())
        store.save(ExecutionMode.SHADOW)
        assertEquals(ExecutionMode.SHADOW, store.selected)
    }

    @Test
    fun `given the view model when a refused mode is selected then the refusal reaches the UI state`() {
        val engine = RepoFiles.of("app/src/main/java/com/aistudio/alphapulse/ui/MainViewModel.kt").readText()
        val workspace = RepoFiles.of("app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt").readText()

        assertTrue("the view model must render the refusal it computed", engine.contains("modeRefusal"))
        assertTrue(
            "the workspace must render the refusal dialog",
            workspace.contains("mode_refusal_dialog") || workspace.contains("ModeRefusalDialog")
        )
        assertTrue(
            "the workspace must mount the mode banner",
            workspace.contains("MODE_BANNER")
        )
    }

    @Test
    fun `given a persisted live mode without a session when the engine boots then it records that it fell back`() {
        val engine = RepoFiles.of("app/src/main/java/com/aistudio/alphapulse/ui/MainViewModel.kt").readText()

        assertTrue(
            "The persisted mode must be restored through the gate, never trusted blindly",
            engine.contains("executionModeStore.load()") &&
                engine.contains("restoredDecision.allowed")
        )
        assertTrue(
            "A fallback to Paper must be explainable in the ledger",
            engine.contains("MODE_RESTORED_WITHOUT_SESSION")
        )
        assertTrue(
            "The Scaled refusal must name the unmet §38 criteria, not just the gate",
            engine.contains("preLaunch.summary")
        )
    }

    /** A tiny in-memory stand-in for the persisted store the view model owns. */
    private class RecordingModeStore(initial: ExecutionMode) {
        var selected: ExecutionMode = initial
        fun load(): ExecutionMode = selected
        fun save(mode: ExecutionMode) {
            selected = mode
        }
    }
}
