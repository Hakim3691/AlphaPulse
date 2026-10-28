package com.aistudio.alphapulse.build

import com.aistudio.alphapulse.data.model.ModelArtifacts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the guards: `FINALIZED_CONTRACTS.md` cannot drift away from the
 * tests and the code that lock each decision in place.
 *
 * Why this spec exists
 * --------------------
 * Behaviour regressed repeatedly because the only record of "we already decided
 * this" was prose: Decision-1 was documented as removed while its files stayed,
 * the README still described per-ABI APKs after the build went arm64-only, and
 * the weights pipeline was re-broken by build edits. Each of those was caught by
 * a test *after* the fact. This spec closes the loop from the other direction:
 * it reads the registry, and fails when
 *
 *  - a contract row is deleted or renumbered,
 *  - a guard spec named in a row is missing, empty or has no BDD scenario,
 *  - the pinned checkpoint recorded in the doc differs from the code's pin,
 *  - a removed component is reintroduced into production sources or the README.
 *
 * It asserts structure and consistency, not behaviour: the behaviour itself is
 * the job of the guard specs each row names.
 *
 * Spec Ref: §52 Testing Requirements, §63 Definition of Done
 */
class FinalizedContractGuardBddSpec {

    private data class Contract(
        val id: String,
        val guards: String,
        val line: String
    )

    private val doc: String by lazy { repoFile("FINALIZED_CONTRACTS.md").readText() }
    private val contracts: List<Contract> by lazy { parseContracts() }

    // ------------------------------------------------------------ the registry

    @Test
    fun `given the finalized contracts doc when read then every contract is present and names a guard scenario`() {
        val expected = (1..16).map { "C-%02d".format(it) }.toSet()
        assertEquals(
            "A contract row was deleted or renumbered. Removing a finalized decision is a " +
                "deliberate commit that updates its guard scenario and this registry, never " +
                "a silent row deletion",
            expected,
            contracts.map { it.id }.toSet()
        )

        val specs = testSources()
        for (contract in contracts) {
            val guards = guardFiles(contract.guards)
            assertTrue(
                "${contract.id} must name at least one guard (a spec or the integrity script) " +
                    "in its row: ${contract.line}",
                guards.isNotEmpty()
            )
            for (guard in guards) {
                // A `.py` guard is a repository check (the traceability registry is
                // enforced by scripts/check_integrity.py), so it is only required to
                // exist; Kotlin guards must be real specs with scenarios.
                if (guard.endsWith(".py")) {
                    repoFile(guard)
                    continue
                }
                val file = specs[guard.substringAfterLast('/')]
                    ?: throw AssertionError(
                        "${contract.id} names '$guard', which is not a spec under " +
                            "app/src/test/java. A finalized contract cannot point at a test " +
                            "that does not exist."
                    )
                val text = file.readText()
                assertTrue("${contract.id}: $guard has no @Test", text.contains("@Test"))
                // Behaviour contracts are guarded by BDD scenarios; a pure-function
                // contract may be guarded by an ordinary unit test with descriptive
                // backticked names, which is what FormattersTest uses.
                if (guard.endsWith("BddSpec.kt")) {
                    assertTrue(
                        "${contract.id}: $guard must state a given/when/then scenario, or it " +
                            "cannot be the guard for a finalized behaviour",
                        text.contains("when ") && text.contains("then ")
                    )
                }
            }
        }

        // The registry is checked by the repository guard too, so a doc-only edit
        // that breaks it is caught by CI's integrity job and the pre-commit hook.
        assertTrue(
            "scripts/check_integrity.py must keep checking this registry",
            repoFile("scripts/check_integrity.py").readText().contains("FINALIZED_CONTRACTS.md")
        )
    }

    // -------------------------------------------------------------- pinned pins

    @Test
    fun `given the doc when read then the recorded checkpoint pin is the one the code verifies`() {
        val needle = ModelArtifacts.NEEDLE3

        assertTrue(
            "The registry must record the pinned size (${needle.sizeBytes}); a doc that " +
                "quotes a different checkpoint is how a wrong asset gets shipped",
            doc.contains("%,d".format(needle.sizeBytes))
        )
        assertTrue(
            "The registry must record the pinned SHA-256 (${needle.sha256})",
            doc.contains(needle.sha256)
        )
        assertTrue(
            "The registry must record that only arm64-v8a ships",
            doc.contains("Only `arm64-v8a` is built and shipped")
        )
    }

    // ------------------------------------------------------- removed behaviour

