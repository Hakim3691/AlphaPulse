package com.aistudio.alphapulse.domain.ml.onnx

import com.aistudio.alphapulse.data.model.ModelArtifacts
import kotlin.math.sqrt

/**
 * Tensor contract for the published TimesFM 2.5 200M ONNX graph.
 *
 * `inputs` [1,512] is the normalized, left-padded history and `masks` [1,512]
 * marks padding (1 = padding, 0 = observed — the opposite of Chronos). The
 * graph returns `point` / `qspread` [1,128,10]; channel 0 is the mean head and
 * channels 1..9 are q10..q90.
 *
 * The mean head is used directly, so the optional flip-invariance double pass
 * is not run; quantile-crossing repair is likewise skipped because only the
 * median is consumed as drift and the bands are advisory.
 */
object TimesFmAdapter {

    const val CONTEXT_LENGTH = 512
    const val MAX_HORIZON = 128

    private const val CHANNELS = 10
    private const val MEAN_CHANNEL = 0
    private const val Q10_CHANNEL = 1
    private const val Q90_CHANNEL = 9
    private const val MIN_STD = 1e-8

    data class Prepared(
        val inputs: FloatArray,
        val masks: FloatArray,
        val mean: Double,
        val std: Double
    )

    /** Pad to 512, interpolate interior gaps, and normalize the padded window. */
    fun prepare(closes: DoubleArray): Prepared {
        val window = DoubleArray(CONTEXT_LENGTH)
        val padding = BooleanArray(CONTEXT_LENGTH) { true }

        val tail = if (closes.size > CONTEXT_LENGTH) {
            closes.copyOfRange(closes.size - CONTEXT_LENGTH, closes.size)
        } else {
            closes
        }
        val clean = interpolate(tail)
        val offset = CONTEXT_LENGTH - clean.size
        for (i in clean.indices) {
            window[offset + i] = clean[i]
            padding[offset + i] = false
        }

        val mean = window.average()
        var squared = 0.0
        for (value in window) {
            val delta = value - mean
            squared += delta * delta
        }
        val std = if (window.size > 1) sqrt(squared / (window.size - 1)) else 0.0
        val safeStd = if (std < MIN_STD) 1.0 else std

        val inputs = FloatArray(CONTEXT_LENGTH) { ((window[it] - mean) / safeStd).toFloat() }
        val masks = FloatArray(CONTEXT_LENGTH) { if (padding[it]) 1f else 0f }

        return Prepared(inputs, masks, mean, safeStd)
    }

    /** Map the mean head back to prices for [steps] ahead. */
    fun decode(
        point: Array<Array<FloatArray>>,
        prepared: Prepared,
        steps: Int
    ): TsfmForecast {
        require(point.isNotEmpty()) { "TimesFM output has no batch" }
        val rows = point[0]
        require(rows.isNotEmpty()) { "TimesFM output has no horizon rows" }
        val horizon = steps.coerceIn(1, MAX_HORIZON)
        require(rows.size >= horizon) {
            "TimesFM output covers ${rows.size} steps, need $horizon"
        }

        val median = DoubleArray(horizon)
        val lower = DoubleArray(horizon)
        val upper = DoubleArray(horizon)
        for (step in 0 until horizon) {
            val channels = rows[step]
            require(channels.size >= CHANNELS) {
                "TimesFM output has ${channels.size} channels, expected $CHANNELS"
            }
            median[step] = channels[MEAN_CHANNEL] * prepared.std + prepared.mean
            lower[step] = channels[Q10_CHANNEL] * prepared.std + prepared.mean
            upper[step] = channels[Q90_CHANNEL] * prepared.std + prepared.mean
        }

        return TsfmForecast(
            modelId = ModelArtifacts.TIMESFM.modelId,
            median = median,
            lower = lower,
            upper = upper
        )
    }

    /**
     * Drop leading gaps, linearly interpolate interior ones and carry the last
     * observation over trailing gaps. Returns an empty array when fully missing.
     */
    internal fun interpolate(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return DoubleArray(0)
        val first = values.indexOfFirst { it.isFinite() }
        if (first < 0) return DoubleArray(0)

        val out = values.copyOfRange(first, values.size)
        var lastValid = out[0]
        var lastIndex = 0
        for (i in out.indices) {
            if (out[i].isFinite()) {
                lastValid = out[i]
                lastIndex = i
                continue
            }
            val next = (i + 1 until out.size).firstOrNull { out[it].isFinite() }
            out[i] = if (next != null) {
                lastValid + (out[next] - lastValid) * (i - lastIndex) / (next - lastIndex)
            } else {
                lastValid
            }
        }
        return out
    }

}
