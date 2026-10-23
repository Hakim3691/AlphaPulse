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

    val ALL: List<ModelArtifactSource> = listOf(CHRONOS, TIMESFM)

    fun byId(modelId: String): ModelArtifactSource? =
        ALL.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }
}
