package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.aistudio.alphapulse.domain.decision.DecisionModelCatalog
import com.aistudio.alphapulse.domain.decision.Needle3EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the Needle 3 status banner, specified as Given/When/Then.
 *
 * §30/§47 require the on-device model's state to be visible. Before this banner the
 * engine reported its state only in code, so a user could download 34 MB of weights
 * that never ran and see nothing explaining why. These tests pin both halves: the
 * reason appears whenever the engine cannot run, and disappears once it can.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §47 Model Download Architecture,
 * §59 AI Narrator Requirements
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Needle3StatusBannerBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private fun ready() = Needle3EngineStatus.Ready("cactus-needle3/arm64-v8a")

    private fun notBundled() = Needle3EngineStatus.NotBundled(
        reason = "This build has no Cactus engine for the device ABI. Cactus publishes " +
            "engines for arm64-v8a and armeabi-v7a only.",
        remedy = "Install the arm64-v8a APK on a physical device."
    )

    private fun weightsNotMounted() = Needle3EngineStatus.WeightsNotMounted(
        reason = "the needle3.cact checkpoint has not been downloaded and verified yet.",
        remedy = "Download Cactus Needle 3 from the model dock on a mid-tier device."
    )

    private fun loadFailed() = Needle3EngineStatus.LoadFailed(
        reason = "Cactus engine rejected the checkpoint: bad magic",
        remedy = "Delete the downloaded Needle 3 weights and download them again."
    )

    @Test
    fun `given the engine is bundled but no checkpoint is mounted when the banner renders then the download remedy is visible`() {
        compose.setContent { Needle3StatusBanner(status = weightsNotMounted()) }

        compose.onNodeWithTag(NEEDLE3_STATUS_BANNER_TAG).assertExists()
        // The label makes clear these weights execute on the device, not over a network.
        compose.onAllNodesWithText("NEEDLE 3 · ON-DEVICE", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("needle3.cact", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("model dock", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given an emulator whose abi has no engine when the banner renders then the supported abis are named`() {
        compose.setContent { Needle3StatusBanner(status = notBundled()) }

        compose.onAllNodesWithText("arm64-v8a and armeabi-v7a", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("arm64-v8a APK", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given the engine rejected the checkpoint when the banner renders then the failure is visible rather than silent`() {
        compose.setContent { Needle3StatusBanner(status = loadFailed()) }

        compose.onAllNodesWithText("bad magic", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("download them again", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given any non-ready engine when the banner renders then it states that the deterministic narrator still works`() {
        compose.setContent { Needle3StatusBanner(status = notBundled()) }

        compose.onAllNodesWithText("deterministic narrator", substring = true).assertCountEquals(1)
    }

    @Test
    fun `given every non-ready engine state when its text is built then each one promises the deterministic narrator`() {
        // Every refusal must leave the user with a working brief, from the rules.
        val refusals = listOf(
            notBundled(),
            weightsNotMounted(),
            loadFailed(),
            Needle3EngineStatus.InferenceFailed(
                reason = "out of memory",
                remedy = "Retry the turn."
            )
        )

        refusals.forEach { status ->
            assertTrue(
                "${status::class.simpleName} must not leave the user without a brief",
                Needle3BannerText.remedyOf(status).contains("deterministic narrator")
            )
            assertTrue(
                "${status::class.simpleName} must explain itself",
                Needle3BannerText.reasonOf(status).isNotBlank()
            )
        }

        // A ready engine has nothing to explain, so it renders no words at all.
        assertEquals("", Needle3BannerText.reasonOf(ready()))
        assertEquals("", Needle3BannerText.remedyOf(ready()))
    }

    @Test
    fun `given the engine is ready when the banner renders then nothing is shown`() {
        compose.setContent { Needle3StatusBanner(status = ready()) }

        compose.onAllNodesWithTag(NEEDLE3_STATUS_BANNER_TAG).assertCountEquals(0)
    }

    @Test
    fun `given the banner exists when the catalog is read then the weights still download and the engine still does not`() {
        // Surfacing the engine's state must never change what is downloadable:
        // the checkpoint is fetched, the engine is compiled in.
        assertTrue(
            "The 34 MB checkpoint is still a downloadable model",
            DecisionModelCatalog.isDownloadable("needle3")
        )
        DecisionModelCatalog.NEEDLE3_ENGINE.forEach { enginePart ->
            assertFalse(
                "${enginePart.id} is linked into the APK and must not appear as a download",
                DecisionModelCatalog.isDownloadable(enginePart.id)
            )
        }
        // Decision-1 is gone from the catalog entirely: routing is on device now.
        assertNull(DecisionModelCatalog.byId("ms-decision-1"))
    }
}
