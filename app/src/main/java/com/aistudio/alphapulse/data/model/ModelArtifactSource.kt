package com.aistudio.alphapulse.data.model

/** Public Hugging Face artifact host; overridable for mirrors and tests. */
const val HUGGING_FACE_BASE_URL = "https://huggingface.co"

/**
 * A single downloadable model artifact, pinned to an exact revision and
 * checksum so what the device mounts is byte-for-byte what was published.
 */
data class ModelArtifactSource(
    val modelId: String,
    val displayName: String,
    val microcopy: String,
    val repoId: String,
    val filePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val baseUrl: String = HUGGING_FACE_BASE_URL
) {
    /** Direct download URL on the artifact host. */
    val downloadUrl: String
        get() = "$baseUrl/$repoId/resolve/main/$filePath"

    /** File name written under the model's private directory. */
    val fileName: String
        get() = filePath.substringAfterLast('/')

    val sizeMb: Int
        get() = ((sizeBytes + MEBIBYTE - 1) / MEBIBYTE).toInt()

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
    }
}

/**
 * The published ONNX artifacts the dock installs.
 *
 * Both are ONNX Runtime builds of the upstream TSFMs, quantized to INT8 for
 * on-device inference. Sizes and SHA-256 digests come from the repositories'
 * published LFS metadata, so [ModelArtifactInstaller] verifies the exact bytes
 * that were released (see `ModelArtifactSourceContractTest`, run in CI).
 */
object ModelArtifacts {

    val CHRONOS = ModelArtifactSource(
        modelId = "chronos",
        displayName = "Chronos-Bolt Tiny",
        microcopy = "Zero-shot forecaster",
        repoId = "bakshinghampalace/chronos-bolt-tiny-onnx",
        filePath = "onnx/model_quantized.onnx",
        sizeBytes = 9_995_263L,
        sha256 = "1cc3e12d9df10bdbf09dc8d341c958fd204a8c64700eaad2312367e5eda366d7"
    )

    val TIMESFM = ModelArtifactSource(
        modelId = "timesfm",
        displayName = "TimesFM 2.5 200M",
        microcopy = "Long-context transformer",
        repoId = "bakshinghampalace/timesfm-2.5-200m-onnx",
        filePath = "onnx/model_quantized.onnx",
        sizeBytes = 261_301_673L,
        sha256 = "03c0de47701fab96bca31c9e4004f6977572e1d399d8cf4e8b2cf88651f783bb"
    )

    /**
     * Cactus Needle 3 - the on-device edge/orchestration model of §30.
     *
     * Unlike the two TSFMs above this is a `.cact` checkpoint: the bytes are
     * downloaded and verified here exactly like any other artifact, while the
     * Cactus static engine (android-arm64/libneedle.a + needle.h, pinned in
     * [com.aistudio.alphapulse.domain.decision.DecisionModelCatalog]) is what will
     * actually execute them. Size and digest were taken from the published file
     * (35,335,380 bytes; sha-256 c9d915ec…70c38), so the installer rejects any
     * truncated or tampered download before it can be mounted.
     *
     * Downloads of this artifact are additionally gated by the HardwareGatekeeper:
     * a device below the mid-tier line (< 3.5 GB RAM or < 4 CPU cores) cannot
     * request it at all.
     */
    val NEEDLE3 = ModelArtifactSource(
        modelId = "needle3",
        displayName = "Cactus Needle 3",
        microcopy = "Edge orchestration model",
        repoId = "Cactus-Compute/needle3",
        filePath = "needle3.cact",
        sizeBytes = 35_335_380L,
        sha256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38"
    )

    val ALL: List<ModelArtifactSource> = listOf(CHRONOS, TIMESFM, NEEDLE3)

    fun byId(modelId: String): ModelArtifactSource? =
        ALL.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }
}
