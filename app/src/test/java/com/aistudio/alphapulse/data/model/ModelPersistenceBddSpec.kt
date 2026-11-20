package com.aistudio.alphapulse.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the TSFM weights live and when a mount is still trustworthy.
 *
 * Why these assertions exist
 * --------------------------
 * A user updates the APK, re-downloads nothing, and expects the models to be
 * there. That only holds if the weights are written to persistent app storage
 * (never a cache directory the OS may reclaim) and if a restart re-verifies the
 * bytes rather than trusting a SharedPreferences flag. A half-written file used to
 * be accepted as long as it was non-empty, so a crash mid-install could leave a
 * corrupt checkpoint reported as READY.
 *
 * Spec Ref: §47 Model Download Architecture, C-36
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelPersistenceBddSpec {

    private lateinit var context: Context
    private val payload = ByteArray(64_000) { (it % 251).toByte() }
    private val source = ModelArtifactSource(
        modelId = "chronos",
        displayName = "Chronos-Bolt Tiny",
        microcopy = "test model",
        repoId = "test/chronos-onnx",
        filePath = "onnx/model_quantized.onnx",
        sizeBytes = payload.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        modelRoot().deleteRecursively()
        ModelDownloadStore.sources = listOf(source)
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        modelRoot().deleteRecursively()
        ModelDownloadStore.sources = ModelArtifacts.ALL
    }

    @Test
    fun `given a model truncated by an interrupted install when the app restarts then it is rejected and removed`() {
        // Given a file that is on disk but shorter than the pinned artifact
        ModelDownloadStore.init(context)
        val installed = ModelDownloadStore.installedFile("chronos")!!
        installed.parentFile!!.mkdirs()
        installed.writeBytes(payload.copyOf(payload.size / 2))
        prefs().edit().putStringSet("ready_models", setOf("chronos")).commit()

        // When the app restarts
        ModelDownloadStore.init(context)

        // Then the mount is dropped and the unusable bytes are deleted, so the next
        // launch downloads instead of mounting a corrupt checkpoint
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("chronos").status)
        assertFalse("A rejected artifact must not stay on disk", installed.exists())
        assertTrue(ModelDownloadStore.readyModelIds().isEmpty())
    }

    @Test
    fun `given a model with the right length but the wrong bytes when the app restarts then it is rejected`() {
        // Given a same-size file whose digest does not match the pin
        ModelDownloadStore.init(context)
        val installed = ModelDownloadStore.installedFile("chronos")!!
        installed.parentFile!!.mkdirs()
        installed.writeBytes(ByteArray(payload.size) { 0x7F })
        prefs().edit().putStringSet("ready_models", setOf("chronos")).commit()

        // When the app restarts
        ModelDownloadStore.init(context)

        // Then length alone was never proof of a mount
        assertEquals(ModelLoadStatus.NOT_DOWNLOADED, ModelDownloadStore.stateOf("chronos").status)
        assertFalse(installed.exists())
    }

    @Test
    fun `given a verified model on disk when the app restarts then it stays mounted without a download`() {
        // Given bytes that match the pinned size and digest
        ModelDownloadStore.init(context)
        val installed = ModelDownloadStore.installedFile("chronos")!!
        installed.parentFile!!.mkdirs()
        installed.writeBytes(payload)
        prefs().edit().putStringSet("ready_models", setOf("chronos")).commit()

        // When the app restarts
        ModelDownloadStore.init(context)

        // Then the weights are recognised immediately, with no network involved
        assertEquals(ModelLoadStatus.READY, ModelDownloadStore.stateOf("chronos").status)
        assertEquals(listOf("chronos"), ModelDownloadStore.readyModelIds())
        assertTrue(installed.isFile)
    }

    @Test
    fun `given no external storage when the model directory is resolved then it falls back to filesDir and never the cache`() {
        val external = null
        val files = File(context.filesDir, "app-files")
        val cache = File(context.cacheDir, "app-cache")

        val base = ModelFileLocator.baseDirectory(external, files)

        assertEquals(
            "Weights must survive an update and a cache sweep, so the fallback is the " +
                "persistent files directory",
            files,
            base
        )
        assertFalse(
            "The OS may delete cacheDir at any time; a model there is redownloaded",
            base.canonicalPath.startsWith(cache.canonicalPath)
        )
    }

    private fun modelRoot(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    private fun prefs() =
        context.getSharedPreferences("alphapulse_model_downloads", Context.MODE_PRIVATE)
}
