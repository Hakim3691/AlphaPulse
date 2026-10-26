package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.domain.decision.DecisionModelCatalog
import com.aistudio.alphapulse.domain.decision.ModelHosting
import com.aistudio.alphapulse.profiling.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the model dock with respect to API-only models.
 *
 * Microsoft-Decision-1 has no weights, so it must never appear as something a user
 * can download: offering a download for a model that does not ship would be a
 * broken button followed by a failed request. The dock therefore lists only the
 * three on-device artifacts, and Decision-1 is reachable only through the router.
 *
 * Spec Ref: §29 Quantitative Decision Router, §47 Model Download Architecture
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ModelDockRemoteOnlyBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private val states = mapOf(
        "chronos" to ModelDownloadState("chronos"),
        "timesfm" to ModelDownloadState("timesfm"),
        "needle3" to ModelDownloadState("needle3")
    )

    private val capablePhone = DeviceProfile(totalRamGb = 8.0, cpuCores = 8)

    @Test
    fun `given the model dock when its downloadable ids are read then microsoft decision 1 is absent`() {
        assertFalse(
            "Decision-1 is closed-weights; a download tile for it would be a dead button",
            "ms-decision-1" in MODEL_DOCK_IDS
        )
        assertEquals("The dock offers exactly the three on-device artifacts", 3, MODEL_DOCK_IDS.size)
        assertTrue("Chronos stays downloadable", "chronos" in MODEL_DOCK_IDS)
        assertTrue("TimesFM stays downloadable", "timesfm" in MODEL_DOCK_IDS)
        assertTrue("Needle 3 is downloadable on a capable device", "needle3" in MODEL_DOCK_IDS)
    }

    @Test
    fun `given the rendered dock when the user looks at it then no decision 1 row or download button exists`() {
        compose.setContent {
            ModelDownloadSection(states = states, onDownload = {}, deviceProfile = capablePhone)
        }

        compose.onAllNodesWithText("Microsoft-Decision-1").assertCountEquals(0)
        compose.onAllNodesWithText("Microsoft Decision 1").assertCountEquals(0)
        // Only the three on-device models can be downloaded.
        compose.onAllNodesWithContentDescription("Download model").assertCountEquals(3)
    }

    @Test
    fun `given the catalog when the api-only model is inspected then it is hosted remotely with no file to fetch`() {
        val spec = DecisionModelCatalog.DECISION1_API

        assertEquals(ModelHosting.API_ONLY, spec.hosting)
        assertEquals("An API model has no download URL", null, spec.downloadUrl)
        assertEquals("An API model ships no bytes", 0L, spec.sizeBytes)
        assertFalse(
            "isDownloadable must be false for an API-only model",
            DecisionModelCatalog.isDownloadable(spec.id)
        )
    }
}
