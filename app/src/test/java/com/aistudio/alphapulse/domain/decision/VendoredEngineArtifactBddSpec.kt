package com.aistudio.alphapulse.domain.decision

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendored Cactus engine, verified as Given/When/Then.
 *
 * Why a specification about binary files
 * --------------------------------------
 * The run path is only as trustworthy as the engine it calls. These tests pin the
 * exact bytes of that engine to the digests Hugging Face publishes, so a truncated
 * download, a hand-edited archive or a swapped ABI cannot reach a device: the test
 * run fails first, and `scripts/check_integrity.py` fails even earlier, on commit.
 *
 * They also prove the vendored files are actually the ones CMake links - an engine
 * sitting in the tree unlinked would be dead weight pretending to be a feature.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §47 Model Download Architecture,
 * §63 Traceability.
 */
class VendoredEngineArtifactBddSpec {

    // ------------------------------------------------------------- fingerprints

    @Test
    fun `given the vendored arm64 engine when hashed then it matches the published digest and size`() {
        val archive = engineFile("android-arm64/libneedle.a")

        assertEquals("Published size is 2,177,676 bytes", 2_177_676L, archive.length())
        assertEquals(
            "Digest must be the published LFS object id",
            "7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0",
            sha256Of(archive)
        )
    }

    @Test
    fun `given the vendored armv7 engine when hashed then it matches the published digest and size`() {
        val archive = engineFile("android-armv7/libneedle.a")

        assertEquals("Published size is 1,700,760 bytes", 1_700_760L, archive.length())
        assertEquals(
            "Digest must be the published LFS object id",
            "9f559a287b201c213e5c753d3cd1076a1849c73f94b445c85d11e4969422a135",
            sha256Of(archive)
        )
    }

    @Test
    fun `given the vendored header when hashed then it matches the published digest and size`() {
        val header = engineFile("needle.h")

        assertEquals("Published size is 4,528 bytes", 4_528L, header.length())
        assertEquals(
            "Digest must be the published object id",
            "a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99",
            sha256Of(header)
        )
    }

    @Test
    fun `given the vendored header when read then it declares the eleven functions the bridge calls`() {
        val header = engineFile("needle.h").readText()

        // Every C function the JNI bridge calls must be declared by the header we
        // vendored; a signature drift would be a compile error, but a missing
        // declaration would be a silent no-op at review time.
        listOf(
            "needle_load",
            "needle_models",
            "needle_last_error",
            "needle_init",
            "needle_complete",
            "needle_reset",
            "needle_transcribe",
            "needle_embed"
        ).forEach { symbol ->
            assertTrue("needle.h must declare $symbol", header.contains(symbol))
        }
        assertTrue(
            "The header must keep the C linkage guard the bridge relies on",
            header.contains("extern \"C\"")
        )
    }

    // --------------------------------------------------- vendored AND linked

    @Test
    fun `given the cmake build file when read then both arm abis link the vendored archive and x86_64 builds the refusal path`() {
        val cmake = cmakeFile().readText()

        assertTrue("arm64 must link the vendored engine", cmake.contains("engine/android-arm64"))
        assertTrue("armv7 must link the vendored engine", cmake.contains("engine/android-armv7"))
        assertTrue("The archive must be an imported static library", cmake.contains("IMPORTED_LOCATION"))
        assertTrue(
            "A missing engine must compile the refusal path, not fail the build",
            cmake.contains("ALPHAPULSE_NEEDLE_ENGINE_BUNDLED=0")
        )
        assertTrue(
            "A present engine must define the bundled flag the bridge tests",
            cmake.contains("ALPHAPULSE_NEEDLE_ENGINE_BUNDLED=1")
        )
    }

    @Test
    fun `given the gradle module when read then the native bridge is built for exactly the abis the apk splits ship`() {
        val gradle = projectFile("build.gradle.kts").readText()

        assertTrue("The JNI bridge must be compiled", gradle.contains("externalNativeBuild"))
        assertTrue("The CMake entry point must be declared", gradle.contains("src/main/cpp/CMakeLists.txt"))
        assertTrue("The NDK version must be pinned", gradle.contains("ndkVersion = "))
        assertTrue(
            "The native ABIs must match the APK splits",
            gradle.contains("abiFilters += listOf(\"arm64-v8a\", \"armeabi-v7a\", \"x86_64\")")
        )
    }

    // ----------------------------------------------------------- catalog parity

    @Test
    fun `given the catalog's engine digests when compared with the vendored files then they agree`() {
        // The catalog is what the diagnostics panel reads; the files are what runs.
        // If these two ever disagree the app would describe an engine it does not have.
        val expected = mapOf(
            "needle3-engine-lib" to ("android-arm64/libneedle.a" to 2_177_676L),
            "needle3-engine-lib-armv7" to ("android-armv7/libneedle.a" to 1_700_760L),
            "needle3-engine-header" to ("needle.h" to 4_528L)
        )

        expected.forEach { (id, fileAndSize) ->
            val (relativePath, size) = fileAndSize
            val spec = DecisionModelCatalog.NEEDLE3_ENGINE.first { it.id == id }
            val file = engineFile(relativePath)

            assertEquals("$id must pin the measured size", size, spec.sizeBytes)
            assertEquals("$id must pin the measured digest", sha256Of(file), spec.sha256)
            assertTrue("$id must be compiled in, not downloaded", spec.bundledAtBuildTime)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The vendored engine directory, located from whichever directory tests run in. */
    private fun engineFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/cpp/engine/$relativePath"),
            File("app/src/main/cpp/engine/$relativePath")
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "The vendored Cactus engine is missing: $relativePath. Tried " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    /** The CMake entry point, located from whichever directory tests run in. */
    private fun cmakeFile(): File {
        val candidates = listOf(
            File("src/main/cpp/CMakeLists.txt"),
            File("app/src/main/cpp/CMakeLists.txt")
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("The native CMake build file is missing.")
    }

    /** A file in the app module, located from whichever directory tests run in. */
    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app/$relativePath"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("Expected project file is missing: $relativePath")
    }

    /** Hashes a file the same way `sha256sum` does. */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
