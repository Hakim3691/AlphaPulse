package com.aistudio.alphapulse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-62 — the app has one workspace host, not two.
 *
 * Tapping `Dashboard` used to launch `TraderHostActivity` with `FLAG_ACTIVITY_NEW_TASK`,
 * which built a second `MainViewModel` and a second `AlphaPulseViewModel` and re-ran the
 * screening boot on every tab change. Forecasts in flight were abandoned and the chosen
 * coin was reinterpreted by whichever host happened to be mounted.
 *
 * The workspace now renders inside the one activity that already runs the analysis view
 * model, so switching a tab is a state change — never a second host, never a second boot.
 *
 * Spec Ref: §7, §32, §72, C-22, C-57, C-62
 */
class SingleWorkspaceHostBddSpec {

    @Test
    fun `given the app root when the module is installed then it mounts the workspace in this activity`() {
        val activity = RepoFiles.of("app/src/main/java/com/aistudio/alphapulse/MainActivity.kt").readText()

        assertTrue(
            "MainActivity must mount the one workspace",
            activity.contains("mountWorkspaceHost(") ||
                activity.contains("ProactiveWorkspaceScreen(")
        )
        assertTrue(
            "The host must own the campaign engine's view model",
            activity.contains("MainViewModel")
        )
        assertTrue(
            "The analysis screen stays in the same composition, with its own view model",
            activity.contains("AlphaPulseScreen(viewModel = viewModel)") &&
                activity.contains("alphaPulseViewModel = viewModel")
        )
    }

    @Test
    fun `given the shared navigation when a workspace tab is tapped then no second activity is launched`() {
        val activity = RepoFiles.of("app/src/main/java/com/aistudio/alphapulse/MainActivity.kt").readText()

        assertFalse(
            "A tab tap must not launch a second host: tab state is local state now",
            activity.contains("launchTraderTab(")
        )
        assertFalse(
            "A tab tap must not start a new task for the paid workspace",
            activity.contains("launchTraderDashboard(")
        )
    }

    @Test
    fun `given the workspace when read then it mirrors one coin source and never closes a tab switch with a reselection`() {
        val workspace = RepoFiles.of(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()

        assertFalse(
            "The engine's own asset must not be a second coin source in the workspace: " +
                "that effect re-ran on every mount and repointed the desk",
            workspace.contains("LaunchedEffect(state.selectedAsset?.symbol)")
        )
        assertTrue(
            "AlphaPulse remains the one selector and still reaches the engine",
            workspace.contains("adoptScreenedUniverse(")
        )
    }

    @Test
    fun `given the workspace tabs when a tab is switched then each tab keeps its own saveable state`() {
        val workspace = RepoFiles.of(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()

        assertTrue(
            "Each tab's scroll/list state must be retained across a switch",
            workspace.contains("rememberSaveableStateHolder()")
        )
        assertTrue(
            "The retained state is keyed by the tab",
            workspace.contains("SaveableStateProvider(")
        )
        assertTrue(
            "The selected tab must survive activity recreation",
            workspace.contains("rememberSaveable(")
        )
    }

    @Test
    fun `given the campaign engine when the universe is reapplied then the inspected campaign is kept by id`() {
        val engine = RepoFiles.of("app/src/main/java/com/aistudio/alphapulse/ui/MainViewModel.kt").readText()

        assertTrue(
            "A refresh must not reset the operator's campaign selection",
            engine.contains("selectedCampaign = gatedCampaigns")
        )
    }
}
