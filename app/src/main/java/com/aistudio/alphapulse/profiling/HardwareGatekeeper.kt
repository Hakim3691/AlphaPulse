package com.aistudio.alphapulse.profiling

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * HardwareGatekeeper - decides which on-device models a phone is allowed to download.
 *
 * Think of it like a bouncer at a door: small phones (low memory, few CPU cores)
 * are not allowed to pull big models, because the download would succeed but the
 * app would then be too slow or would crash.
 *
 * The rule we follow comes from the product notes:
 *   "The download button remains blocked on devices below the mid-tier threshold
 *    (< 3.5 GB RAM or < 4 CPU cores)."
 *
 * Mid-tier models are Chronos, TimesFM and Cactus Needle 3 - if a device does not
 * have at least 3.5 GB of RAM AND at least 4 CPU cores, none of them may download.
 *
 * Spec Ref: §47 (Model Download Architecture), §26 (hardware backends)
 */

/**
 * DeviceProfile - a simple description of the phone we are running on.
 *
 * We only ask for the two numbers the gate needs, so tests can build any phone
 * they want without needing a real device.
 *
 * @property totalRamGb total device memory in gigabytes (e.g. 3.5)
 * @property cpuCores how many CPU cores the device has (e.g. 4)
 */
data class DeviceProfile(
    val totalRamGb: Double,
    val cpuCores: Int
)

/**
 * DeviceTier - how capable the phone is.
 *
 * LOW  -> below the mid-tier line: no big model downloads.
 * MID  -> meets the mid-tier line: Chronos, TimesFM and Needle 3 allowed.
 * HIGH -> comfortably above the mid-tier line (kept for future larger models).
 */
enum class DeviceTier { LOW, MID, HIGH }

/**
 * GateDecision - the answer the gate gives, plus a human-readable reason.
 *
 * @property allowed true when the download button should be clickable
 * @property reason short sentence explaining why (shown to the user when blocked)
 */
data class GateDecision(
    val allowed: Boolean,
    val reason: String
)

object HardwareGatekeeper {

    /** Minimum RAM (in GB) a device needs to be considered mid-tier. */
    const val MID_TIER_MIN_RAM_GB: Double = 3.5

    /** Minimum CPU cores a device needs to be considered mid-tier. */
    const val MID_TIER_MIN_CORES: Int = 4

    /** RAM (in GB) above which we already call the device high-tier. */
    const val HIGH_TIER_MIN_RAM_GB: Double = 8.0

    /**
     * Models that require at least a mid-tier device.
     *
     * Chronos and TimesFM are the TSFM dock models; Cactus Needle 3 is the
     * on-device orchestration / edge model (§30). Needle 3 is listed here
     * alongside the other two, exactly as the notes require.
     */
    val MID_TIER_MODELS: Set<String> = setOf(
        "chronos",   // Chronos-Bolt Tiny (TSFM dock)
        "timesfm",   // TimesFM 2.5 200M (TSFM dock)
        "needle3"    // Cactus Needle 3 (on-device edge / orchestration)
    )

    /**
     * How capable is this phone?
     *
     * Both conditions must hold for MID (RAM **and** cores), which is what makes
     * a device below either threshold fall back to LOW.
     */
    fun tierFor(profile: DeviceProfile): DeviceTier = when {
        profile.totalRamGb >= HIGH_TIER_MIN_RAM_GB &&
            profile.cpuCores >= MID_TIER_MIN_CORES -> DeviceTier.HIGH
        profile.totalRamGb >= MID_TIER_MIN_RAM_GB &&
            profile.cpuCores >= MID_TIER_MIN_CORES -> DeviceTier.MID
        else -> DeviceTier.LOW
    }

    /** True when the device reaches the mid-tier line (RAM and cores both OK). */
    fun isMidTierOrAbove(profile: DeviceProfile): Boolean =
        tierFor(profile) != DeviceTier.LOW

    /**
     * May this model be downloaded on this device?
     *
     * Models that are not in [MID_TIER_MODELS] are always allowed - the gate only
     * exists to protect the phone from the heavy downloads.
     */
    fun canDownload(modelId: String, profile: DeviceProfile): Boolean =
        decide(modelId, profile).allowed

    /**
     * The full answer, including the reason the user should see if blocked.
     *
     * @param modelId model being downloaded (e.g. "needle3")
     * @param profile the phone's hardware profile
     */
    fun decide(modelId: String, profile: DeviceProfile): GateDecision {
        // Lightweight models are never blocked by this gate.
        if (modelId.lowercase() !in MID_TIER_MODELS) {
            return GateDecision(true, "Model is not gated by the hardware gatekeeper.")
        }

        val tier = tierFor(profile)
        if (tier == DeviceTier.LOW) {
            // Build a friendly sentence that says exactly which limit failed.
            val ramOk = profile.totalRamGb >= MID_TIER_MIN_RAM_GB
            val coreOk = profile.cpuCores >= MID_TIER_MIN_CORES
            val detail = when {
                !ramOk && !coreOk ->
                    "needs at least ${MID_TIER_MIN_RAM_GB} GB RAM and $MID_TIER_MIN_CORES CPU cores " +
                        "(this device has ${profile.totalRamGb} GB and ${profile.cpuCores} cores)"
                !ramOk ->
                    "needs at least ${MID_TIER_MIN_RAM_GB} GB RAM (this device has ${profile.totalRamGb} GB)"
                else ->
                    "needs at least $MID_TIER_MIN_CORES CPU cores (this device has ${profile.cpuCores} cores)"
            }
            return GateDecision(
                allowed = false,
                reason = "Download blocked: $modelId $detail."
            )
        }

        return GateDecision(true, "Device meets the mid-tier hardware requirement.")
    }

    /**
     * Read the real hardware of the phone this app is running on.
     *
     * Returns a conservative LOW profile when the platform will not tell us the
     * numbers (unknown hardware means "do not download big models").
     */
    fun fromDevice(context: Context): DeviceProfile {
        val cores = Runtime.getRuntime().availableProcessors()
        val ramGb = readTotalRamGb(context) ?: return DeviceProfile(0.0, cores)
        return DeviceProfile(totalRamGb = ramGb, cpuCores = cores)
    }

    /** Total RAM in GB, or null when ActivityManager has no usable info. */
    private fun readTotalRamGb(context: Context): Double? {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val info = ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                // MemoryInfo.totalMem is bytes; convert to GB (1024^3).
                info.totalMem / (1024.0 * 1024.0 * 1024.0)
            } else {
                null
            }
        } catch (e: Exception) {
            // Any failure means "we do not know", which the caller treats as LOW.
            null
        }
    }
}
