package com.aistudio.alphapulse.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the Robolectric `android-all` runtime cache, and the document that
 * says which `@Config` SDKs it covers.
 *
 * Why this spec exists
 * --------------------
 * Robolectric fetches `android-all-instrumented` from Maven Central *while the
 * tests run*, so those jars sit outside both Gradle's dependency cache and
 * setup-gradle's cache. A 429 from that host failed the API 34 leg after every
 * Gradle dependency had already resolved. The workflow now caches
 * `~/.m2/repository/org/robolectric` and prefetches the jars only on a cold
 * cache; this spec and `scripts/check_ci_preflight.py` both read the same
 * registry (`scripts/robolectric_runtime.json`), so the key, the prefetched
 * artifacts and the SDKs the suite actually pins cannot drift apart.
 *
 * The scenarios below cover:
 *
 *  - the cache key names the Robolectric version and every SDK it covers, so an
 *    SDK or version bump without a new key is a failure rather than a stale
 *    restore;
 *  - the registry agrees with the pinned Robolectric version in
 *    `gradle/libs.versions.toml`;
 *  - every SDK the suite pins with `@Config(sdk = ...)` - plus the module's
 *    `targetSdk`, which Robolectric uses when a spec pins nothing - has a
 *    prefetched runtime artifact;
 *  - the workflow still caches that directory under that key, and still fetches
 *    each registry artifact only when the cache missed.
 *
 * Spec Ref: §52 Testing Requirements, §63 Definition of Done, C-28
 */
class RobolectricRuntimeCacheBddSpec {

    private val registry: RuntimeRegistry by lazy {
        RuntimeRegistry.parse(
            repoFile("scripts/robolectric_runtime.json").readText(Charsets.UTF_8)
        )
    }

    // ------------------------------------------------------------ the shared key

    @Test
    fun `given the runtime registry when read then the cache key names the version and every SDK it covers`() {
        val sdks = registry.sdkArtifacts.keys.sortedBy(String::toInt).joinToString("-")

        assertEquals(
            "scripts/robolectric_runtime.json must name a real SDK set; a key with no " +
                "artifacts would cache nothing",
            false,
            registry.sdkArtifacts.isEmpty()
        )
        assertTrue(
            "The cache key '${registry.cacheKey}' does not name Robolectric " +
                "${registry.version} and its covered SDKs (sdk$sdks). An SDK or version " +
                "bump must change the key, otherwise a cold runner restores the old jars, " +
                "downloads the new one mid-test, and the download flake is back.",
            Regex(
                "^robolectric-android-all-${Regex.escape(registry.version)}" +
                    "-sdk${Regex.escape(sdks)}-v\\d+$"
            ).matches(registry.cacheKey)
        )
    }

