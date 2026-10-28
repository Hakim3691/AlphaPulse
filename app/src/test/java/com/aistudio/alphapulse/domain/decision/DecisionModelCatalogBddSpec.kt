package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the decision-model catalog, specified as Given/When/Then.
 *
 * The catalog now carries exactly one route: Cactus Needle 3, downloaded as a file
 * and executed by the engine linked into the APK. Microsoft-Decision-1 used to be a
 * second, API-only route and is deliberately absent - §29's routing moved on device,
 * so there is no closed-weight endpoint left to catalogue, and the unverified local
 * alternative went with it.
 *
 * Spec Ref: §29 Quantitative Decision Router, §30 Needle / Agent Orchestration,
 * §47 Model Download Architecture.
 */
class DecisionModelCatalogBddSpec {

    // ----------------------------------------------------------------- Needle 3

    @Test
    fun `given the needle 3 entry when inspected then it carries the verified hugging face coordinate and digest`() {
        val spec = DecisionModelCatalog.NEEDLE3

        assertEquals("needle3", spec.id)
        assertEquals(ModelHosting.ON_DEVICE, spec.hosting)
        assertEquals(ModelAvailability.AVAILABLE, spec.availability)
        assertEquals(
            "Weights must come from the official Cactus-Compute repository",
            "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact",
            spec.downloadUrl
        )
        assertEquals(
            "Published file size is 35,335,380 bytes (measured before pinning)",
            35_335_380L,
            spec.sizeBytes
        )
        assertEquals(
            "Digest must be the sha-256 of the downloaded bytes",
            "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38",
            spec.sha256
        )
        assertEquals("Needle 3 requires a mid-tier device", "mid-tier", spec.hardwareTierRequired)
    }

    @Test
    fun `given needle 3 when the downloadability rule is applied then only on-device artifacts are downloadable`() {
        assertTrue(
            "Open weights on a public host must be downloadable",
            DecisionModelCatalog.isDownloadable("needle3")
        )
        DecisionModelCatalog.NEEDLE3_ENGINE.forEach { part ->
            assertFalse(
                "${part.id} is linked into the APK, not fetched",
                DecisionModelCatalog.isDownloadable(part.id)
            )
        }
        assertFalse("Unknown ids are not downloadable", DecisionModelCatalog.isDownloadable("nope"))
    }

    @Test
    fun `given the native engine files when inspected then each abi is pinned with its real size and digest and is compiled in`() {
        val arm64 = DecisionModelCatalog.NEEDLE3_ENGINE.first { it.id == "needle3-engine-lib" }
        val armv7 = DecisionModelCatalog.NEEDLE3_ENGINE.first { it.id == "needle3-engine-lib-armv7" }
        val header = DecisionModelCatalog.NEEDLE3_ENGINE.first { it.id == "needle3-engine-header" }

        assertEquals(2_177_676L, arm64.sizeBytes)
        assertEquals(1_700_760L, armv7.sizeBytes)
        assertEquals(4_528L, header.sizeBytes)
        assertTrue(arm64.downloadUrl!!.endsWith("/android-arm64/libneedle.a"))
        assertTrue(armv7.downloadUrl!!.endsWith("/android-armv7/libneedle.a"))
        assertTrue(header.downloadUrl!!.endsWith("/android-arm64/needle.h"))

        // The engine is linked for both arm ABIs, so it is AVAILABLE...
        DecisionModelCatalog.NEEDLE3_ENGINE.forEach { part ->
            assertEquals(
                "${part.id} is vendored and linked, so it must be AVAILABLE",
                ModelAvailability.AVAILABLE,
                part.availability
            )
            assertTrue(
                "${part.id} must be marked as compiled into the APK",
                part.bundledAtBuildTime
            )
        }

        // ...but it is compiled in, never fetched at runtime, so the downloader
        // must not offer it as a file to download.
        DecisionModelCatalog.NEEDLE3_ENGINE.forEach { part ->
            assertFalse(
                "${part.id} is linked by CMake, not downloaded",
                DecisionModelCatalog.isDownloadable(part.id)
            )
        }
    }

    // ------------------------------------------------- Decision-1 removal (§29)

    @Test
    fun `given the catalog when it is read then microsoft decision 1 is gone rather than merely hidden`() {
        // Routing runs on device now; an API-only route must not linger as a
        // catalogue row, a download tile or a configuration gap.
        assertNull(
            "Decision-1 must not survive the move to on-device routing",
            DecisionModelCatalog.byId("ms-decision-1")
        )
        assertNull(
            "The unverified local alternative went with it",
            DecisionModelCatalog.byId("decision-1-local")
        )
        assertEquals(
            "The catalog is Needle 3 plus its three vendored engine parts",
            4,
            DecisionModelCatalog.ALL.size
        )
        assertFalse(
            "No route is downloadable under a Decision-1 id any more",
            DecisionModelCatalog.isDownloadable("ms-decision-1")
        )
    }

    // ------------------------------------------------------------ catalog lookups

    @Test
    fun `given the catalog when searched by id then every entry resolves case-insensitively and unknown ids return null`() {
        assertNotNull(DecisionModelCatalog.byId("NEEDLE3"))
        assertNotNull(DecisionModelCatalog.byId("needle3-engine-lib"))
        assertNotNull(DecisionModelCatalog.byId("NEEDLE3-ENGINE-HEADER"))
        assertNull(DecisionModelCatalog.byId("does-not-exist"))
        assertTrue(
            "Catalog must contain the checkpoint plus its three engine parts",
            DecisionModelCatalog.ALL.size == DecisionModelCatalog.NEEDLE3_ENGINE.size + 1
        )
    }
}
