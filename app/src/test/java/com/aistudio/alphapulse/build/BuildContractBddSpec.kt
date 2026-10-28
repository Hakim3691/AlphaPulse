package com.aistudio.alphapulse.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The build and CI contract that keeps installed apps updatable.
 *
 * Why these assertions exist
 * --------------------------
 * Android only installs a new APK over an existing one when all three of these
 * hold: the same `applicationId`, a signature from the same certificate, and a
 * `versionCode` that is not lower. Nothing in the Kotlin code can enforce that,
 * and a single edited line in `build.gradle.kts` or the workflow silently turns
 * every release into "uninstall first, lose your local data and models".
 *
 * These specs read the real files rather than a copy of their content: they
 * extract the values that are configured today and assert the properties that
 * must always hold, so an edit that breaks the property fails here even when the
 * numbers themselves legitimately change.
 *
 * Spec Ref: §45 Performance Requirements, §47 Model Download Architecture,
 * §52 Testing Requirements
 */
class BuildContractBddSpec {

    private val moduleBuild: String by lazy { repoFile("app/build.gradle.kts").readText() }
    private val workflow: String by lazy { repoFile(".github/workflows/build-apk.yml").readText() }

    // ----------------------------------------------------------- applicationId

    @Test
    fun `given the module build file when read then the applicationId is one literal unchanged from the shipped app`() {
        val ids = Regex("""applicationId\s*=\s*"([^"]+)"""")
            .findAll(moduleBuild)
            .map { it.groupValues[1] }
            .toList()

        assertEquals("Exactly one applicationId must be declared: $ids", 1, ids.size)
        assertEquals(
            "A different applicationId is a different app: it installs beside the " +
                "existing one instead of updating it",
            "com.aistudio.alphapulse.kxmpzq",
            ids.single()
        )
        assertFalse(
            "A computed applicationId could change between builds",
            moduleBuild.contains("applicationIdSuffix")
        )
    }

    // -------------------------------------------------------------- versionCode

    @Test
    fun `given the versionCode formula when run numbers grow then every later build outranks the previous one`() {
        val offset = versionCodeOffset()
        assertTrue(
            "BUILD_NUMBER must be the increment",
            moduleBuild.contains("System.getenv(\"BUILD_NUMBER\")")
        )

        val codes = listOf(1, 2, 1_000, 50_000).map { offset + it }

        for ((previous, next) in codes.zipWithNext()) {
            assertTrue(
                "versionCode must strictly grow ($previous -> $next); Android refuses a " +
                    "lower code with INSTALL_FAILED_VERSION_DOWNGRADE",
                next > previous
            )
        }
        assertEquals("Every CI build must be distinct", codes.size, codes.toSet().size)
        assertTrue(
            "The lowest CI versionCode ($offset) must outrank the pre-offset releases and " +
                "local builds",
            offset >= 10_000
        )
    }

    @Test
    fun `given an explicit VERSION_CODE when the build runs then it still overrides the computed code`() {
        assertTrue(
            "The override must be read first",
            Regex("""versionCode\s*=\s*System\.getenv\("VERSION_CODE"\)""").containsMatchIn(moduleBuild)
        )
        assertTrue(
            "The run-number formula must remain the fallback, not replace the override",
            Regex(
                """versionCode\s*=\s*System\.getenv\("VERSION_CODE"\)\?\.toIntOrNull\(\)\s*\n?\s*\?:"""
            ).containsMatchIn(moduleBuild)
        )
    }

    // ------------------------------------------------------------------ ABIs

    @Test
    fun `given the native and split configuration when read then only arm64-v8a can be packaged`() {
        val filterList = Regex("""abiFilters \+= listOf\(([^)]*)\)""")
            .find(moduleBuild)?.groupValues?.get(1)
            ?: failWith("No abiFilters declaration was found")

        assertEquals(
            "The NDK must build exactly the ABI the APK ships",
            setOf("arm64-v8a"),
            quotedValues(filterList)
        )

        val splits = moduleBuild.substringAfter("splits {").substringBefore("compileOptions")
        val includes = Regex("""include\(([^)]*)\)""").find(splits)?.groupValues?.get(1)
            ?: failWith("No split include(...) was found")

        assertEquals(
            "The APK split must include exactly arm64-v8a",
            setOf("arm64-v8a"),
            quotedValues(includes)
        )
        assertTrue(
            "A universal APK would ship native libraries for ABIs that are not built",
            splits.contains("isUniversalApk = false")
        )
        assertFalse(
            "An emulator build would reintroduce an ABI the engine cannot run",
            includes.contains("x86")
        )
    }

    // --------------------------------------------------------------- packaging

    @Test
    fun `given the resource packaging configuration when read then model weights are stored uncompressed`() {
        val noCompress = Regex("""noCompress \+= listOf\(([^)]*)\)""")
            .find(moduleBuild)?.groupValues?.get(1)
            ?: failWith("No noCompress declaration was found")

        val extensions = quotedValues(noCompress)
        for (required in listOf("cact", "onnx", "bin")) {
            assertTrue(
                "'.${required}' must be stored uncompressed so the engine maps the weights " +
                    "instead of inflating them at runtime; found $extensions",
                extensions.contains(required)
            )
        }
    }

    // ------------------------------------------------------------------- CI

    @Test
    fun `given the workflow when read then signing uses a stable keystore and not a freshly generated key`() {
        assertTrue(
            "Signing must be cache-backed so every run restores the same certificate",
            workflow.contains("actions/cache@v4")
        )

        val cacheKey = workflow.lines()
            .firstOrNull { it.trim().startsWith("key:") }
            ?.trim()
            ?: failWith("The keystore cache has no key")

        assertFalse(
            "A run-scoped key restores nothing: every build would sign with a new " +
                "certificate and break in-place updates. Found: $cacheKey",
            cacheKey.contains("\${'$'}{{")
        )
        assertTrue(
            "The cache key must be the stable signing key: $cacheKey",
            cacheKey.contains("alphapulse-signing-keystores")
        )

        assertTrue(
            "A secret-supplied release keystore must be supported",
            workflow.contains("secrets.KEYSTORE_BASE64")
        )
        assertTrue(
            "A secret-supplied debug keystore must be supported",
            workflow.contains("secrets.DEBUG_KEYSTORE_BASE64")
        )
        assertTrue(
            "Generation must be a guarded first-run fallback, not the normal path",
            workflow.contains("if [ ! -s my-upload-key.jks ]; then")
        )
        assertTrue(
            "The certificate fingerprint must be reported so a change is visible",
            workflow.contains("SHA256:")
        )
    }

    @Test
    fun `given the workflow when read then debug and release APKs are uploaded as two separate artifacts`() {
        val debugStep = stepBlock("Upload debug APK (arm64-v8a)")
        assertTrue(
            "The debug artifact must be its own download: $debugStep",
            debugStep.contains("name: alphapulse-arm64-v8a-debug")
        )
        assertTrue(
            "It must upload only the arm64 debug APK: $debugStep",
            debugStep.contains("app/build/outputs/apk/debug/app-arm64-v8a-debug.apk")
        )
        assertFalse("A glob would bundle more than one APK: $debugStep", debugStep.contains("*.apk"))

        val releaseStep = stepBlock("Upload release APK (arm64-v8a)")
        assertTrue(
            "The release artifact must be its own download: $releaseStep",
            releaseStep.contains("name: alphapulse-arm64-v8a-release")
        )
        assertTrue(
            "It must upload only the arm64 release APK: $releaseStep",
            releaseStep.contains("app/build/outputs/apk/release/app-arm64-v8a-release.apk")
        )

        assertFalse(
            "The combined APK archive must not come back",
            workflow.contains("path: app/build/outputs/apk/**/*.apk")
        )
        assertFalse(
            "The combined artifact name must not come back",
            workflow.contains("name: alphapulse-apks-")
        )
        assertTrue(
            "The release must attach both per-build-type artifacts",
            workflow.contains("pattern: alphapulse-arm64-v8a-*")
        )
    }

    @Test
    fun `given the workflow when read then the Needle 3 weights are bundled before the APK is assembled`() {
        val bundleStep = stepBlock("Pre-bundle the Needle 3 weights")
        assertTrue(
            "The weights must be fetched and verified before packaging: $bundleStep",
            bundleStep.contains(":app:bundleNeedle3Weights")
        )
        assertTrue(
            "A missing asset must fail the build rather than shipping an APK that " +
                "downloads at runtime",
            bundleStep.contains("app/src/main/assets/needle3.cact")
        )
        assertTrue(
            "The bundle step must run before the APK step",
            workflow.indexOf("Pre-bundle the Needle 3 weights") < workflow.indexOf("Build APK Packages")
        )
    }

    // ---------------------------------------------------------------- helpers

    /** The `versionCodeOffset` the module build declares, as a number. */
    private fun versionCodeOffset(): Int {
        val raw = Regex("""val versionCodeOffset\s*=\s*([0-9_]+)""")
            .find(moduleBuild)?.groupValues?.get(1)
            ?: failWith("No versionCodeOffset was found in app/build.gradle.kts")
        return raw.replace("_", "").toInt()
    }

    /** Quoted literals inside a Kotlin list argument. */
    private fun quotedValues(listBody: String): Set<String> =
        Regex("\"([^\"]+)\"").findAll(listBody).map { it.groupValues[1] }.toSet()

    /** The lines of one workflow step, from its name to the next step's name. */
    private fun stepBlock(name: String): String {
        val marker = "- name: $name"
        val start = workflow.indexOf(marker)
        if (start < 0) failWith("The workflow has no step named '$name'")
        val rest = workflow.substring(start + marker.length)
        val end = rest.indexOf("\n      - name:")
        return if (end < 0) rest else rest.substring(0, end)
    }

    /** Locates a file from the repository root, whichever directory tests run in. */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.isFile) failWith("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        return failWith("Could not locate the repository root from ${File("").absolutePath}")
    }

    private fun failWith(message: String): Nothing = throw AssertionError(message)
}
