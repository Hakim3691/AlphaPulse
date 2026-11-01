package com.aistudio.alphapulse.domain.ensemble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * §66 / Contract C-18 — 30-day multi-horizon Binance ingestion and the tri/quad/penta
 * calibration tiers.
 *
 * The pipeline, its tiers and its weight carrier live in production
 * (`domain/ensemble/ThirtyDayBinanceCalibration.kt`); this spec drives them, so a
 * weighting rule that stops summing to 1.000 — or that hands a weight to a model which
 * is not mounted — breaks the build.
 *
 * Spec Ref: §66, C-18
 */
class ThirtyDayBinanceCalibrationBddSpec {

    private val defaultFiveScores = listOf(
        ModelErrorScore("W1_KINEMATIC", mae = 0.0018, rmse = 0.0022, directionalAccuracy = 0.68),
        ModelErrorScore("W2_LOB_PAIN", mae = 0.0021, rmse = 0.0026, directionalAccuracy = 0.65),
        ModelErrorScore("W3_CONFLUENCE", mae = 0.0045, rmse = 0.0058, directionalAccuracy = 0.59),
        ModelErrorScore("W4_CHRONOS_BOLT", mae = 0.0068, rmse = 0.0085, directionalAccuracy = 0.56),
        ModelErrorScore("W5_TIMESFM_2", mae = 0.0040, rmse = 0.0051, directionalAccuracy = 0.61)
    )

    @Test
    fun `given 30 days of Binance public klines across all 5 horizons when Needle 3 orchestrates Penta Factor calibration then weights sum strictly to 1_000`() {
        val pipeline = ThirtyDayBinanceCalibrationPipeline()

        Horizon.values().forEach { horizon ->
            val result = pipeline.calibrateFrom30DayWindow(
                symbol = "RTWUSDT",
                horizon = horizon,
                ingestedCandleCount = horizon.expectedCandles30d,
                tier = EnsembleTier.PENTA_FACTOR,
                scores = defaultFiveScores,
                needle3Available = true
            )

            assertEquals("NEEDLE3", result.orchestrationSource)
            assertEquals(1.000, result.sumWeights, 1e-9)
            assertTrue(result.w1Kinematic > 0.0)
            assertTrue(result.w2PainPath > 0.0)
            assertTrue(result.w3Confluence > 0.0)
            assertTrue(result.w4ChronosBolt > 0.0)
            assertTrue(result.w5TimesFm > 0.0)
        }
    }

    @Test
    fun `given Tri Factor or Quad Factor mounted tier when calibrated on 30 day dataset then unmounted TSFM weights are zero and active weights sum to 1_000`() {
        val pipeline = ThirtyDayBinanceCalibrationPipeline()

        val triResult = pipeline.calibrateFrom30DayWindow(
            symbol = "BTCUSDT",
            horizon = Horizon.M15,
            ingestedCandleCount = Horizon.M15.expectedCandles30d,
            tier = EnsembleTier.TRI_FACTOR,
            scores = defaultFiveScores,
            needle3Available = true
        )
        assertEquals(1.000, triResult.sumWeights, 1e-9)
        assertEquals(0.0, triResult.w4ChronosBolt, 0.0)
        assertEquals(0.0, triResult.w5TimesFm, 0.0)

        val quadResult = pipeline.calibrateFrom30DayWindow(
            symbol = "BTCUSDT",
            horizon = Horizon.M15,
            ingestedCandleCount = Horizon.M15.expectedCandles30d,
            tier = EnsembleTier.QUAD_FACTOR,
            scores = defaultFiveScores,
            needle3Available = true
        )
        assertEquals(1.000, quadResult.sumWeights, 1e-9)
        assertTrue(quadResult.w4ChronosBolt > 0.0)
        assertEquals(0.0, quadResult.w5TimesFm, 0.0)
    }

    @Test
    fun `given Needle 3 returns refusal on unsupported ABI when 30 day calibration runs then deterministic fallback completes without exception`() {
        val pipeline = ThirtyDayBinanceCalibrationPipeline()

        val fallbackResult = pipeline.calibrateFrom30DayWindow(
            symbol = "PAXGUSDT",
            horizon = Horizon.H1,
            ingestedCandleCount = Horizon.H1.expectedCandles30d,
            tier = EnsembleTier.PENTA_FACTOR,
            scores = defaultFiveScores,
            needle3Available = false
        )

        assertEquals("DETERMINISTIC_FALLBACK", fallbackResult.orchestrationSource)
        assertTrue(abs(fallbackResult.sumWeights - 1.0) < 1e-9)
    }
}
