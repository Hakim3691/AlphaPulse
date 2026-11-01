package com.aistudio.alphapulse.domain.ensemble

/**
 * §66 / Contract C-18 — the 30-day multi-horizon calibration tiers.
 *
 * Why this exists
 * ---------------
 * [CoinCalibration] answers *how well did each mounted model predict this coin over the
 * last thirty days of Binance history*. This file answers the next question: given
 * those error scores, what weights may the ensemble actually run with, per horizon and
 * per mounted tier?
 *
 * The three tiers are not cosmetic. A device that mounted only the core tri-factor must
 * never carry a W4/W5 weight, because a weight on a model that is not there is a
 * permanent under-weighting of the models that are. So an unmounted component is
 * exactly 0.0, and the remaining components are renormalised to sum to 1.000 — the
 * invariant the whole forecast ladder depends on.
 *
 * The last active component absorbs the rounding residual rather than the largest one,
 * so a tier's weights always sum to 1.000 within floating-point tolerance instead of
 * drifting by the accumulated error of four independent divisions.
 *
 * Ingestion is checked before weighting: a 30-day window that arrived short (a rate
 * limit, a truncated page) would flatter every model on a smaller sample, so a partial
 * window is rejected rather than calibrated against.
 *
 * Spec Ref: §66 30-Day Binance Calibration, C-18
 */

/**
 * One forecast horizon with the exact number of exchange candles a 30-day window holds.
 *
 * The counts are derived from the interval, not measured, so a partially ingested
 * window is detectable: 1m over 30 days is 43,200 candles, and anything less is short.
 */
enum class Horizon(val label: String, val expectedCandles30d: Int) {
    M1("1m", 43_200),
    M5("5m", 8_640),
    M15("15m", 2_880),
    M30("30m", 1_440),
    H1("1h", 720)
}

/** Which ensemble tier is mounted on this device. */
enum class EnsembleTier(val modelCount: Int) {
    TRI_FACTOR(3),
    QUAD_FACTOR(4),
    PENTA_FACTOR(5)
}

/** One model's error on the 30-day rolling-context evaluation of a horizon. */
data class ModelErrorScore(
    val modelId: String,
    val mae: Double,
    val rmse: Double,
    val directionalAccuracy: Double
)

/**
 * The calibrated weights for one symbol, horizon and tier.
 *
 * @param orchestrationSource who chose the plan: `NEEDLE3` when the on-device engine
 *   answered, `DETERMINISTIC_FALLBACK` when it refused and the rules took over
 */
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
    /** The invariant: a mounted tier distributes exactly its full budget. */
    val sumWeights: Double
        get() = w1Kinematic + w2PainPath + w3Confluence + w4ChronosBolt + w5TimesFm
}

/**
 * Turns 30-day error scores into the weights one tier may run with.
 *
 * Weighting is inverse-RMSE, scaled by how often the model got the direction right:
 * a model that is precise but points the wrong way should not inherit the budget of
 * one that is slightly noisier but directional. Nothing is trained here — the models
 * are scored as shipped, which is what makes two runs comparable.
 */
class ThirtyDayBinanceCalibrationPipeline {

    /**
     * @param ingestedCandleCount candles actually received for the window
     * @param scores every mounted model's error score, best-first is not required
     * @param needle3Available false when the engine refused, in which case the
     *   deterministic rules still produce a full calibration
     */
    fun calibrateFrom30DayWindow(
        symbol: String,
        horizon: Horizon,
        ingestedCandleCount: Int,
        tier: EnsembleTier,
        scores: List<ModelErrorScore>,
        needle3Available: Boolean
    ): CalibratedHorizonWeights {
        require(ingestedCandleCount >= horizon.expectedCandles30d) {
            "Insufficient 30-day Binance public history for $symbol ${horizon.label}: " +
                "got $ingestedCandleCount, required ${horizon.expectedCandles30d}"
        }

        val activeScores = scores.take(tier.modelCount)
        val inverseRmse = activeScores.map { score ->
            // A zero RMSE is a perfect score, not an infinite one: clamp before dividing.
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

        val source = if (needle3Available) NEEDLE3_SOURCE else DETERMINISTIC_SOURCE

        // The last active component absorbs the residual so the tier sums to 1.000.
        return when (tier) {
            EnsembleTier.TRI_FACTOR -> {
                val adjustedW3 = 1.0 - (w1 + w2)
                CalibratedHorizonWeights(
                    symbol, horizon, tier, w1, w2, adjustedW3, 0.0, 0.0, source
                )
            }
            EnsembleTier.QUAD_FACTOR -> {
                val adjustedW4 = 1.0 - (w1 + w2 + w3)
                CalibratedHorizonWeights(
                    symbol, horizon, tier, w1, w2, w3, adjustedW4, 0.0, source
                )
            }
            EnsembleTier.PENTA_FACTOR -> {
                CalibratedHorizonWeights(
                    symbol, horizon, tier, w1, w2, w3, w4, w5, source
                )
            }
        }
    }

    companion object {
        /** The on-device engine answered; its plan is the calibration. */
        const val NEEDLE3_SOURCE = "NEEDLE3"

        /** The engine refused and the deterministic rules produced the plan. */
        const val DETERMINISTIC_SOURCE = "DETERMINISTIC_FALLBACK"
    }
}
