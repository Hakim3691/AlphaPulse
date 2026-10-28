package com.aistudio.alphapulse.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The last hop of the bundled-weights path: the app opening `assets/needle3.cact`
 * through the real `AssetManager` instead of a fake.
 *
 * Why this spec exists
 * --------------------
 * Everything before this point is verified without Android: the bundling task
 * pins the artifact, `BundledModelInstallerBddSpec` proves the resolution and
 * verification logic against a fake asset source, and `:app:verifyPackagedWeights`
 * hashes the entry inside the built APK. None of them prove that the bytes a
 * running app hands to the engine are actually reachable under the asset name
 * [BundledModelInstaller] looks for - a rename, a `noCompress` regression that
 * pushed the entry somewhere unexpected, or an asset-merge exclusion would pass
 * all of those and fail only here.
 *
 * What this does and does not prove
 * ---------------------------------
 * `unitTests.isIncludeAndroidResources = true` makes Robolectric serve the merged
 * `src/main/assets` tree through its AssetManager shadow, so this is the real
 * merge output and a real `AssetManager.open`, but it is **not** a device: the
 * APK install step, the ZIP entry and the memory-mapped `needle_load` call are
 * covered by `:app:verifyPackagedWeights` and the JNI bridge specs respectively.
 * The digest is read from [ModelArtifacts.NEEDLE3], the same pin the build
 * script and the runtime verifier use, so there is no second copy to drift.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §47 Model Download Architecture,
 * §52 Testing Requirements
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BundledWeightsAssetManagerBddSpec {

    private val needle = ModelArtifacts.NEEDLE3

    private lateinit var context: Context
    private lateinit var workspace: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workspace = Files.createTempDirectory("bundled-mount").toFile()
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `given the packaged app when the bundled checkpoint is opened through the asset manager then it is the pinned artifact`() {
        // given the asset the Gradle task bundles, resolved the way production does
        val assetPath = BundledModelInstaller.bundledAssetPath(context, needle)
        assertEquals(
            "The app must find ${needle.fileName} in the merged assets the APK carries",
            needle.fileName,
            assetPath
        )

        // when the bytes are read straight out of the AssetManager and hashed
        val (length, digest) = context.assets.open(needle.fileName).use { stream ->
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
                total += read
            }
            total to sha.digest().joinToString("") { "%02x".format(it) }
        }

        // then it is exactly the artifact the build and the runtime pin
        assertEquals(
            "The packaged checkpoint must be the pinned size; a truncated asset " +
                "would mount bytes the engine cannot run",
            needle.sizeBytes,
            length
        )
        assertEquals(
            "The packaged checkpoint must be the pinned SHA-256",
            needle.sha256,
            digest
        )

        // and the production mount path accepts those very bytes from the same
        // AssetManager, so the verified copy the engine memory-maps is this file
        val target = File(workspace, "models/${needle.modelId}/${needle.fileName}")
        assertTrue(
            "The production installer must mount the bundled checkpoint without a network",
            BundledModelInstaller.install(context, needle, target)
        )
        assertEquals(
            "The mounted file must be the packaged artifact",
            needle.sizeBytes,
            target.length()
        )
    }
}
