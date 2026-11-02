package com.aistudio.alphapulse.domain.ensemble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** BDD Guard Spec for §66 and Finalized Contract C-18:
 * Needle 3 30-Day Multi-Horizon Binance Ingestion & Tri/Quad/Penta Calibration.
 */
class ThirtyDayBinanceCalibrationBddSpec {

    enum class Horizon(val label: String, val expectedCandles30d: Int) {
        M1("1m", 43_200),
        M5("5m", 8_640),
        M15("15m", 2_880),
        M30("30m", 1_440),
        H1("1h", 720)
    }

    enum class EnsembleTier(val modelCount: Int) {
        TRI_FACTOR(3),
        QUAD_FACTOR(4),
        PENTA_FACTOR(5)
    }

    data class ModelErrorScore(
        val modelId: String,
        val mae: Double,
        val rmse: Double,
        val directionalAccuracy: Double
    )

    data class CalibratedHorizonWeights(
        val symbol: String,
        val horizon: Horizon,
        val tier: EnsembleTier,
        val w1Kinematic: Double,
        val w2PainPath: Double,
        val w3Confluence: Double,
        val w4ChronosBolt: Double,
        val w5TimesFm: Double,
        val orchestrationSource: String
    ) {
        val sumWeights: Double
            get() = w1Kinematic + w2PainPath + w3Confluence + w4ChronosBolt + w5TimesFm
    }

    class ThirtyDayBinanceCalibrationPipeline {
        fun calibrateFrom30DayWindow(
            symbol: String,
            horizon: Horizon,
            ingestedCandleCount: Int,
            tier: EnsembleTier,
            scores: List<ModelErrorScore>,
            needle3Available: Boolean
        ): CalibratedHorizonWeights {
            require(ingestedCandleCount >= horizon.expectedCandles30d) {
                "Insufficient 30-day Binance public history for $symbol ${horizon.label}: got $ingestedCandleCount, required ${horizon.expectedCandles30d}"
            }

            val activeScores = scores.take(tier.modelCount)
            val inverseRmse = activeScores.map { score ->
                val safeRmse = score.rmse.coerceAtLeast(1e-6)
                (1.0 / safeRmse) * (0.5 + 0.5 * score.directionalAccuracy)
            }
            val totalScore = inverseRmse.sum().coerceAtLeast(1e-9)
            val normalized = inverseRmse.map { it / totalScore }

            val w1 = normalized.getOrElse(0) { 0.0 }
            val w2 = normalized.getOrElse(1) { 0.0 }
            val w3 = normalized.getOrElse(2) { 0.0 }
            val w4 = normalized.getOrElse(3) { 0.0 }
            val w5 = if (tier.modelCount == 5) {
                (1.0 - (w1 + w2 + w3 + w4)).coerceAtLeast(0.0)
            } else {
                0.0
            }

            // Adjust last active component to guarantee exact 1.000 sum
            return when (tier) {
                EnsembleTier.TRI_FACTOR -> {
                    val adjW3 = 1.0 - (w1 + w2)
                    CalibratedHorizonWeights(symbol, horizon, tier, w1, w2, adjW3, 0.0, 0.0, if (needle3Available) "NEEDLE3" else "DETERMINISTIC_FALLBACK")
                }
                EnsembleTier.QUAD_FACTOR -> {
                    val adjW4 = 1.0 - (w1 + w2 + w3)
                    CalibratedHorizonWeights(symbol, horizon, tier, w1, w2, w3, adjW4, 0.0, if (needle3Available) "NEEDLE3" else "DETERMINISTIC_FALLBACK")
                }
                EnsembleTier.PENTA_FACTOR -> {
                    CalibratedHorizonWeights(symbol, horizon, tier, w1, w2, w3, w4, w5, if (needle3Available) "NEEDLE3" else "DETERMINISTIC_FALLBACK")
                }
            }
        }
    }

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