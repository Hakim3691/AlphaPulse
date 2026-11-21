package com.aistudio.alphapulse.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 D4/§5: the Gradle project splits into :app, :core_contract and
 * :proactive_trader, and the feature is delivered on demand rather than compiled in.
 *
 * These assertions read the build files themselves because that split is a build
 * contract: a future edit that quietly folds the module back into the base APK
 * would still pass every UI test.
 *
 * Spec Ref: Phase 1 §5 Dynamic Feature Module Architecture, §7, D4, D6
 */
class DynamicFeatureContractBddSpec {

    @Test
    fun `given the project settings when read then app core_contract and proactive_trader are all included`() {
        val settings = repoFile("settings.gradle.kts").readText()

        assertTrue(settings.contains("include(\":app\")"))
        assertTrue(settings.contains("include(\":core_contract\")"))
        assertTrue(settings.contains("include(\":proactive_trader\")"))
    }

    @Test
    fun `given the base app build file when read then the feature is dynamic and the base never links it`() {
        val app = repoFile("app/build.gradle.kts").readText()

        assertTrue(
            "The base module must declare the dynamic feature",
            app.contains("dynamicFeatures += setOf(\":proactive_trader\")")
        )
        assertTrue(
            "The base module must depend on the shared contract",
            app.contains("implementation(project(\":core_contract\"))")
        )
        assertTrue(
            "The Play Feature Delivery client must be present",
            app.contains("libs.play.feature.delivery")
        )
        assertFalse(
            "A compile-time dependency on the feature would defeat on-demand delivery",
            app.contains("implementation(project(\":proactive_trader\"))")
        )
    }

    @Test
    fun `given the feature module when its build and manifest are read then it is on demand and depends on the base`() {
        val build = repoFile("proactive_trader/build.gradle.kts").readText()
        val manifest = repoFile("proactive_trader/src/main/AndroidManifest.xml").readText()

        assertTrue(build.contains("alias(libs.plugins.android.dynamic.feature)"))
        assertTrue(build.contains("implementation(project(\":app\"))"))
        assertTrue(build.contains("implementation(project(\":core_contract\"))"))
        assertTrue(
            "The module must be on-demand, not fused into the base APK",
            manifest.contains("dist:onDemand=\"true\"")
        )
        assertTrue(manifest.contains("com.alphapulse.proactive.TraderHostActivity") ||
            manifest.contains(".TraderHostActivity"))
    }

    @Test
    fun `given the feature host when read then it renders the canonical seven ProactiveMS tabs and no native code`() {
        val activity = repoFile(
            "proactive_trader/src/main/java/com/alphapulse/proactive/TraderHostActivity.kt"
        ).readText()

        // C-22 replaced the earlier six-label list: `Analysis` is retired, `AlphaPulse` and
        // `Replay` are part of the contract, and the host must read the one tab order rather
        // than declaring a second list of its own (`ProactiveTabsBddSpec`).
        assertTrue(
            "The host must render the canonical tab order",
            activity.contains("ProactiveMsWorkspaceState.TAB_ORDER")
        )
        assertFalse(
            "The retired Analysis tab must not return",
            activity.contains("\"Analysis\"")
        )
        assertFalse(
            "The feature ships no native libraries, so the arm64 ABI split stays untouched",
            File(repoFile("proactive_trader").path, "src/main/cpp").exists()
        )
    }

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
        throw AssertionError("Could not locate the repository root")
    }
}
