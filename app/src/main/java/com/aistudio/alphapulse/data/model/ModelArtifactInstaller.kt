package com.aistudio.alphapulse.data.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Streams a [ModelArtifactSource] onto local storage.
 *
 * The transfer is a real HTTPS download: bytes are written to a `.part` file
 * while being hashed, progress is reported at 1% granularity, and the file is
 * only moved into place once the pinned size and SHA-256 both match. Any
 * failure (HTTP error, short body, checksum mismatch, cancellation) deletes the
 * partial file so a broken artifact can never be mounted.
 */
class ModelArtifactInstaller(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun install(
        source: ModelArtifactSource,
        target: File,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(ioDispatcher) {
        val parent = target.parentFile
            ?: throw IOException("No parent directory for ${target.path}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create model directory ${parent.path}")
        }

        val freeSpace = parent.usableSpace
        if (freeSpace > 0 && freeSpace < source.sizeBytes) {
            throw IOException(
                "Not enough free storage: need ${source.sizeMb} MB, " +
                    "have ${freeSpace / (1024L * 1024L)} MB"
            )
        }

        val partial = File(parent, "${target.name}.part")
        partial.delete()
        onProgress(0f)

        try {
            val request = Request.Builder().url(source.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Artifact download failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Artifact response had no body")
                val expectedBytes = source.sizeBytes

                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var lastPercent = -1

                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            val percent = if (expectedBytes > 0) {
                                (written * 100L / expectedBytes).toInt().coerceIn(0, 100)
                            } else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }

                if (written != expectedBytes) {
                    throw IOException(
                        "Artifact size mismatch: expected $expectedBytes bytes, received $written"
                    )
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(source.sha256, ignoreCase = true)) {
                    throw IOException("Artifact checksum mismatch for ${source.displayName}")
                }
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            onProgress(1f)
            target
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }
}
