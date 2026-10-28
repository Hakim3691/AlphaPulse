package com.aistudio.alphapulse.data.model

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Opens one APK asset by path, or returns null when the APK does not carry it.
 *
 * This is the narrow seam that makes the bundled-weight path testable on the JVM:
 * the production implementation reads `AssetManager`, a fake reads a map. Nothing
 * else about resolution or verification differs between the two.
 *
 * Spec Ref: §47 Model Download Architecture, §52 Testing Requirements
 */
internal fun interface BundledAssetSource {

    /** @return the asset's bytes, or null when [path] is not bundled */
    fun open(path: String): InputStream?
}

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
 * Resolution and installation are split from the Android [AssetManager] through
 * [BundledAssetSource], so `BundledModelInstallerBddSpec` exercises the same
 * logic with a fake. The [Context] overloads are the production path and behave
 * exactly as before; they simply wrap `context.assets` in that seam.
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
        bundledAssetPath(source, ContextAssetSource(context.assets))

    /** [bundledAssetPath] over an explicit asset source. */
    fun bundledAssetPath(source: ModelArtifactSource, assets: BundledAssetSource): String? =
        candidates(source).firstOrNull { path -> assets.open(path)?.use { } != null }

    /**
     * Copy the bundled artifact to [target] and verify it.
     *
     * @return true when [target] is now a verified mount of [source]; false when
     *   the artifact is not bundled, is truncated, or does not match its digest.
     *   `false` is the actionable failure: the caller keeps the network download
     *   as the fallback, and nothing partial is left on disk.
     */
    fun install(context: Context, source: ModelArtifactSource, target: File): Boolean =
        install(ContextAssetSource(context.assets), source, target)

    /** [install] over an explicit asset source. */
    fun install(assets: BundledAssetSource, source: ModelArtifactSource, target: File): Boolean {
        val assetPath = bundledAssetPath(source, assets) ?: return false

        // Already mounted from the same bytes: length is the cheap check, and the
        // digest was verified when this file was written.
        if (target.isFile && target.length() == source.sizeBytes) return true

        val directory = target.parentFile ?: return false
        directory.mkdirs()
        val staging = File(directory, "${source.fileName}.part")

        val copied = runCatching {
            val input = assets.open(assetPath) ?: return@runCatching false
            input.use { stream ->
                staging.outputStream().use { output -> stream.copyTo(output) }
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

    /**
     * The production source: the APK's asset manager.
     *
     * `AssetManager.open` throws `FileNotFoundException` for a path the APK does
     * not carry, which is the normal case for a model that was never bundled; it is
     * reported as absence, never as a crash.
     */
    private class ContextAssetSource(private val assets: AssetManager) : BundledAssetSource {
        override fun open(path: String): InputStream? =
            runCatching { assets.open(path) }.getOrNull()
    }
}
