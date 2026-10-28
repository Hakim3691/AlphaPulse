package com.aistudio.alphapulse.domain.decision

/**
 * DecisionModelCatalog - the single place that describes the decision models we
 * can use, and how each one is obtained.
 *
 * The spec is deliberately loose about Microsoft-Decision-1:
 *   "The document's proposed Microsoft-Decision-1 layer shall be treated as a
 *    replaceable decision-router interface rather than a hard dependency."  (§29)
 *
 * That is why this catalog describes exactly one route:
 *
 *  NEEDLE3 - Cactus Needle 3 is openly downloadable and runs on device through the
 *            Cactus C/C++ engine (§30 orchestration).
 *
 * Microsoft-Decision-1 is no longer catalogued. It was the API-only third route,
 * and the same six-state decision job now runs locally through Needle 3, so there
 * is no endpoint, API key or download left to describe. [Needle3DecisionRouter]
 * is what replaced it.
 *
 * Every download coordinate below was checked against the live publisher before
 * being pinned; nothing here is a guess.
 */

/** Where a model's weights come from. */
enum class ModelHosting {
    /** Called over the network; weights are the provider's property. */
    API_ONLY,
    /** Weights are public and downloaded onto the device. */
    ON_DEVICE
}

/** Whether a model can actually run right now in this build. */
enum class ModelAvailability {
    /** Downloadable / callable today. */
    AVAILABLE,
    /** Kept in the catalog for a future release (engine or artifact missing). */
    PLANNED
}

/**
 * DecisionModelSpec - one row of the catalog.
 *
 * @property id stable identifier used by the gatekeeper and the UI
 * @property displayName name shown to the user
 * @property hosting where the weights live
 * @property availability whether we can use it today
 * @property downloadUrl exact file URL, or null for API-only models
 * @property sizeBytes file size in bytes, or 0 for API-only models
 * @property sha256 pinned digest of the file, or null when nothing is downloadable
 * @property license model license as published by the vendor
 * @property integrationStrategy short explanation of how AlphaPulse uses it
 * @property hardwareTierRequired "mid-tier" if it needs the HardwareGatekeeper line
 * @property status human-readable reason when availability is PLANNED
 * @property bundledAtBuildTime true for artifacts compiled into the APK (engine parts),
 *           which are therefore never fetched by the downloader at runtime
 */
data class DecisionModelSpec(
    val id: String,
    val displayName: String,
    val hosting: ModelHosting,
    val availability: ModelAvailability,
    val downloadUrl: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String,
    val integrationStrategy: String,
    val hardwareTierRequired: String?,
    val status: String,
    val bundledAtBuildTime: Boolean = false
)

object DecisionModelCatalog {

    /** Base host for public Hugging Face artifacts. */
    private const val HF = "https://huggingface.co"

    /**
     * Cactus Needle 3 - open weights, on device.
     *
     * Verified live before pinning:
     *   - needle3.cact        35,335,380 bytes, sha-256 c9d915ec...70c38
     *   - android-arm64/libneedle.a  2,177,676 bytes (static engine)
     *   - android-arm64/needle.h           4,528 bytes (engine header)
     *
     * The weights are downloaded with the normal model pipeline (size + sha-256
     * verified before the file is accepted). Running them additionally needs the
     * Cactus static engine linked into the native layer, which is tracked as
     * PLANNED_ENGINE - see [engineStatus].
     */
    val NEEDLE3 = DecisionModelSpec(
        id = "needle3",
        displayName = "Cactus Needle 3",
        hosting = ModelHosting.ON_DEVICE,
        availability = ModelAvailability.AVAILABLE,
        downloadUrl = "$HF/Cactus-Compute/needle3/resolve/main/needle3.cact",
        sizeBytes = 35_335_380L,
        sha256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38",
        license = "Cactus Compute community license (open weights)",
        integrationStrategy = "Download .cact weights from Hugging Face, verify size + sha-256, " +
            "then execute them through the Cactus C/C++ static engine (android-arm64/libneedle.a " +
            "+ needle.h) linked into the app's NDK layer and called over JNI.",
        hardwareTierRequired = "mid-tier",
        status = "Weights downloadable and verifiable; engine linked for arm64-v8a and " +
            "armeabi-v7a through the vendored static library ([NEEDLE3_ENGINE])."
    )

