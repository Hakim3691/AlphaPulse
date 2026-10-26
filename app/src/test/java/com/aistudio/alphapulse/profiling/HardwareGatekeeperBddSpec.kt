package com.aistudio.alphapulse.profiling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the hardware download gate, specified as Given/When/Then.
 *
 * The product rule under test is the one from the release notes:
 * "Update HardwareGatekeeper.kt to include Cactus Needle 3 alongside the mid-tier
 *  models (Chronos & TimesFM). The download button remains blocked on devices
 *  below the mid-tier threshold (< 3.5 GB RAM or < 4 CPU cores)."
 *
 * Spec Ref: §47 Model Download Architecture, §26 hardware backends.
 */
class HardwareGatekeeperBddSpec {

    /** A phone that meets both mid-tier limits exactly (3.5 GB, 4 cores). */
    private val exactlyMidTier = DeviceProfile(totalRamGb = 3.5, cpuCores = 4)

    /** A phone under the RAM line but with plenty of cores. */
    private val lowRamPhone = DeviceProfile(totalRamGb = 3.0, cpuCores = 8)

    /** A phone with plenty of RAM but too few cores. */
    private val fewCoresPhone = DeviceProfile(totalRamGb = 6.0, cpuCores = 2)

    /** A comfortable high-tier phone. */
    private val highTierPhone = DeviceProfile(totalRamGb = 12.0, cpuCores = 8)

    // ---------------------------------------------------------------- Needle 3

    @Test
    fun `given needle 3 in the catalog when the gate is asked which models are mid-tier then needle 3 is listed next to chronos and timesfm`() {
        assertTrue("Chronos must stay a mid-tier model", "chronos" in HardwareGatekeeper.MID_TIER_MODELS)
        assertTrue("TimesFM must stay a mid-tier model", "timesfm" in HardwareGatekeeper.MID_TIER_MODELS)
        assertTrue(
            "Needle 3 must be gated together with the other mid-tier models",
            "needle3" in HardwareGatekeeper.MID_TIER_MODELS
        )
        assertEquals("Exactly three mid-tier models are defined", 3, HardwareGatekeeper.MID_TIER_MODELS.size)
    }

    @Test
    fun `given a phone below the ram line when the user taps download for needle 3 then the download is blocked with a reason naming the ram limit`() {
        val decision = HardwareGatekeeper.decide("needle3", lowRamPhone)

        assertFalse("Needle 3 must not download on a 3.0 GB device", decision.allowed)
        assertTrue("Reason must mention RAM", decision.reason.contains("RAM"))
        assertTrue(
            "Reason must show the configured threshold",
            decision.reason.contains(HardwareGatekeeper.MID_TIER_MIN_RAM_GB.toString())
        )
    }

    @Test
    fun `given a phone with too few cores when the user taps download for needle 3 then the download is blocked with a reason naming the core limit`() {
        val decision = HardwareGatekeeper.decide("needle3", fewCoresPhone)

        assertFalse("Needle 3 must not download on a 2-core device", decision.allowed)
        assertTrue("Reason must mention CPU cores", decision.reason.contains("CPU cores"))
        assertTrue(
            "Reason must show the core threshold",
            decision.reason.contains(HardwareGatekeeper.MID_TIER_MIN_CORES.toString())
        )
    }

    @Test
    fun `given a phone that exactly meets the mid-tier line when the user taps download for needle 3 then the download is allowed`() {
        val decision = HardwareGatekeeper.decide("needle3", exactlyMidTier)

        assertTrue(
            "3.5 GB + 4 cores is the documented mid-tier line, so it must pass " +
                "(${decision.reason})",
            decision.allowed
        )
        assertTrue(HardwareGatekeeper.isMidTierOrAbove(exactlyMidTier))
        assertEquals(DeviceTier.MID, HardwareGatekeeper.tierFor(exactlyMidTier))
    }

    // -------------------------------------------------- the two TSFM dock models

    @Test
    fun `given a below-mid-tier phone when the user tries chronos and timesfm then both are blocked`() {
        assertFalse(HardwareGatekeeper.canDownload("chronos", lowRamPhone))
        assertFalse(HardwareGatekeeper.canDownload("timesfm", lowRamPhone))
        assertFalse(HardwareGatekeeper.canDownload("chronos", fewCoresPhone))
        assertFalse(HardwareGatekeeper.canDownload("timesfm", fewCoresPhone))
    }

    @Test
    fun `given a mid-tier or better phone when the user tries every gated model then all are allowed`() {
        listOf(exactlyMidTier, highTierPhone).forEach { profile ->
            HardwareGatekeeper.MID_TIER_MODELS.forEach { modelId ->
                assertTrue(
                    "$modelId must download on ${profile.totalRamGb} GB / ${profile.cpuCores} cores",
                    HardwareGatekeeper.canDownload(modelId, profile)
                )
            }
        }
    }

    // ------------------------------------------------------------- ungated models

    @Test
    fun `given a model outside the mid-tier list when a low-tier phone requests it then the gate lets it through`() {
        val decision = HardwareGatekeeper.decide("some-small-model", lowRamPhone)

        assertTrue("Only the listed mid-tier models are gated", decision.allowed)
    }

    // ------------------------------------------------------------------ tier math

    @Test
    fun `given the tier thresholds when a device misses either limit then its tier drops to LOW`() {
        assertEquals(DeviceTier.LOW, HardwareGatekeeper.tierFor(lowRamPhone))
        assertEquals(DeviceTier.LOW, HardwareGatekeeper.tierFor(fewCoresPhone))
        assertEquals(DeviceTier.LOW, HardwareGatekeeper.tierFor(DeviceProfile(0.0, 0)))
        assertEquals(DeviceTier.HIGH, HardwareGatekeeper.tierFor(highTierPhone))
    }

    @Test
    fun `given unknown hardware when the profile is read conservatively then the device is treated as LOW tier`() {
        // fromDevice() falls back to a zero-RAM profile when the platform will not
        // report memory - unknown hardware must never open the gate.
        val unknown = DeviceProfile(totalRamGb = 0.0, cpuCores = 4)

        assertEquals(DeviceTier.LOW, HardwareGatekeeper.tierFor(unknown))
        assertFalse(HardwareGatekeeper.isMidTierOrAbove(unknown))
        assertFalse(HardwareGatekeeper.canDownload("needle3", unknown))
    }
}
