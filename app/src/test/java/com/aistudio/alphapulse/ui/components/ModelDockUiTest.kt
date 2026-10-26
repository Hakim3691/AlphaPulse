package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.data.model.ModelLoadStatus
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.profiling.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ModelDockUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val idleStates = mapOf(
        "chronos" to ModelDownloadState("chronos"),
        "timesfm" to ModelDownloadState("timesfm"),
        "needle3" to ModelDownloadState("needle3")
    )

    /** A phone that comfortably meets the mid-tier line, so nothing is gated. */
    private val midTierPhone = DeviceProfile(totalRamGb = 8.0, cpuCores = 8)

    /** A phone under the mid-tier line (< 3.5 GB RAM or < 4 cores). */
    private val lowTierPhone = DeviceProfile(totalRamGb = 2.0, cpuCores = 2)

    @Test
    fun `idle tiles expose one download trigger each and report the model id`() {
        var clicked: String? = null
        compose.setContent {
            ModelDownloadSection(
                states = idleStates,
                onDownload = { clicked = it },
                deviceProfile = midTierPhone
            )
        }

        compose.onNodeWithText("TSFM MODEL DOCK").assertExists()
        compose.onNodeWithText("0/3 READY").assertExists()
        compose.onNodeWithText("Zero-shot forecaster").assertExists()
        compose.onNodeWithText("Long-context transformer").assertExists()
        compose.onNodeWithText("Edge orchestration model").assertExists()
        compose.onAllNodesWithContentDescription("Download model").assertCountEquals(3)

        compose.onAllNodesWithContentDescription("Download model")[0].performClick()
        assertEquals("chronos", clicked)
    }

    @Test
    fun `given a below mid-tier phone when the dock renders then every mid-tier model is locked and offers no download button`() {
        compose.setContent {
            ModelDownloadSection(
                states = idleStates,
                onDownload = {},
                deviceProfile = lowTierPhone
            )
        }

        // Under the mid-tier line (< 3.5 GB RAM or < 4 cores) the three mid-tier
        // models - Chronos, TimesFM and Needle 3 - must all be locked.
        compose.onAllNodesWithContentDescription("Download model").assertCountEquals(0)
        compose.onAllNodesWithText("BLOCKED").assertCountEquals(3)
        compose.onNodeWithText("0/3 READY").assertExists()
    }

    @Test
    fun `downloading shows percentage and ready shows badge`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ModelDownloadSection(
                states = mapOf(
                    "chronos" to ModelDownloadState("chronos", ModelLoadStatus.DOWNLOADING, 0.42f),
                    "timesfm" to ModelDownloadState("timesfm", ModelLoadStatus.READY, 1f)
                ),
                onDownload = {},
                deviceProfile = midTierPhone
            )
        }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("42%").assertExists()
        compose.onNodeWithText("READY").assertExists()
        compose.onNodeWithText("IN ENSEMBLE").assertExists()
        compose.onNodeWithText("1/3 READY").assertExists()
        // The downloading tile cannot be triggered again; only the idle Needle 3 tile can.
        compose.onAllNodesWithContentDescription("Download model").assertCountEquals(1)
    }

    @Test
    fun `dock stays compact - under 110dp versus the previous ~215dp card`() {
        compose.setContent {
            ModelDownloadSection(states = idleStates, onDownload = {}, deviceProfile = midTierPhone)
        }

        val bounds = compose.onNodeWithTag("tsfmModelDock").getUnclippedBoundsInRoot()
        val dockHeight = bounds.bottom - bounds.top

        assertTrue("dock height was $dockHeight, expected <= 107.dp", dockHeight <= 107.dp)
    }

    @Test
    fun `weights card grows to four columns and keeps the total at one`() {
        compose.setContent {
            MlEnsembleWeightsCard(
                modelState = ModelState(),
                totalSettled = 14,
                coneHitRate = 72.5,
                mountedModelIds = listOf("chronos")
            )
        }

        compose.onNodeWithText("W1").assertExists()
        compose.onNodeWithText("W4").assertExists()
        compose.onNodeWithText("MODELS 4 · ΣW 100.0%").assertExists()
        compose.onNodeWithText("Chronos-Bolt TSFM").assertExists()
    }

    @Test
    fun `weights card stays tri-factor without mounted models`() {
        compose.setContent {
            MlEnsembleWeightsCard(
                modelState = ModelState(),
                totalSettled = 3,
                coneHitRate = null,
                mountedModelIds = emptyList()
            )
        }

        compose.onNodeWithText("W3").assertExists()
        compose.onNodeWithText("MODELS 3 · ΣW 100.0%").assertExists()
        compose.onNodeWithText("Chronos-Bolt TSFM").assertDoesNotExist()
    }
}
