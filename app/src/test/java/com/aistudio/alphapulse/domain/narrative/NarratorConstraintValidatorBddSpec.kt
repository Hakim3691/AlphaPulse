package com.aistudio.alphapulse.domain.narrative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the narrator guardrails, specified as Given/When/Then.
 *
 * §59 draws a hard line: the narrator may explain, summarise, diagnose and
 * compare, but it may never invent a number, rewrite a model output or produce
 * an unsupported probability. These tests are the executable form of that rule -
 * if someone later loosens a validator, one of them fails.
 *
 * Spec Ref: §59 AI Narrator Requirements
 */
class NarratorConstraintValidatorBddSpec {

    // ------------------------------------------------- numerical claims (§59)

    @Test
    fun `given a claim that matches its source value when validated then it is accepted`() {
        assertTrue(
            NarratorConstraintValidator.validateNumericalClaim("87.0", sourceValue = 87.0)
        )
    }

    @Test
    fun `given a claim with a percent sign that matches its source when validated then it is accepted`() {
        assertTrue(
            NarratorConstraintValidator.validateNumericalClaim("91%", sourceValue = 91.0)
        )
    }

    @Test
    fun `given a claim with no source value when validated then it is rejected because nothing can be invented`() {
        assertFalse(
            "A number with no origin in the engine state must never be published",
            NarratorConstraintValidator.validateNumericalClaim("87.0", sourceValue = null)
        )
    }

    @Test
    fun `given a claim that differs from its source beyond tolerance when validated then it is rejected`() {
        assertFalse(
            NarratorConstraintValidator.validateNumericalClaim(
                "99.0",
                sourceValue = 87.0,
                tolerance = 0.01
            )
        )
    }

    @Test
    fun `given a claim that is not a number at all when validated then it is rejected`() {
        assertFalse(
            NarratorConstraintValidator.validateNumericalClaim("a lot", sourceValue = 87.0)
        )
    }

    @Test
    fun `given a small rounding difference within tolerance when validated then it is accepted`() {
        assertTrue(
            NarratorConstraintValidator.validateNumericalClaim(
                "87.005",
                sourceValue = 87.0,
                tolerance = 0.01
            )
        )
    }

    // --------------------------------------------- no output rewriting (§59)

    @Test
    fun `given a narrative that rewrites a model output when checked then it is flagged unsafe`() {
        listOf(
            "Confidence changed to 95%",
            "The model was modified",
            "Weights adjusted upward",
            "Forecast altered by the narrator",
            "LONG instead of NEUTRAL",
            "Signal replaced by a stronger one",
            "The verdict was overridden"
        ).forEach { phrase ->
            assertFalse(
                "'$phrase' must be rejected as an output rewrite",
                NarratorConstraintValidator.isOutputSafe(phrase, emptyMap())
            )
        }
    }

    @Test
    fun `given a narrative that only explains when checked then it is considered safe`() {
        assertTrue(
            NarratorConstraintValidator.isOutputSafe(
                "Momentum is expanding while liquidity sits on the upper shelf.",
                mapOf("confidence" to 0.91)
            )
        )
    }

    // ----------------------------------------------- no invented data (§59)

    @Test
    fun `given a narrative that hedges with approximate wording when checked then invented data is detected`() {
        listOf(
            "Confidence is approximately 90%",
            "About 12 trades were blocked",
            "Roughly half the book is bid-side",
            "Estimated volume of 3M",
            "It generates a strong signal",
            "This creates a new probability",
            "The engine produces 87% confidence",
            "It yields a 12% edge"
        ).forEach { phrase ->
            assertTrue(
                "'$phrase' must be flagged as invented data",
                NarratorConstraintValidator.containsInventedData(phrase)
            )
        }
    }

    @Test
    fun `given a narrative quoting exact engine figures when checked then no invention is flagged`() {
        assertFalse(
            NarratorConstraintValidator.containsInventedData(
                "Confidence 91%. Cone hit rate 72.5%. Regime MOMENTUM_BURST."
            )
        )
    }
}