    @Test
    fun `given the production sources when scanned then the removed remote decision router is still gone`() {
        val sources = repoFile("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

        for (removed in listOf("Decision1ApiRouter", "Decision1Configuration", "Decision1StatusBanner")) {
            val offenders = sources.filter { it.readText().contains(removed) }
            assertTrue(
                "C-01/C-16: '$removed' was removed from the product and must not return to " +
                    "production sources. Found in: ${offenders.joinToString { it.name }}",
                offenders.isEmpty()
            )
        }

        assertFalse(
            "C-16: the README must not advertise per-ABI or multi-ABI APKs again",
            repoFile("README.md").readText().contains("one per ABI")
        )
    }

    // ------------------------------------------------ the change process in CI

    @Test
    fun `given the workflow when read then the documented CI gates are the ones that run`() {
        val workflow = repoFile(".github/workflows/build-apk.yml").readText()

        for (gate in listOf(":app:testDebugUnitTest", "scripts/check_integrity.py", ":app:verifyPackagedWeights")) {
            assertTrue(
                "The change process in FINALIZED_CONTRACTS.md names '$gate' as a gate, so the " +
                    "workflow must actually run it",
                workflow.contains(gate)
            )
        }
        assertTrue(
            "The release job must still refuse to publish unverified artifacts",
            workflow.contains("Refuse to publish unverified artifacts")
        )
        assertTrue(
            "The APK the release attaches must stay the arm64-v8a artifact pattern",
            workflow.contains("alphapulse-arm64-v8a-")
        )
    }

    // --------------------------------------------- contributor instructions

    @Test
    fun `given the contributor instructions when read then the pull request template and agent guide keep every required item`() {
        val template = repoFile(".github/pull_request_template.md").readText()
        for (required in listOf(
            "BDD checklist",
            "*BddSpec.kt",
            "FINALIZED_CONTRACTS.md",
            "./gradlew :app:testDebugUnitTest",
            "scripts/check_integrity.py",
            ":app:assembleDebug",
            ":app:verifyPackagedWeights",
            "weakened"
        )) {
            assertTrue(
                "The pull request template lost the required item '$required'. Removing a " +
                    "checklist item is how the BDD/CI discipline quietly disappears",
                template.contains(required)
            )
        }

        val agents = repoFile("AGENTS.md").readText()
        for (required in listOf(
            "BDD scenario first",
            "FINALIZED_CONTRACTS.md",
            ":app:testDebugUnitTest",
            "check_integrity.py",
            "guard spec"
        )) {
            assertTrue(
                "AGENTS.md lost the required instruction '$required' - agents would have to " +
                    "be reminded of the process again",
                agents.contains(required)
            )
        }

        assertTrue(
            "README.md must point contributors and agents at AGENTS.md",
            repoFile("README.md").readText().contains("AGENTS.md")
        )
        assertTrue(
            "scripts/check_integrity.py must pin these files too, or CI cannot catch their loss",
            repoFile("scripts/check_integrity.py").readText().contains("AGENTS.md")
        )
    }

    // ----------------------------------------------------------- release gate

    @Test
    fun `given the workflow when read then the release job only publishes on a version tag`() {
        val workflow = repoFile(".github/workflows/build-apk.yml").readText()
        val start = workflow.indexOf("  release:")
        assertTrue("The release job must exist", start >= 0)
        val block = workflow.substring(start).substringBefore("  model-artifacts:")

        assertTrue(
            "The release job must be gated on a v* tag: $block",
            block.contains("startsWith(github.ref, 'refs/tags/v')")
        )
        assertFalse(
            "A branch push has no tag to publish, so action-gh-release fails with 'GitHub " +
                "Releases requires a tag' and every main push turns red after its gates " +
                "passed: $block",
            block.contains("refs/heads/main")
        )
    }

    // ---------------------------------------------------- release completeness

    @Test
    fun `given the workflow when read then a tag builds both APKs and no upload may silently skip`() {
        val workflow = repoFile(".github/workflows/build-apk.yml").readText()

        assertTrue(
            "A tag must build both the debug and the release APK; building only the " +
                "release variant published releases with a single asset",
            Regex("""refs/tags/\*[^\n]*then[\s\S]{0,300}BUILD_TYPE="both""")
                .containsMatchIn(workflow)
        )
        // Scoped to the APK uploads: the coverage and contract-report uploads are
        // legitimately optional, but an APK that is not produced must fail the job.
        for (step in listOf("Upload debug APK (arm64-v8a)", "Upload release APK (arm64-v8a)")) {
            val start = workflow.indexOf("- name: $step")
            assertTrue("The workflow must still have the '$step' step", start >= 0)
            val block = workflow.substring(start).substringBefore("\n      - name:")
            assertTrue(
                "$step must fail the job when its APK is missing: $block",
                block.contains("if-no-files-found: error")
            )
            assertFalse(
                "$step must not ignore a missing APK - that is how a release shipped " +
                    "with only one asset: $block",
                block.contains("if-no-files-found: ignore")
            )
        }

        val gate = workflow.indexOf("Require both arm64-v8a APKs before publishing")
        assertTrue("The release job must refuse a release with only one APK", gate >= 0)
        assertTrue(
            "The completeness gate must run before the release is published",
            gate < workflow.indexOf("Publish Release with APK Assets")
        )
        val gateBlock = workflow.substring(gate).substringBefore("      - name: Publish Release with APK Assets")
        for (apk in listOf("app-arm64-v8a-debug.apk", "app-arm64-v8a-release.apk")) {
            assertTrue("The completeness gate must require $apk: $gateBlock", gateBlock.contains(apk))
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Contract rows, in document order: `| C-xx | behaviour | guards | ... |`. */
    private fun parseContracts(): List<Contract> {
        val rows = doc.lines().filter { Regex("""^\|\s*C-\d{2}\s*\|""").containsMatchIn(it) }
        val parsed = rows.map { row ->
            val cells = row.split('|')
            // cells[0] is empty (leading pipe); id is 1, behaviour 2, guards 3.
            require(cells.size >= 5) { "Malformed contract row: $row" }
            Contract(id = cells[1].trim(), guards = cells[3].trim(), line = row)
        }
        assertEquals("Contract ids must be unique", parsed.size, parsed.map { it.id }.toSet().size)
        return parsed
    }

    /** Backticked spec or repository-check references in a guard cell. */
    private fun guardFiles(cell: String): List<String> =
        Regex("`([^`]+\\.(?:kt|py))`").findAll(cell).map { it.groupValues[1] }.toList()

    /** Every JVM spec in the unit-test source set, keyed by file name. */
    private fun testSources(): Map<String, File> =
        repoFile("app/src/test/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .associateBy { it.name }

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
