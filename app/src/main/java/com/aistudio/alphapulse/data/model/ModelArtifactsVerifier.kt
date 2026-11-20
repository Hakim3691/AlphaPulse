package com.aistudio.alphapulse.data.model

import java.io.File
import java.security.MessageDigest

/**
 * Verifies that a model file on disk is still the artifact that was published.
 *
 * Why this exists
 * ---------------
 * Mount state used to be restored by checking that the file was non-empty. A
 * download interrupted by a crash, a partial copy, or a corrupted filesystem could
 * therefore be reported READY and handed to the ONNX runtime, and a simple length
 * check would still accept same-size garbage. The only proof is the pinned SHA-256
 * the download was verified against in the first place.
 *
 * Hashing TimesFM (261 MB) on every launch would be wasteful, so the caller keeps a
 * cheap `length:lastModified` token for files it has already verified and only
 * re-hashes when that token changes (a rewrite, a restore, a corruption).
 *
 * Spec Ref: §47 Model Download Architecture, C-36
 */
internal object ModelArtifactsVerifier {

    /** True when [file] is exactly the bytes [source] pins, size and digest. */
    fun matches(source: ModelArtifactSource, file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() != source.sizeBytes) return false
        return sha256(file).equals(source.sha256, ignoreCase = true)
    }

    /**
     * Cheap identity of a file's state. Stored after a successful verification so
     * an unchanged file never has to be re-hashed on the next launch.
     */
    fun token(file: File): String = "${file.length()}:${file.lastModified()}"

    fun sha256(file: File): String {
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
