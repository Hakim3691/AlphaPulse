package com.aistudio.alphapulse.domain.decision

/**
 * DecisionModelCatalog - the single place that describes the decision models we
 * can use, and how each one is obtained.
 *
 * The spec is deliberately loose about Microsoft-Decision-1:
 *   "The document's proposed Microsoft-Decision-1 layer shall be treated as a
 *    replaceable decision-router interface rather than a hard dependency."  (§29)
 *
 * That is why this catalog describes three routes instead of hard-wiring one:
 *
 *  1. DECISION1_API   - Microsoft-Decision-1 is closed-weights, so it is called
 *                       over HTTPS (Azure AI Foundry / OpenRouter). The weights
 *                       never ship in the APK.
 *  2. NEEDLE3         - Cactus Needle 3 is openly downloadable and runs on device
 *                       through the Cactus C/C++ engine (§30 orchestration).
 *  3. LOCAL_ALTERNATIVE - an open-weight decision model for fully offline use.
 *                       (No candidate currently ships a verified ONNX file - see
 *                       [status] below - so we do not offer a download for it.)
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
    val status: String
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
            "then execute through the Cactus C/C++ static engine (android-arm64/libneedle.a + needle.h) " +
            "via the NDK/JNI layer.",
        hardwareTierRequired = "mid-tier",
        status = "Weights downloadable and verifiable. Native engine linkage is pending " +
            "(libneedle.a + needle.h are pinned in [NEEDLE3_ENGINE])."
    )

    /** The native engine files Needle 3 needs, pinned with their real sizes. */
    val NEEDLE3_ENGINE: List<DecisionModelSpec> = listOf(
        DecisionModelSpec(
            id = "needle3-engine-lib",
            displayName = "Needle 3 native engine (libneedle.a)",
            hosting = ModelHosting.ON_DEVICE,
            availability = ModelAvailability.PLANNED,
            downloadUrl = "$HF/Cactus-Compute/needle3/resolve/main/android-arm64/libneedle.a",
            sizeBytes = 2_177_676L,
            sha256 = null, // digest is taken from the release itself when the NDK layer lands
            license = "Cactus Compute community license",
            integrationStrategy = "Linked into the app's native C++ layer and called over JNI.",
            hardwareTierRequired = "mid-tier",
            status = "Pinned coordinate: awaiting the NDK/JNI integration task."
        ),
        DecisionModelSpec(
            id = "needle3-engine-header",
            displayName = "Needle 3 engine header (needle.h)",
            hosting = ModelHosting.ON_DEVICE,
            availability = ModelAvailability.PLANNED,
            downloadUrl = "$HF/Cactus-Compute/needle3/resolve/main/android-arm64/needle.h",
            sizeBytes = 4_528L,
            sha256 = null,
            license = "Cactus Compute community license",
            integrationStrategy = "Compile-time header for the native engine.",
            hardwareTierRequired = null,
            status = "Pinned coordinate: awaiting the NDK/JNI integration task."
        )
    )

    /**
     * Microsoft-Decision-1 - closed weights, so it is an API call, not a download.
     *
     * Microsoft does not publish the weights (9B backbone), therefore the spec's
     * instruction applies: expose it as a replaceable interface (§29) and call it
     * where it is actually served - Azure AI Foundry or OpenRouter.
     */
    val DECISION1_API = DecisionModelSpec(
        id = "ms-decision-1",
        displayName = "Microsoft-Decision-1",
        hosting = ModelHosting.API_ONLY,
        availability = ModelAvailability.AVAILABLE,
        downloadUrl = null,
        sizeBytes = 0L,
        sha256 = null,
        license = "Closed weights - served by Microsoft/Azure under their API terms",
        integrationStrategy = "Call the hosted endpoint over HTTPS (Azure AI Foundry or OpenRouter) " +
            "through Decision1ApiRouter. The API key is kept in the Android Keystore (§42/§43), " +
            "never in source. Falls back to the deterministic router when the call fails.",
        hardwareTierRequired = null,
        status = "Callable once an endpoint URL + API key are configured in Settings."
    )

    /**
     * Open-weight local alternative - documented but NOT downloadable today.
     *
     * The notes suggested `mukaddamzaid/amos-1-mini/resolve/main/model.onnx`
     * (~180 MB INT8), but that path returns HTTP 404: the repository publishes a
     * 1.6 GB `model.pt` checkpoint and no ONNX export, and `fonles-studios/
     * fonles-decision-1` publishes no weights either. Pinning a URL we know is
     * broken would ship a guaranteed-failing download, so we record the finding
     * instead and keep the slot ready.
     */
    val DECISION1_LOCAL_ALTERNATIVE = DecisionModelSpec(
        id = "decision-1-local",
        displayName = "Decision-1 open-weight alternative",
        hosting = ModelHosting.ON_DEVICE,
        availability = ModelAvailability.PLANNED,
        downloadUrl = null,
        sizeBytes = 0L,
        sha256 = null,
        license = "To be confirmed with the chosen checkpoint",
        integrationStrategy = "Offline decision scoring when the API route is unavailable. " +
            "A candidate must publish an ONNX file small enough for mobile before it is pinned here.",
        hardwareTierRequired = "mid-tier",
        status = "Blocked: the documented ONNX coordinates do not exist (verified HTTP 404 for " +
            "mukaddamzaid/amos-1-mini model.onnx; fonles-studios/fonles-decision-1 ships no weights)."
    )

    /** Everything the catalog knows about, including engine parts. */
    val ALL: List<DecisionModelSpec> =
        listOf(NEEDLE3, DECISION1_API, DECISION1_LOCAL_ALTERNATIVE) + NEEDLE3_ENGINE

    /** Look a spec up by id (case-insensitive), or null when unknown. */
    fun byId(id: String): DecisionModelSpec? =
        ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** True when this model can be downloaded onto the device today. */
    fun isDownloadable(id: String): Boolean {
        val spec = byId(id) ?: return false
        return spec.hosting == ModelHosting.ON_DEVICE &&
            spec.availability == ModelAvailability.AVAILABLE &&
            spec.downloadUrl != null
    }
}
