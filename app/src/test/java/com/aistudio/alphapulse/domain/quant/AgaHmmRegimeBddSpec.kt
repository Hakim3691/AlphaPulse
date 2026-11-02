package com.aistudio.alphapulse.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13 / Unified Spec Part III §10 — AGA-HMM regime-classification upgrade.
 *
 * The production implementation lives in `domain/quant/QuantMath.kt`
 * (`evaluateMarkovRegimeGARCH`). This BDD spec drives it, so weakening the
 * fat-tail escalation rule or the mean-reversion fallback breaks the build.
 *
 * The same behaviour is also covered by `QuantMathTest.kt` (unit tests), but
 * §13 requires a dedicated BDD guard so the AGA-HMM contract is visible in the
 * spec registry independently of the broader quant-number tests.
 *
 * Spec Ref: §13 (AGA-HMM Upgrade), Part III §10
 */
class AgaHmmRegimeBddSpec {

    private fun flatTape(length: Int) = List(length) { 0.0 }
    private fun fatTailTape() = List(16) { 0.0 } + List(4) { 0.5 }

    @Test
    fun `given a flat return tape when the AGA-HMM regime is evaluated then it stays in mean reversion`() {
        val result = QuantMath.evaluateMarkovRegimeGARCH(
            returns = flatTape(15),
            kurtosisHistory = mutableListOf(),
            state1Returns = mutableListOf(),
            state0Returns = mutableListOf()
        )

        assertTrue(result.regime.startsWith("State 0"))
        assertEquals(0.85, result.regimeVolScale, 1e-12)
        assertEquals(0.0, result.muKinematic, 1e-12)
    }

    @Test
    fun `given insufficient history when the AGA-HMM regime is evaluated then it stays in mean reversion with zero drift`() {
        val result = QuantMath.evaluateMarkovRegimeGARCH(
            returns = List(5) { 0.01 },
            kurtosisHistory = mutableListOf(),
            state1Returns = mutableListOf(),
            state0Returns = mutableListOf()
        )

        assertTrue(result.regime.startsWith("State 0"))
        assertEquals(0.0, result.muKinematic, 1e-12)
    }

    @Test
    fun `given fat-tailed returns when the AGA-HMM regime is evaluated then it escalates to a momentum burst`() {
        val result = QuantMath.evaluateMarkovRegimeGARCH(
            returns = fatTailTape(),
            kurtosisHistory = mutableListOf(),
            state1Returns = mutableListOf(),
            state0Returns = mutableListOf()
        )

        assertTrue(result.regime.startsWith("State 1"))
        assertTrue(result.kurtosis > 3.0)
        assertTrue(result.regimeVolScale > 1.0)
    }

    @Test
    fun `given a regime shift when the AGA-HMM is evaluated then the kinematic drift reflects the burst`() {
        val result = QuantMath.evaluateMarkovRegimeGARCH(
            returns = fatTailTape(),
            kurtosisHistory = mutableListOf(),
            state1Returns = mutableListOf(),
            state0Returns = mutableListOf()
        )

        assertTrue("Momentum burst must produce non-zero kinematic drift", result.muKinematic != 0.0)
    }
}
