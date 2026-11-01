package com.aistudio.alphapulse.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §70 / Contract C-21 — the 8-configuration walk-forward matrix, its 21 metrics, the
 * no-look-ahead guard and the plateau-based promotion rule.
 *
 * The matrix and the profiler live in production
 * (`domain/validation/ProactiveWalkForward.kt`), and leakage is enforced by the one
 * existing [LeakageGuard] rather than a second copy, so a weakened leak check fails here.
 *
 * Spec Ref: §70, §50, §51, C-21
 */
class ProactiveWalkForwardBddSpec {

    private fun fold(
        config: WalkForwardConfigId,
        netPnlR: Double,
        expectancy: Double,
        sharpe: Double,
        savedMinus2RPct: Double
    ) = WalkForwardReport21Metrics(
        configId = config,
        netPnlR = netPnlR,
        expectancyPerTradeR = expectancy,
        profitFactor = 1.65,
        winRate = 0.58,
        averageWinR = 1.25,
        averageLossR = -0.48,
        maxDrawdownR = 3.2,
        sharpeRatio = sharpe,
        calmarRatio = 2.1,
        totalTrades = 40,
        proactiveExitCount = 12,
        proactiveExitPct = 30.0,
        avgRAtProactiveExit = -0.42,
        pctProactiveExitsSubsequentlyReachedMinus2R = savedMinus2RPct,
        pctProactiveExitsSubsequentlyRecoveredToProfit = 14.0,
        missedProfitOpportunityCostR = 0.18,
        avgAdverseExcursionMaeR = 0.55,
        avgFavorableExcursionMfeR = 1.40,
        hardStopFrequencyPct = 4.5,
        maxConsecutiveLosses = 3,
        oosDegradationVsTrainPct = 6.0
    )

    @Test
    fun `given the walk forward sweep when inspected then all 8 required configurations exist and every one keeps the 2_00R hard stop`() {
        val configs = WalkForwardConfigId.values().toList()

        assertEquals(8, configs.size)
        configs.forEach { config ->
            assertEquals("${config.label} must keep the fixed backstop", 2.00, config.hardStopR, 1e-9)
        }

        // Only the six swept candidates may become the production threshold.
        assertEquals(6, WalkForwardConfigId.SWEPT.size)
        assertTrue(WalkForwardConfigId.BASELINE !in WalkForwardConfigId.SWEPT)
        assertTrue(WalkForwardConfigId.HARD_STOP_ONLY !in WalkForwardConfigId.SWEPT)
        assertEquals(
            listOf(0.25, 0.50, 0.75, 1.00, 1.25, 1.50).sorted(),
            WalkForwardConfigId.SWEPT.mapNotNull { it.proactiveThresholdR }.sorted()
        )
    }

    @Test
    fun `given a future cone breach or future SGD ranking after the decision timestamp when the fold is validated then the leak is rejected and named`() {
        val decisionTime = 1_762_000_000_000L
        val contaminated = listOf(
            TimedObservation(DataStream.CANDLES, decisionTime - 60_000L),
            TimedObservation(DataStream.ENSEMBLE_WEIGHTS, decisionTime + 60_000L, "SGD rank"),
            TimedObservation(DataStream.MODEL_CALIBRATION, decisionTime + 120_000L)
        )

        var leak: LeakageGuard.LeakageException? = null
        try {
            LeakageGuard.requireNoLeakage(contaminated, decisionTime)
        } catch (expected: LeakageGuard.LeakageException) {
            leak = expected
        }

        assertTrue("A future observation must fail the fold", leak != null)
        assertTrue(
            "The failure must name the stream that leaked: ${leak!!.message}",
            leak.message!!.contains("ensemble weights")
        )

        // Trimmed to the decision moment, the same inputs are clean: that is the shape
        // a backtest is supposed to walk through.
        LeakageGuard.requireNoLeakage(LeakageGuard.upto(contaminated, decisionTime), decisionTime)
    }

    @Test
    fun `given 0_50R has one dominant outlier window while 0_75R and 1_00R form a stable plateau when promoting then the plateau candidate is chosen over the peak`() {
        val profiler = ParameterStabilityProfiler()

        val reports = mapOf(
            WalkForwardConfigId.P1 to listOf(
                fold(WalkForwardConfigId.P1, 2.0, 0.05, 0.80, 45.0),
                fold(WalkForwardConfigId.P1, 1.5, 0.04, 0.70, 42.0),
                fold(WalkForwardConfigId.P1, 1.8, 0.04, 0.75, 44.0)
            ),
            // One window contributes most of the return; the other two are flat.
            WalkForwardConfigId.P2 to listOf(
                fold(WalkForwardConfigId.P2, 18.0, 0.45, 2.10, 55.0),
                fold(WalkForwardConfigId.P2, 0.4, 0.01, 0.20, 30.0),
                fold(WalkForwardConfigId.P2, 0.5, 0.01, 0.30, 32.0)
            ),
            WalkForwardConfigId.P3 to listOf(
                fold(WalkForwardConfigId.P3, 6.2, 0.28, 1.75, 68.0),
                fold(WalkForwardConfigId.P3, 5.9, 0.27, 1.70, 66.0),
                fold(WalkForwardConfigId.P3, 6.4, 0.29, 1.80, 70.0)
            ),
            WalkForwardConfigId.P4 to listOf(
                fold(WalkForwardConfigId.P4, 5.8, 0.26, 1.68, 65.0),
                fold(WalkForwardConfigId.P4, 5.7, 0.25, 1.65, 64.0),
                fold(WalkForwardConfigId.P4, 6.0, 0.27, 1.72, 67.0)
            ),
            WalkForwardConfigId.P5 to listOf(
                fold(WalkForwardConfigId.P5, 4.1, 0.18, 1.30, 58.0),
                fold(WalkForwardConfigId.P5, 4.0, 0.17, 1.28, 57.0),
                fold(WalkForwardConfigId.P5, 4.2, 0.18, 1.32, 59.0)
            ),
            WalkForwardConfigId.P6 to listOf(
                fold(WalkForwardConfigId.P6, 2.5, 0.10, 0.95, 50.0),
                fold(WalkForwardConfigId.P6, 2.4, 0.09, 0.90, 48.0),
                fold(WalkForwardConfigId.P6, 2.6, 0.10, 0.98, 51.0)
            )
        )

        val promoted = profiler.selectStableProductionCandidate(reports)

        assertEquals(WalkForwardConfigId.P3, promoted)
    }

    @Test
    fun `given no fold reports for any swept candidate when promoting then it fails instead of returning a threshold`() {
        val profiler = ParameterStabilityProfiler()

        var failed = false
        try {
            profiler.selectStableProductionCandidate(emptyMap())
        } catch (expected: IllegalArgumentException) {
            failed = true
            assertTrue(expected.message!!.contains("fold reports"))
        }

        assertTrue("Promotion needs evidence", failed)
    }
}
