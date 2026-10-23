package com.aistudio.alphapulse.domain.ml.onnx

import com.aistudio.alphapulse.data.model.ModelArtifacts
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tensor contract for the published Chronos-Bolt Tiny ONNX graph.
 *
 * `context` [1,2048] holds the normalized, left-padded history and `mask`
 * [1,2048] marks observed points (1 = observed). The graph returns `quantiles`
 * [1,9,64] normalized q10..q90. Price space is restored with the same
 * loc/scale used for normalization, exactly as the publisher documents.
 */
object ChronosBoltAdapter {

    const val CONTEXT_LENGTH = 2048
    const val MAX_HORIZON = 64

    private const val QUANTILE_COUNT = 9
    private const val Q10_INDEX = 0
    private const val MEDIAN_INDEX = 4
    private const val Q90_INDEX = 8
    private const val MIN_SCALE = 1e-5

    data class Prepared(
        val context: FloatArray,
        val mask: FloatArray,
        val loc: Double,
        val scale: Double
    )

    /** Normalize the trailing [CONTEXT_LENGTH] closes exactly as the release specifies. */
    fun prepare(closes: DoubleArray): Prepared {
        val observedCount = min(closes.size, CONTEXT_LENGTH)
        val offset = CONTEXT_LENGTH - observedCount
        val values = DoubleArray(CONTEXT_LENGTH)
        val present = BooleanArray(CONTEXT_LENGTH)

        var valid = 0
        var sum = 0.0
        for (i in 0 until observedCount) {
            val value = closes[closes.size - observedCount + i]
            if (!value.isFinite()) continue
            values[offset + i] = value
            present[offset + i] = true
            valid++
            sum += value
        }

        val loc = if (valid == 0) 0.0 else sum / valid
        var squared = 0.0
        for (i in values.indices) {
            if (!present[i]) continue
            val delta = values[i] - loc
            squared += delta * delta
        }
        val scale = when {
            valid == 0 -> 1.0
            else -> sqrt(squared / valid).coerceAtLeast(MIN_SCALE)
        }

        val context = FloatArray(CONTEXT_LENGTH)
        val mask = FloatArray(CONTEXT_LENGTH)
        for (i in values.indices) {
            if (!present[i]) continue
            context[i] = ((values[i] - loc) / scale).toFloat()
            mask[i] = 1f
        }

        return Prepared(context, mask, loc, scale)
    }

    /** Map normalized `quantiles` [1,9,64] back to prices for [steps] ahead. */
    fun decode(
        quantiles: Array<Array<FloatArray>>,
        prepared: Prepared,
        steps: Int
    ): TsfmForecast {
        require(quantiles.isNotEmpty()) { "Chronos output has no batch" }
        val rows = quantiles[0]
        require(rows.size >= QUANTILE_COUNT) {
            "Chronos output has ${rows.size} quantiles, expected $QUANTILE_COUNT"
        }
        val horizon = steps.coerceIn(1, MAX_HORIZON)
        require(rows[MEDIAN_INDEX].size >= horizon) {
            "Chronos output covers ${rows[MEDIAN_INDEX].size} steps, need $horizon"
        }

        val median = DoubleArray(horizon)
        val lower = DoubleArray(horizon)
        val upper = DoubleArray(horizon)
        for (step in 0 until horizon) {
            median[step] = rows[MEDIAN_INDEX][step] * prepared.scale + prepared.loc
            lower[step] = rows[Q10_INDEX][step] * prepared.scale + prepared.loc
            upper[step] = rows[Q90_INDEX][step] * prepared.scale + prepared.loc
        }

        return TsfmForecast(
            modelId = ModelArtifacts.CHRONOS.modelId,
            median = median,
            lower = lower,
            upper = upper
        )
    }
}
