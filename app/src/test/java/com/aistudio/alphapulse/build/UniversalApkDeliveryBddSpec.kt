package com.aistudio.alphapulse.build

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 D5/C-03/C-04: the on-demand feature must be installable from the release.
 *
 * Why this spec exists
 * --------------------
 * `:proactive_trader` is `dist:onDemand="true"` and C-03 deliberately ships no
 * universal APK, so the published `app-arm64-v8a-release.apk` contains no
 * `com.alphapulse.proactive` classes at all. Play Feature Delivery installs the
 * module; a sideloaded APK cannot, so `TraderNavigation` correctly reports
 * `VIP unavailable` and the paid surface is unreachable outside Play.
 *
 * The fix is a second, universal artifact built by bundletool from the same
 * verified deploy bundle: `--mode=universal` fuses every module whose manifest
 * allows fusing into one APK, which makes the trader host class really present on
 * a sideloaded device. This spec guards the four things that make that true:
 *
 *  - the module opts into fusing (otherwise bundletool omits it from the APK);
 *  - CI builds and signs the universal APK from the verified `.aab`, and the
 *    verifier proves the fused artifact carries the host class and only arm64
 *    native code;
 *  - the release job refuses to publish without it;
 *  - C-03/C-04 record the decision and point at this spec.
 *
 * Spec Ref: Phase 1 §5 Dynamic Feature Module Architecture, D4, D5, C-03, C-04, C-32
 */
class UniversalApkDeliveryBddSpec {

    private val workflow: String by lazy { repoFile(".github/workflows/build-apk.yml").readText() }
    private val verifier: File by lazy { repoFile("scripts/verify_universal_apk.py") }

    // ------------------------------------------------------------- the module

    @Test
    fun `given the feature manifest when read then the module is fused so the universal APK carries it`() {
        val manifest = repoFile("proactive_trader/src/main/AndroidManifest.xml").readText()

        assertTrue(
            "The module must stay on-demand: Play still delivers it separately: $manifest",
            manifest.contains("dist:onDemand=\"true\"")
        )
        assertTrue(
            "bundletool omits a module whose fusing is disabled from the universal APK, so " +
                "the sideloaded release would keep reporting 'VIP unavailable': $manifest",
            manifest.contains("<dist:fusing dist:include=\"true\" />")
        )
    }

    // ----------------------------------------------------------- the CI stage

    @Test
    fun `given the workflow when the bundle is verified then bundletool builds the arm64 universal APK before upload`() {
        val start = workflow.indexOf("- name: Build the universal APK from the verified bundle")
        assertTrue(
            "CI must build the universal APK from the same verified bundle: no such step",
            start >= 0
        )
        val block = workflow.substring(start).substringBefore("\n      - name:")

        assertTrue(
            "It must consume the .aab the bundle step already proved carries the feature: $block",
            block.contains("app/build/outputs/bundle/debug/app-debug.aab")
        )
        assertTrue(
            "Universal mode is what fuses the on-demand module into one installable APK: $block",
            block.contains("--mode=universal")
        )
        assertTrue(
            "A sideloaded APK must be signed, or Android refuses to install it: $block",
            block.contains("--ks=debug.keystore") && block.contains("androiddebugkey")
        )
        assertTrue(
            "The maven-artifact jar under com/android/tools/build has no Main-Class, so CI must " +
                "download the executable release distribution: $block",
            block.contains("bundletool-all-")
        )
        assertTrue(
            "The artifact name must stay inside the arm64-v8a family the release already uses: $block",
            block.contains("app-arm64-v8a-universal-debug.apk")
        )
        assertTrue(
            "The fused artifact must be verified before it can be uploaded: $block",
            block.contains("scripts/verify_universal_apk.py")
        )
        assertTrue(
            "The bundle step must run first, or there is no .aab to convert",
            workflow.indexOf("Bundle the app with its on-demand feature") < start
        )
        assertTrue(
            "The universal APK must be built before it is uploaded",
            start < workflow.indexOf("- name: Upload universal APK (arm64-v8a)")
        )
    }

    // -------------------------------------------------------- the verifier

    @Test
    fun `given a fused APK when verified then the trader host class and only arm64 native code are required`() {
        val good = fusedApk("lib/arm64-v8a/libneedle.so")
        val result = runVerifier(good)
        assertEquals(
            "A universal APK that fuses the trader host class and ships only arm64 native " +
                "code must pass verification: ${result.output}",
            0,
            result.exitCode
        )

        val foreignAbi = fusedApk("lib/x86_64/libneedle.so")
        val rejectedAbi = runVerifier(foreignAbi)
        assertFalse(
            "A fused APK carrying a non-arm64 library must be rejected: ${rejectedAbi.output}",
            rejectedAbi.exitCode == 0
        )
        assertTrue(
            "The rejection must name the offending ABI: ${rejectedAbi.output}",
            rejectedAbi.output.contains("x86_64")
        )

        val missingHost = fusedApk("lib/arm64-v8a/libneedle.so", includeHostClass = false)
        val rejectedHost = runVerifier(missingHost)
        assertFalse(
            "An APK whose dex has no trader host class would still say 'VIP unavailable', so " +
                "it must be rejected: ${rejectedHost.output}",
            rejectedHost.exitCode == 0
        )
        assertTrue(
            "The rejection must name the missing host class: ${rejectedHost.output}",
            rejectedHost.output.contains("TraderHostActivity")
        )
    }

