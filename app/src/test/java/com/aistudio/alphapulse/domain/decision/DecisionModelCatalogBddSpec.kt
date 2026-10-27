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
 * These assertions pin the facts we verified against the live publishers before
 * writing them down: the Needle 3 coordinates are real and downloadable, the
 * Microsoft-Decision-1 route is API-only (no weights exist to download), and the
 * "local alternative" stays unplugged because the documented ONNX URL does not
 * resolve. A regression here means the app would ship a broken download link.
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
    fun `given needle 3 when the downloadability rule is applied then it is downloadable but microsoft decision 1 is not`() {
        assertTrue(
            "Open weights on a public host must be downloadable",
            DecisionModelCatalog.isDownloadable("needle3")
        )
        assertFalse(
            "Closed weights must never be presented as a download",
            DecisionModelCatalog.isDownloadable("ms-decision-1")
        )
        assertFalse(
            "An unverified alternative must never be presented as a download",
            DecisionModelCatalog.isDownloadable("decision-1-local")
        )
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

    // ------------------------------------------------------ Microsoft-Decision-1

    @Test
    fun `given microsoft decision 1 when inspected then it is described as an api route with no weights in the apk`() {
        val spec = DecisionModelCatalog.DECISION1_API

        assertEquals(ModelHosting.API_ONLY, spec.hosting)
        assertEquals(ModelAvailability.AVAILABLE, spec.availability)
        assertNull("No file may be advertised for a closed-weight model", spec.downloadUrl)
        assertEquals(0L, spec.sizeBytes)
        assertNull("No digest can exist for weights we never receive", spec.sha256)
        assertTrue(
            "Strategy must name both documented hosts",
            spec.integrationStrategy.contains("Azure") && spec.integrationStrategy.contains("OpenRouter")
        )
        assertTrue(
            "Strategy must state that the key lives in the Keystore (§42/§43)",
            spec.integrationStrategy.contains("Keystore")
        )
    }

    @Test
    fun `given the documented local alternative when inspected then it is parked as planned because the onnx coordinate does not exist`() {
        val spec = DecisionModelCatalog.DECISION1_LOCAL_ALTERNATIVE

        assertEquals(
            "Offering a URL we verified as HTTP 404 would ship a guaranteed failure",
            ModelAvailability.PLANNED,
            spec.availability
        )
        assertNull(spec.downloadUrl)
        assertTrue(
            "The reason must record what we found",
            spec.status.contains("404")
        )
    }

    // ------------------------------------------------------------ catalog lookups

    @Test
    fun `given the catalog when searched by id then every entry resolves case-insensitively and unknown ids return null`() {
        assertNotNull(DecisionModelCatalog.byId("NEEDLE3"))
        assertNotNull(DecisionModelCatalog.byId("ms-decision-1"))
        assertNotNull(DecisionModelCatalog.byId("decision-1-local"))
        assertNull(DecisionModelCatalog.byId("does-not-exist"))
        assertTrue(
            "Catalog must contain the three headline entries plus two engine parts",
            DecisionModelCatalog.ALL.size >= 3
        )
    }
}
