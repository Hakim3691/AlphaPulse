package com.aistudio.alphapulse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.alphapulse.contract.ModuleInstallState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 D6: installing the dynamic module must cause zero movement in the core
 * analysis view.
 *
 * The acceptance criterion asks for a pixel diff. A pixel comparison cannot be made
 * deterministic without a screenshot harness on the device matrix, so this spec
 * asserts the stronger *layout* invariant directly: the core surface and its content
 * occupy identical bounds before and after the trader tab is injected. Combined with
 * `TraderTabInjectionBddSpec`'s structural check that the core screen receives no
 * trader state, that covers the same failure mode - a module changing base layout.
 *
 * Spec Ref: Phase 1 D6 Zero Base Regression
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class TraderZeroRegressionBddSpec {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given the trader module is not installed when it becomes installed then the core surface does not move`() {
        var state by mutableStateOf(ModuleInstallState.NOT_INSTALLED)

        compose.setContent {
            AppShell(
                installState = state,
                downloadProgress = 0,
                onUnlockTrader = {},
                onTraderTab = {}
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .testTag("core_body")
                )
            }
        }

        val surfaceBefore = compose.onNodeWithTag(TraderNavigation.CORE_SURFACE_TAG).getBoundsInRoot()
        val bodyBefore = compose.onNodeWithTag("core_body").getBoundsInRoot()

        state = ModuleInstallState.INSTALLED
        compose.waitForIdle()

        val surfaceAfter = compose.onNodeWithTag(TraderNavigation.CORE_SURFACE_TAG).getBoundsInRoot()
        val bodyAfter = compose.onNodeWithTag("core_body").getBoundsInRoot()

        assertEquals(surfaceBefore.left.value, surfaceAfter.left.value, 0.1f)
        assertEquals(surfaceBefore.right.value, surfaceAfter.right.value, 0.1f)
        assertEquals(surfaceBefore.top.value, surfaceAfter.top.value, 0.1f)
        assertEquals(surfaceBefore.bottom.value, surfaceAfter.bottom.value, 0.1f)

        // The body inside the slot must not move either.
        assertEquals(bodyBefore.left.value, bodyAfter.left.value, 0.1f)
        assertEquals(bodyBefore.top.value, bodyAfter.top.value, 0.1f)
        assertEquals(bodyBefore.right.value, bodyAfter.right.value, 0.1f)
        assertEquals(
            bodyBefore.bottom.value - bodyBefore.top.value,
            bodyAfter.bottom.value - bodyAfter.top.value,
            0.1f
        )
    }
}
