package com.aistudio.alphapulse.data.model

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Mounts a model that the APK already ships.
 *
 * Why a copy is needed
 * --------------------
 * The Cactus engine loads a checkpoint from a path and memory-maps it there
 * (`needle_load`), so it needs a real file, not an `AssetFileDescriptor`. The
 * artifact is still zero-download: the bytes come out of the APK on first mount
 * and are verified against the same pinned size and SHA-256 a network download
 * would be, then left in the private model directory the engine expects.
 *
 * `androidResources { noCompress += "cact" }` keeps the asset stored rather than
 * deflated, so this copy is a straight stream instead of an inflate of 35 MB, and
 * the engine maps the resulting file rather than reading it into the heap.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §47 Model Download Architecture
 */
internal object BundledModelInstaller {

    /** Asset locations a bundled artifact may use, most specific first. */
    private fun candidates(source: ModelArtifactSource): List<String> = listOf(
        "models/${source.modelId}/${source.fileName}",
        source.fileName
    )

    /** The asset path this artifact is bundled at, or null when it is not shipped. */
    fun bundledAssetPath(context: Context, source: ModelArtifactSource): String? =
        candidates(source).firstOrNull { path ->
            runCatching { context.assets.open(path).close() }.isSuccess
        }

    /**
     * Copy the bundled artifact to [target] and verify it.
     *
     * @return true when [target] is now a verified mount of [source]; false when
     *   the artifact is not bundled, is truncated, or does not match its digest
     */
    fun install(context: Context, source: ModelArtifactSource, target: File): Boolean {
        val assetPath = bundledAssetPath(context, source) ?: return false

        // Already mounted from the same bytes: length is the cheap check, and the
        // digest was verified when this file was written.
        if (target.isFile && target.length() == source.sizeBytes) return true

        val directory = target.parentFile ?: return false
        directory.mkdirs()
        val staging = File(directory, "${source.fileName}.part")

        val copied = runCatching {
            context.assets.open(assetPath).use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            staging.length() == source.sizeBytes && sha256(staging) == source.sha256
        }.getOrDefault(false)

        if (!copied) {
            staging.delete()
            return false
        }

        if (target.exists() && !target.delete()) {
            staging.delete()
            return false
        }
        return staging.renameTo(target)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
