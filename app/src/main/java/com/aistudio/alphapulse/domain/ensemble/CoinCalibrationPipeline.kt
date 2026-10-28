package com.aistudio.alphapulse.domain.ensemble

import com.aistudio.alphapulse.domain.quant.KlineData
import com.aistudio.alphapulse.domain.quant.ModelState
import kotlin.math.max

/**
 * Runs the rolling-context evaluation behind [CoinCalibrationReport].
 *
 * The pipeline owns the loop, the origin selection and the assembly of a report;
 * everything numeric lives in [EnsembleCalibration] so it can be tested without
 * a coroutine or a model. Forecasting is injected as a single suspend function,
 * which is what lets the JVM specs drive the real loop without ONNX sessions.
 *
 * Spec Ref: §20 Adaptive Ensemble Learning, §23 Forecast Scheduling
 */
class CoinCalibrationPipeline(
    private val forecaster: HorizonForecaster,
    private val maxOrigins: Int = EnsembleCalibration.MAX_EVALUATION_ORIGINS,
    private val minContextBars: Int = EnsembleCalibration.MIN_CONTEXT_BARS
) {

    /**
     * Evaluate every horizon the plan names over the supplied history.
     *
     * A horizon with too little history still produces a row: it keeps the
     * mount-balanced weights, records zero samples, and says so through
     * [CoinCalibrationReport.barsPerHorizon] instead of being dropped.
     */
    suspend fun run(
        plan: CoinCalibrationPlan,
        candles: Map<String, List<KlineData>>
    ): CoinCalibrationReport {
        val horizonResults = mutableListOf<HorizonCalibration>()
        val barsPerHorizon = mutableMapOf<String, Int>()

        for (horizon in plan.horizons) {
            val bars = candles[horizon.id].orEmpty().sortedBy { it.openTime }
            barsPerHorizon[horizon.id] = bars.size
            horizonResults += evaluateHorizon(plan, horizon, bars)
        }

        return CoinCalibrationReport(
            symbol = plan.symbol,
            generatedAt = System.currentTimeMillis(),
            windowDays = plan.windowDays,
            dispatchedBy = plan.dispatchedBy,
            planReason = plan.reason,
            horizons = horizonResults,
            barsPerHorizon = barsPerHorizon
        )
    }

    /** Score every mounted model at one horizon and derive that horizon's weights. */
    private suspend fun evaluateHorizon(
        plan: CoinCalibrationPlan,
        horizon: CalibrationHorizon,
        bars: List<KlineData>
    ): HorizonCalibration {
        val closes = bars.map { it.close }.toDoubleArray()
        val origins = originIndices(closes.size)

        val scores = mutableListOf<ModelHorizonScore>()
        if (origins.isNotEmpty()) {
            for (modelId in plan.modelIds) {
                val predictions = mutableListOf<Double>()
                val actuals = mutableListOf<Double>()
                val references = mutableListOf<Double>()

                for (origin in origins) {
                    val context = closes.copyOfRange(0, origin + 1)
                    val predicted = forecaster.forecastCloseAhead(modelId, context, 1) ?: continue
                    val actual = closes[origin + 1]
                    if (!predicted.isFinite() || !actual.isFinite() || actual <= 0.0) continue

                    predictions += predicted
                    actuals += actual
                    references += closes[origin]
                }

                EnsembleCalibration.score(
                    modelId = modelId,
                    horizonId = horizon.id,
                    predictions = predictions,
                    actuals = actuals,
                    references = references
                )?.let { scores += it }
            }
        }

        val samples = scores.maxOfOrNull { it.samples } ?: 0
        return HorizonCalibration(
            horizonId = horizon.id,
            minutes = horizon.minutes,
            weights = EnsembleCalibration.weightsForHorizon(plan.modelIds, scores),
            scores = scores,
            samples = samples
        )
    }

    /**
     * Origins to evaluate, evenly spread across the window.
     *
     * Spreading rather than evaluating every bar keeps a 30-day window cheap on a
     * phone while still covering regimes from the start of the month; the last
     * origin is always one whose next bar exists.
     */
    private fun originIndices(size: Int): List<Int> {
        if (size <= minContextBars + 1) return emptyList()
        val candidates = (minContextBars until size - 1).toList()
        if (candidates.size <= maxOrigins) return candidates

        val stride = candidates.size.toDouble() / max(maxOrigins, 1)
        return (0 until maxOrigins)
            .map { index -> candidates[(index * stride).toInt().coerceAtMost(candidates.lastIndex)] }
            .distinct()
    }
}

/**
 * One model's next-close prediction, injected.
 *
 * @param closes every observed close up to and including the origin
 * @param steps bars ahead to predict (1 for the calibration loop)
 * @return the predicted close, or null when this model cannot answer
 */
fun interface HorizonForecaster {
    suspend fun forecastCloseAhead(modelId: String, closes: DoubleArray, steps: Int): Double?
}

/**
 * A calibrated horizon applied to a live state, for callers that only need the
 * weights without the evidence.
 */
fun CoinCalibrationReport.applyTo(horizonId: String, fallback: ModelState): ModelState =
    weightsFor(horizonId) ?: fallback
