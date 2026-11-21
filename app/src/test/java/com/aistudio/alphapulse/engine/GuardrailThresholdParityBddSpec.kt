package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.engine.proactive.ProductionExecutionConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §33 / §34 — the guardrails that actually run must be the values §34 publishes.
 *
 * Two divergences were live at once: the spread guard used an 8 bps base instead of the
 * configured 0.10% ceiling, and the hard-flatten cooldown was hard-coded to 15 minutes in
 * both `InvalidationEngine` and `MainViewModel` while §34 specifies 30. A configuration
 * the call sites ignore is documentation, not a guardrail.
 *
 * Spec Ref: Phase 2 §33 Operational Guardrails, §34 Production Configuration, C-45
 */
class GuardrailThresholdParityBddSpec {

    private val config = ProductionExecutionConfig.PRODUCTION

    @Test
    fun `given the §34 spread ceiling when a compliant book is judged then it is not a blowout`() {
        assertFalse(
            "0.09% is inside the 0.10% ceiling and must not suppress passive staging",
            ExecutionGuardrails.isSpreadBlowout(
                spreadPct = 0.09,
                atr5m = 0.0,
                lastPrice = 50_000.0,
                config = config
            )
        )
    }

    @Test
    fun `given the §34 spread ceiling when the book is wider than the ceiling then it is a blowout`() {
        assertTrue(
            "0.11% is outside the 0.10% ceiling",
            ExecutionGuardrails.isSpreadBlowout(
                spreadPct = 0.11,
                atr5m = 0.0,
                lastPrice = 50_000.0,
                config = config
            )
        )
    }

    @Test
    fun `given a volatile asset when the ceiling widens then it never exceeds the §33 0_12 percent maximum`() {
        val ceiling = ExecutionGuardrails.spreadCeilingPct(
            atr5m = 500.0,
            lastPrice = 1_000.0,
            config = config
        )

        assertEquals(0.12, ceiling, 0.0001)
        assertFalse(
            "0.119% is still inside the widened ceiling",
            ExecutionGuardrails.isSpreadBlowout(0.119, 500.0, 1_000.0, config)
        )
        assertTrue(
            "0.13% is outside it",
            ExecutionGuardrails.isSpreadBlowout(0.13, 500.0, 1_000.0, config)
        )
    }

    @Test
    fun `given a hard flatten when the engine arms the re-entry lockout then it uses the §34 30 minutes not the old 15`() {
        val engine = InvalidationEngine(config)
        val now = 1_700_000_000_000L

        assertEquals(
            now + 30 * 60_000L,
            engine.cooldownDeadline(now)
        )
        assertEquals(30, config.reentryCooldownMinutes)
    }

    @Test
    fun `given the production sources when read then no call site keeps its own cooldown literal`() {
        val engine = repoFile("app/src/main/java/com/aistudio/alphapulse/engine/InvalidationEngine.kt")
            .readText()
        val viewModel = repoFile("app/src/main/java/com/aistudio/alphapulse/ui/MainViewModel.kt")
            .readText()

        assertFalse(
            "InvalidationEngine must not hard-code a 15-minute lockout",
            engine.contains("15 * 60 * 1000")
        )
        assertFalse(
            "MainViewModel must not hard-code a 15-minute lockout",
            viewModel.contains("15 * 60 * 1000")
        )
        assertTrue(
            "The engine owns the cooldown through the configuration",
            engine.contains("reentryCooldownMillis")
        )
        assertTrue(
            "The view model arms the lockout from the configuration",
            viewModel.contains("cooldownDeadline")
        )
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
