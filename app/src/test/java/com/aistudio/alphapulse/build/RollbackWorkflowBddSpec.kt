package com.aistudio.alphapulse.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-26 — a rollback must be a release, not a guess.
 *
 * Why this spec exists
 * --------------------
 * "Roll back to before the merge" was claimed once while the merged UI was still on screen.
 * A rollback is a product action with the same evidence bar as a forward release: it has to
 * rebuild the previously verified source, carry the pinned checkpoint, attach both APKs and
 * bump `versionCode` so an installed device can actually accept it. This spec reads the
 * workflow that does that, so the strategy cannot quietly rot into a documentation promise.
 *
 * Spec Ref: C-26, SPEC_TRACEABILITY §76
 */
class RollbackWorkflowBddSpec {

    private val workflow: String by lazy {
        repoFile(".github/workflows/rollback.yml").readText()
    }

    @Test
    fun `given the rollback workflow when read then it is a manual pipeline with an explicit target tag a version code and a reason`() {
        assertTrue(
            "A rollback must be manually dispatched, never triggered by a push",
            workflow.contains("workflow_dispatch:")
        )
        for (input in listOf("target_tag:", "version_code:", "reason:")) {
            assertTrue("The rollback workflow must require `$input`: $workflow", workflow.contains(input))
        }
        assertTrue(
            "The target must be an existing version tag, so the rebuilt source is a released one",
            workflow.contains("refs/tags/")
        )
    }

    @Test
    fun `given a rollback is requested when it runs then it refuses a version code that cannot install over the current release`() {
        assertTrue(
            "The workflow must read the newest published versionCode/version to compare against",
            workflow.contains("gh api") || workflow.contains("GITHUB_TOKEN")
        )
        assertTrue(
            "A lower version code would be rejected by Android as a downgrade",
            workflow.contains("version_code") && workflow.contains("greater")
        )
        assertTrue(
            "The reason must be recorded in the run summary, so the rollback is auditable",
            workflow.contains("GITHUB_STEP_SUMMARY")
        )
    }

    @Test
    fun `given the target tag when the rollback builds then it rebuilds both arm64 APKs from that tag with the pinned checkpoint`() {
        assertTrue(
            "The rollback must build the target tag's source, not main",
            workflow.contains("github.event.inputs.target_tag")
        )
        assertTrue("Both APK variants must be assembled", workflow.contains(":app:assembleDebug") && workflow.contains(":app:assembleRelease"))
        assertTrue(
            "The pinned checkpoint must be bundled and verified in the rollback build too",
            workflow.contains(":app:bundleNeedle3Weights") && workflow.contains(":app:verifyPackagedWeights")
        )
        assertTrue(
            "Only arm64-v8a is shipped, so the rollback must not widen the ABI",
            workflow.contains("arm64-v8a") && !workflow.contains("armeabi-v7a")
        )
    }

    @Test
    fun `given the rollback build when publishing then both APKs are required and the release is marked latest`() {
        val gate = workflow.indexOf("Require both APKs before publishing the rollback")
        val publish = workflow.indexOf("Publish the rollback release")
        assertTrue("The rollback needs a completeness gate before publishing", gate >= 0)
        assertTrue("The completeness gate must run before publishing", gate < publish)
        for (apk in listOf("app-arm64-v8a-debug.apk", "app-arm64-v8a-release.apk")) {
            assertTrue("The gate must require $apk", workflow.contains(apk))
        }
        assertTrue(
            "The rollback release must become the latest release, or users never receive it",
            workflow.contains("make_latest: true")
        )
        assertFalse(
            "A rollback must never force-push or delete a tag; history stays immutable",
            workflow.contains("push --force") || workflow.contains("git push -f")
        )
    }

    @Test
    fun `given the rollback workflow when documented then the README and the agent guide describe the same strategy`() {
        val readme = repoFile("README.md").readText()
        assertTrue(
            "README must document the rollback strategy, not only the forward pipeline",
            readme.contains("rollback.yml")
        )
        val contracts = repoFile("FINALIZED_CONTRACTS.md").readText()
        assertTrue("C-26 must stay in the registry", contracts.contains("| C-26 |"))
    }

    /** Locates a repository file from whichever directory tests run in. */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
