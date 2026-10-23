package com.aistudio.alphapulse.domain.ml.onnx

import kotlin.math.ln

/**
 * Price-space forecast from a mounted TSFM. One element per 1-minute step from
 * the last observed close; [lower] and [upper] are the model's q10/q90 bands.
 */
data class TsfmForecast(
    val modelId: String,
    val median: DoubleArray,
    val lower: DoubleArray,
    val upper: DoubleArray,
    val generatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Log drift per minute implied by the median at [minutes] ahead, or null
     * when the forecast cannot be compared with [spot].
     */
    fun driftPerMinute(spot: Double, minutes: Int): Double? {
        if (spot <= 0.0 || minutes <= 0 || median.isEmpty()) return null
        val price = median[(minutes - 1).coerceAtMost(median.lastIndex)]
        if (!price.isFinite() || price <= 0.0) return null
        return ln(price / spot) / minutes
    }
}
