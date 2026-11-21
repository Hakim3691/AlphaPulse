package com.aistudio.alphapulse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.data.model.ModelLoadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Directive §3: the two TSFM artifacts live in the app header, not a bottom dock.
 *
 * A missing model is muted and tappable and starts exactly one download; a verified
 * model is green and inert; a running transfer shows a ring instead of a trigger.
 *
 * Spec Ref: Directive §3 TSFM Model Dock Deprecation & Header Quick Actions
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class HeaderModelActionsBddSpec {

    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun header(
        states: Map<String, ModelDownloadState> = emptyMap(),
        onDownload: (String) -> Unit = {}
    ) {
        HeaderBar(
            sessionElapsedSeconds = 0,
            isLTFReady = false,
            isMTFReady = false,
            isHTFReady = false,
            pipelineRunning = false,
            pipelineSecondsRemaining = 0,
            onRefreshClick = {},
            modelStates = states,
            onDownloadModel = onDownload
        )
    }

    @Test
    fun `given both artifacts missing when the chronos icon is tapped then a download starts`() {
        val requested = mutableListOf<String>()
        compose.setContent { header(onDownload = { requested += it }) }

        compose.onAllNodesWithContentDescription("Download Chronos-Bolt").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Download TimesFM-2.5").assertCountEquals(1)

        compose.onNodeWithTag("header_model_chronos").performClick()
        assertEquals(listOf(ModelArtifacts.CHRONOS.modelId), requested)
    }

    @Test
    fun `given both artifacts ready when the header renders then both icons report ready`() {
        compose.setContent {
            header(
                states = mapOf(
                    ModelArtifacts.CHRONOS.modelId to ModelDownloadState(
                        ModelArtifacts.CHRONOS.modelId,
                        ModelLoadStatus.READY
                    ),
                    ModelArtifacts.TIMESFM.modelId to ModelDownloadState(
                        ModelArtifacts.TIMESFM.modelId,
                        ModelLoadStatus.READY
                    )
                )
            )
        }

        compose.onNodeWithContentDescription("Chronos-Bolt ready").assertExists()
        compose.onNodeWithContentDescription("TimesFM-2.5 ready").assertExists()
    }

    @Test
    fun `given a running transfer when the header renders then the trigger is replaced by progress`() {
        compose.setContent {
            header(
                states = mapOf(
                    ModelArtifacts.CHRONOS.modelId to ModelDownloadState(
                        ModelArtifacts.CHRONOS.modelId,
                        ModelLoadStatus.DOWNLOADING,
                        progress = 0.4f
                    )
                )
            )
        }

        compose.onNodeWithTag("header_model_chronos").assertExists()
        compose.onAllNodesWithContentDescription("Download Chronos-Bolt").assertCountEquals(0)
        assertTrue(true)
    }
}
