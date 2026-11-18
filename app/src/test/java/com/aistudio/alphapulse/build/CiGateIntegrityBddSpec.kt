package com.aistudio.alphapulse.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the CI gates themselves, after a run where every gate passed and the
 * release still failed.
 *
 * Why this spec exists
 * --------------------
 * The v1.0.20 tag run (`9f72f79`) went green on integrity, both test legs, the
 * model-artifact contract and the whole APK job. The release then refused to
 * publish because `app-arm64-v8a-release.apk` did not exist: `assembleRelease`
 * had died inside `:app:minifyReleaseWithR8`, and the build step ran
 * `gradle ... | tee build-output.log` without `pipefail`, so the step reported
 * `tee`'s success. The release APK upload was additionally gated on
 * `hashFiles(...)`, which turned the absence into a *skipped* step instead of a
 * failure. The first sign of trouble was five minutes later, in another job.
 *
 * These scenarios make that shape impossible again:
 *
 *  - a blocking step that pipes gradle into `tee` must enable `pipefail`;
 *  - the build step must prove the variants it asked for exist;
 *  - an APK upload may not decide whether to run by looking for its own file;
 *  - the cheap gates (both Kotlin source sets, the release R8 pass) run in a
 *    `preflight` job before the APK build, not after it.
 *
 * Spec Ref: §52 Testing Requirements, §63 Definition of Done, C-28
 */
class CiGateIntegrityBddSpec {

    private val workflow: String by lazy { repoFile(".github/workflows/build-apk.yml").readText() }

    // -------------------------------------------------- silent pipeline status

    @Test
    fun `given a blocking step when it pipes gradle into tee then the pipeline status is gradle's`() {
        val offenders = steps().filter { (_, block) ->
            block.contains("gradlew") &&
                block.contains("| tee") &&
                !block.contains("continue-on-error: true") &&
                !block.contains("set -o pipefail") &&
                !block.contains("set -euo pipefail")
        }

        assertTrue(
            "These steps pipe gradle into tee without `set -o pipefail`, so a failing " +
                "task still reports a green step - which is how a dead assembleRelease " +
                "shipped as 'APKs built': ${offenders.map { it.first }}",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------- the build really built it

    @Test
    fun `given the build step when a variant is requested then it asserts that APK exists`() {
        val build = stepNamed("Build APK Packages")
        for (apk in listOf("app-arm64-v8a-debug.apk", "app-arm64-v8a-release.apk")) {
            assertTrue(
                "The build step must name $apk so it can assert the variant it asked for " +
                    "was produced: $build",
                build.contains(apk)
            )
        }
        assertTrue(
            "The build step must fail when a requested APK is missing instead of leaving " +
                "the absence to the release job: $build",
            build.contains("if [ ! -s \"\$apk\" ]; then")
        )
    }

    // ------------------------------------------------ uploads may not self-skip

    @Test
    fun `given an APK upload when the APK is missing then the job fails rather than skipping the step`() {
        for ((name, block) in steps()) {
            if (!name.startsWith("Upload ") || !name.contains("APK")) continue
            assertTrue(
                "$name must fail when its APK is missing, not upload nothing quietly",
                block.contains("if-no-files-found: error")
            )
            assertFalse(
                "$name decides whether to run by looking for its own file, " +
                    "which converts a missing APK into a skipped step and a green job: $block",
                block.contains("hashFiles(")
            )
        }
    }

    // ------------------------------------------------------- cheap gates first

    @Test
    fun `given the workflow when the expensive APK build is planned then the cheap gates run first`() {
        val preflight = stepContainingJob("preflight")

        for (gate in listOf(
            "scripts/check_ci_preflight.py",
            ":app:compileDebugKotlin",
            ":app:compileDebugAndroidTestKotlin",
            ":app:minifyReleaseWithR8"
        )) {
            assertTrue(
                "The preflight job must run $gate before the NDK build: it is the gate " +
                    "that catches a compilation or R8 failure in minutes instead of after " +
                    "the test matrix and the APK build",
                preflight.contains(gate)
            )
        }

        val build = stepContainingJob("build:")
        assertTrue(
            "The `build` job must need `preflight`, or the cheap gates can fail after the " +
                "expensive one has already run: $build",
            Regex("""needs:\s*\[[^\]]*preflight""").containsMatchIn(build)
        )

        val release = stepContainingJob("release:")
        assertTrue(
            "The `release` job must refuse to publish when preflight did not succeed",
            release.contains("preflight")
        )
    }

    @Test
    fun `given the preflight script when read then it checks the workflow and the release body`() {
        val script = repoFile("scripts/check_ci_preflight.py").readText()
        for (subject in listOf("pipefail", "if-no-files-found", "hashFiles", "CHANGELOG.md")) {
            assertTrue(
                "scripts/check_ci_preflight.py must keep checking $subject, so a workflow " +
                    "edit cannot quietly reintroduce the v1.0.20 failure",
                script.contains(subject)
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /** `(step name, step body)` for every `- name:` step in the workflow. */
    private fun steps(): List<Pair<String, String>> =
        Regex("""(?m)^      - name: (.+)$""").findAll(workflow).map { match ->
            val start = match.range.first
            val end = workflow.indexOf("\n      - ", start + 1).let {
                if (it < 0) workflow.length else it
            }
            match.groupValues[1].trim() to workflow.substring(start, end)
        }.toList()

    private fun stepNamed(name: String): String =
        steps().firstOrNull { it.first == name }?.second
            ?: throw AssertionError("The workflow has no '$name' step")

    /** The whole job block that starts with `key` (e.g. `build:`). */
    private fun stepContainingJob(key: String): String {
        val start = workflow.indexOf("\n  $key")
        if (start < 0) throw AssertionError("The workflow has no '$key' job")
        val rest = workflow.substring(start + 1)
        // Search past the job's own header, which would otherwise match at index 0.
        val next = Regex("""(?m)^  [a-z][a-z-]*:$""").find(rest.substring(1))
        return if (next == null) rest else rest.substring(0, next.range.first + 1)
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