    @Test
    fun `given the verifier source when read then it checks dex for the canonical host descriptor`() {
        val script = verifier.readText()

        assertTrue(
            "The verifier must look for the component C-33 launches by name, or it can pass an " +
                "APK the app cannot start: $script",
            script.contains("com.alphapulse.proactive.TraderHostActivity")
        )
        assertTrue(
            "It must inspect every top-level dex file: $script",
            script.contains(".dex")
        )
        assertTrue(
            "It must reject non-arm64 native entries: $script",
            script.contains("lib/arm64-v8a/")
        )
    }

    // ---------------------------------------------------------- the delivery

    @Test
    fun `given a version tag when publishing then the universal APK is uploaded stashed and required`() {
        val upload = stepBlock("Upload universal APK (arm64-v8a)")
        assertTrue(
            "The universal APK must be its own artifact in the arm64-v8a family: $upload",
            upload.contains("name: alphapulse-arm64-v8a-universal")
        )
        assertTrue(
            "A missing universal APK must fail the build, never ship a release without it: $upload",
            upload.contains("if-no-files-found: error")
        )
        assertTrue(
            "It must upload the fused APK, not a glob of the split outputs: $upload",
            upload.contains("app/build/outputs/apk/universal/app-arm64-v8a-universal-debug.apk")
        )

        val stash = stepBlock("Stash the verified APKs for the release job")
        assertTrue(
            "The quota-independent transport must carry the universal APK too: $stash",
            stash.contains("app/build/outputs/apk/universal/app-arm64-v8a-universal-debug.apk")
        )

        val gateStart = workflow.indexOf("- name: Require every arm64-v8a APK before publishing")
        assertTrue(
            "The release job must refuse to publish without the universal APK: no completeness gate",
            gateStart >= 0
        )
        val gate = workflow.substring(gateStart).substringBefore("\n      - name: Publish Release with APK Assets")
        for (apk in listOf(
            "app-arm64-v8a-debug.apk",
            "app-arm64-v8a-release.apk",
            "app-arm64-v8a-universal-debug.apk"
        )) {
            assertTrue(
                "The completeness gate must require $apk: $gate",
                gate.contains(apk)
            )
        }
        assertTrue(
            "The gate must be able to find the artifact the build job uploaded: $gate",
            gate.contains("app/build/outputs/apk/universal/app-arm64-v8a-universal-debug.apk")
        )
    }

    // ------------------------------------------------------------ the registry

    @Test
    fun `given the finalized contracts when read then C-03 and C-04 record the universal APK decision`() {
        val doc = repoFile("FINALIZED_CONTRACTS.md").readText()
        val c03 = doc.lineSequence().firstOrNull { it.startsWith("| C-03 |") } ?: ""
        val c04 = doc.lineSequence().firstOrNull { it.startsWith("| C-04 |") } ?: ""

        assertTrue(
            "C-03 must record the universal APK and why it exists (installable on-demand " +
                "feature outside Play): $c03",
            c03.contains("universal") && c03.contains("bundletool")
        )
        assertTrue(
            "C-03 must name this spec as a guard: $c03",
            c03.contains("UniversalApkDeliveryBddSpec.kt")
        )
        assertTrue(
            "C-04 must record the third, universal artifact: $c04",
            c04.contains("alphapulse-arm64-v8a-universal")
        )
        assertTrue(
            "C-04 must name this spec as a guard: $c04",
            c04.contains("UniversalApkDeliveryBddSpec.kt")
        )
    }

    @Test
    fun `given the preflight script when read then it fails a pipeline without the universal APK stage`() {
        val preflight = repoFile("scripts/check_ci_preflight.py").readText()

        assertTrue(
            "The cheap pipeline contract must check the universal stage, or it can disappear " +
                "without failing any gate: $preflight",
            preflight.contains("Build the universal APK from the verified bundle")
        )
        assertTrue(
            "The release gate's new name must be pinned by the preflight script too: $preflight",
            preflight.contains("Require every arm64-v8a APK before publishing")
        )
    }

    // ---------------------------------------------------------------- helpers

    private data class VerifierResult(val exitCode: Int, val output: String)

    /**
     * A minimal synthetic APK: a manifest, a dex file that either carries the
     * trader host descriptor, and one native library at [nativeEntry].
     */
    private fun fusedApk(nativeEntry: String, includeHostClass: Boolean = true): File {
        val apk = File.createTempFile("fused", ".apk")
        apk.deleteOnExit()
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("manifest".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("classes.dex"))
            val dex = buildString {
                append("dex\n035")
                append('\u0000')
                if (includeHostClass) {
                    append("Lcom/alphapulse/proactive/TraderHostActivity;")
                    append('\u0000')
                }
                append("Lcom/aistudio/alphapulse/MainActivity;")
                append('\u0000')
            }
            zip.write(dex.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(nativeEntry))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        return apk
    }

    private fun runVerifier(apk: File): VerifierResult {
        val process = ProcessBuilder("python3", verifier.absolutePath, apk.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return VerifierResult(exit, output)
    }

    /** The lines of one workflow step, from its name to the next step's name. */
    private fun stepBlock(name: String): String {
        val marker = "- name: $name"
        val start = workflow.indexOf(marker)
        if (start < 0) throw AssertionError("The workflow has no step named '$name'")
        val rest = workflow.substring(start + marker.length)
        val end = rest.indexOf("\n      - name:")
        return if (end < 0) rest else rest.substring(0, end)
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