    /**
     * The native engine files Needle 3 needs, pinned with their real sizes and digests.
     *
     * These are **compiled into the APK**, not downloaded: CMake links the archive for
     * the ABI being built (see `app/src/main/cpp/CMakeLists.txt`), so
     * [bundledAtBuildTime] is true and the downloader never offers them. Every digest
     * below is the published LFS object id, re-checked in
     * `app/src/test/java/com/aistudio/alphapulse/domain/decision/VendoredEngineArtifactBddSpec.kt`
     * and by `scripts/check_integrity.py` on every commit.
     */
    val NEEDLE3_ENGINE: List<DecisionModelSpec> = listOf(
        DecisionModelSpec(
            id = "needle3-engine-lib",
            displayName = "Needle 3 native engine (arm64-v8a)",
            hosting = ModelHosting.ON_DEVICE,
            availability = ModelAvailability.AVAILABLE,
            downloadUrl = "$HF/Cactus-Compute/needle3/resolve/main/android-arm64/libneedle.a",
            sizeBytes = 2_177_676L,
            sha256 = "7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0",
            license = "Cactus Compute community license",
            integrationStrategy = "Linked into libalphapulse_needle.so by CMake and called over JNI.",
            hardwareTierRequired = "mid-tier",
            status = "Vendored at app/src/main/cpp/engine/android-arm64/libneedle.a and linked.",
            bundledAtBuildTime = true
        ),
        DecisionModelSpec(
            id = "needle3-engine-lib-armv7",
            displayName = "Needle 3 native engine (armeabi-v7a)",
            hosting = ModelHosting.ON_DEVICE,
            availability = ModelAvailability.AVAILABLE,
            downloadUrl = "$HF/Cactus-Compute/needle3/resolve/main/android-armv7/libneedle.a",
            sizeBytes = 1_700_760L,
            sha256 = "9f559a287b201c213e5c753d3cd1076a1849c73f94b445c85d11e4969422a135",
            license = "Cactus Compute community license",
            integrationStrategy = "Linked into libalphapulse_needle.so by CMake and called over JNI.",
            hardwareTierRequired = "mid-tier",
            status = "Vendored at app/src/main/cpp/engine/android-armv7/libneedle.a and linked.",
            bundledAtBuildTime = true
        ),
        DecisionModelSpec(
            id = "needle3-engine-header",
            displayName = "Needle 3 engine header (needle.h)",
            hosting = ModelHosting.ON_DEVICE,
            availability = ModelAvailability.AVAILABLE,
            downloadUrl = "$HF/Cactus-Compute/needle3/resolve/main/android-arm64/needle.h",
            sizeBytes = 4_528L,
            sha256 = "a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99",
            license = "Cactus Compute community license",
            integrationStrategy = "Compile-time header for the native bridge; identical for both ABIs.",
            hardwareTierRequired = null,
            status = "Vendored at app/src/main/cpp/engine/needle.h and compiled against.",
            bundledAtBuildTime = true
        )
    )



    /** Everything the catalog knows about, including engine parts. */
    val ALL: List<DecisionModelSpec> = listOf(NEEDLE3) + NEEDLE3_ENGINE

    /** Look a spec up by id (case-insensitive), or null when unknown. */
    fun byId(id: String): DecisionModelSpec? =
        ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /**
     * True when this model can be downloaded onto the device at runtime.
     *
     * Artifacts that are compiled into the APK are excluded: the Cactus engine is
     * linked by CMake, so offering it in a download list would describe a fetch that
     * never happens.
     */
    fun isDownloadable(id: String): Boolean {
        val spec = byId(id) ?: return false
        if (spec.bundledAtBuildTime) return false
        return spec.hosting == ModelHosting.ON_DEVICE &&
            spec.availability == ModelAvailability.AVAILABLE &&
            spec.downloadUrl != null
    }
}
