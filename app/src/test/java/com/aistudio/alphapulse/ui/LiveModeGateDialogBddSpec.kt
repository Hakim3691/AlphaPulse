package com.aistudio.alphapulse.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-63 — a refused mode switch is visible, and it points at the gate that refused it.
 *
 * The refusal used to be stored in `isLiveModeBlocked` and rendered nowhere, so every
 * mode but Paper looked like a dead control. The dialog now names the missing
 * prerequisite and offers the Guardrails tab where it is satisfied.
 *
 * Spec Ref: §36, §38, C-63
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LiveModeGateDialogBddSpec {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given a refused Capped Live switch when the dialog renders then it says why and offers Guardrails`() {
        var dismissed = 0
        var openedGuardrails = 0

        compose.setContent {
            ModeRefusalDialog(
                isOpen = true,
                refusal = "Capped Live needs an active API session. Open Guardrails and enter your key.",
                onDismiss = { dismissed++ },
                onOpenGuardrails = { openedGuardrails++ }
            )
        }

        compose.onNodeWithTag(ProactiveWorkspaceTags.MODE_REFUSAL).assertExists()
        compose.onNodeWithText("MODE NOT AVAILABLE").assertExists()
        compose.onNodeWithText(
            "Capped Live needs an active API session. Open Guardrails and enter your key."
        ).assertExists()

        compose.onNodeWithText("OPEN GUARDRAILS").performClick()

        assertEquals(1, dismissed)
        assertEquals(1, openedGuardrails)
    }

    @Test
    fun `given no refusal when the dialog renders then nothing is shown`() {
        compose.setContent {
            ModeRefusalDialog(isOpen = false, refusal = null, onDismiss = {})
        }

        compose.onNodeWithTag(ProactiveWorkspaceTags.MODE_REFUSAL).assertDoesNotExist()
    }

    @Test
    fun `given the refused mode when the host wiring is read then the dialog is rendered with the Guardrails action`() {
        val workspace = RepoFiles.of(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveWorkspace.kt"
        ).readText()

        assertTrue(
            "The live-mode gate is driven by the state the view model sets",
            workspace.contains("isOpen = state.isLiveModeBlocked") &&
                workspace.contains("onOpenGuardrails = actions.onOpenGuardrails")
        )
        assertTrue(
            "The action switches to the Guardrails tab",
            workspace.contains("selectTab(ProactiveMsTab.GUARDRAILS)")
        )
        assertTrue(
            "The plan's dialog tag is the one the specs address",
            ProactiveWorkspaceTags.MODE_REFUSAL == "live_mode_gate_dialog"
        )
    }
}