    @Test
    fun `given the pinned Robolectric dependency when the registry is read then both name the same version`() {
        val toml = repoFile("gradle/libs.versions.toml").readText(Charsets.UTF_8)
        val pinned = Regex("""(?m)^robolectric\s*=\s*"([^"]+)"""")
            .find(toml)?.groupValues?.get(1)
            ?: throw AssertionError("gradle/libs.versions.toml no longer pins Robolectric")

        assertEquals(
            "Robolectric is pinned at $pinned but the runtime registry documents " +
                "${registry.version}. Robolectric names its android-all jars per version, so " +
                "a version bump must also bump the cache key.",
            pinned,
            registry.version
        )
    }

    // -------------------------------------------------- which SDKs it must cover

    @Test
    fun `given every spec when it pins an SDK then that SDK has a cached runtime artifact`() {
        val used = pinnedSdks()
        assertTrue(
            "This scanner found no SDK at all, so it proves nothing about coverage",
            used.isNotEmpty()
        )

        val uncovered = used - registry.sdkArtifacts.keys
        assertTrue(
            "The suite runs Robolectric on SDK $uncovered, but " +
                "scripts/robolectric_runtime.json has no android-all artifact for it. Add the " +
                "artifact, the cold-cache prefetch entry and a new cache key in the same " +
                "change, or the first runner that reaches that SDK downloads it mid-test and " +
                "the 429 flake is back. Covered today: " +
                registry.sdkArtifacts.entries.joinToString { "${it.key} -> ${it.value}" },
            uncovered.isEmpty()
        )
    }

    @Test
    fun `given a covered SDK when the registry is read then its artifact is a pinned android-all jar`() {
        for ((sdk, artifact) in registry.sdkArtifacts) {
            assertTrue(
                "SDK $sdk is mapped to '$artifact', which is not a pinned Robolectric " +
                    "android-all-instrumented artifact name. A floating or malformed name " +
                    "makes the cache key point at nothing.",
                Regex("^\\d+-robolectric-\\d+-i\\d+$").matches(artifact)
            )
        }
    }

    // ------------------------------------------------------- the workflow wiring

    @Test
    fun `given the test job when the cache is cold then it caches and prefetches exactly the registry artifacts`() {
        val testJob = jobBlock("test:")

        assertTrue(
            "The test job no longer restores the Robolectric runtime cache under " +
                "`id: robolectric-cache`; every run would download the android-all jars again",
            testJob.contains("id: robolectric-cache")
        )
        assertTrue(
            "The test job no longer caches ~/.m2/repository/org/robolectric, so the " +
                "android-all jars are outside every cache again: $testJob",
            testJob.contains("path: ~/.m2/repository/org/robolectric")
        )
        assertTrue(
            "The test job's cache key is not the one scripts/robolectric_runtime.json " +
                "documents ('${registry.cacheKey}'), so the spec no longer describes what CI " +
                "actually caches: $testJob",
            testJob.contains("key: ${registry.cacheKey}")
        )
        assertTrue(
            "The prefetch must stay gated on a cache miss; an unconditional prefetch pays " +
                "the download on every run and defeats the cache",
            testJob.contains("steps.robolectric-cache.outputs.cache-hit != 'true'")
        )
        for ((sdk, artifact) in registry.sdkArtifacts) {
            assertTrue(
                "SDK $sdk is documented as cached, but the cold-cache prefetch never " +
                    "fetches $artifact, so a cold runner leans on Robolectric's own fetch",
                testJob.contains("fetch $artifact")
            )
            assertTrue(
                "SDK $sdk's prefetch does not build the Maven filename " +
                    "android-all-instrumented-$artifact; writing <version>.jar 404s, so the " +
                    "step warns and prefetches nothing",
                testJob.contains("android-all-instrumented-\$artifact")
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Every SDK the suite asks Robolectric to run on. */
    private fun pinnedSdks(): Set<String> {
        val sdks = linkedSetOf<String>()
        repoFile("app/src/test").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText(Charsets.UTF_8)
                for (args in Regex("""@Config\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(text).map { it.groupValues[1] }) {
                    Regex("""sdk\s*=\s*\[([^\]]*)\]""").findAll(args)
                        .forEach { match -> sdks += Regex("""\d+""").findAll(match.groupValues[1]).map { it.value } }
                    Regex("""sdk\s*=\s*(\d+)""").findAll(args).forEach { sdks += it.groupValues[1] }
                }
            }
        // Robolectric falls back to targetSdk for a spec that pins nothing, so that
        // SDK is part of the covered set whether or not an annotation names it.
        val appBuild = repoFile("app/build.gradle.kts").readText(Charsets.UTF_8)
        Regex("""(?m)^\s*targetSdk\s*=\s*(\d+)""").find(appBuild)?.let { sdks += it.groupValues[1] }
        return sdks
    }

    /** The whole job block that starts with `key` (e.g. `test:`). */
    private fun jobBlock(key: String): String {
        val workflow = repoFile(".github/workflows/build-apk.yml").readText(Charsets.UTF_8)
        val start = workflow.indexOf("\n  $key")
        if (start < 0) throw AssertionError("The workflow has no '$key' job")
        val rest = workflow.substring(start + 1)
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

    /** The registry both this spec and the preflight script read. */
    private data class RuntimeRegistry(
        val version: String,
        val cacheKey: String,
        val sdkArtifacts: Map<String, String>
    ) {
        companion object {
            fun parse(json: String): RuntimeRegistry {
                fun field(name: String): String =
                    Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                        ?: throw AssertionError(
                            "scripts/robolectric_runtime.json has no '$name'; the preflight " +
                                "gate and this spec both depend on it"
                        )

                val body = Regex("\"sdk_artifacts\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
                    .find(json)?.groupValues?.get(1)
                    ?: throw AssertionError(
                        "scripts/robolectric_runtime.json has no 'sdk_artifacts' map, so " +
                            "nothing documents which @Config SDKs the cache covers"
                    )
                val artifacts = Regex("\"(\\d+)\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                    .associate { it.groupValues[1] to it.groupValues[2] }

                return RuntimeRegistry(
                    version = field("robolectric_version"),
                    cacheKey = field("cache_key"),
                    sdkArtifacts = artifacts
                )
            }
        }
    }
}
