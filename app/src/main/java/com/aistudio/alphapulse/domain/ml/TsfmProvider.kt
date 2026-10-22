package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.data.model.ChartPoint

/** Market-state tensor fed into a TSFM provider's predict() call. */
data class TsfmInput(
    /** Close price of the last K-line in the sequence. */
    val close: Double,
    /** Realized volatility over the lookback window. */
    val realizedVolatility: Double,
    /** Chronos/TimesFM style regime indicator. */
    val regime: String,
    /** Horizon in minutes for the prediction. */
    val horizon: Int,
    /** Additional metadata, not used by core inference currently. */
    val metadata: Map<String, Any> = emptyMap()
)

/** Backend type for TSFM execution (KRONOS_SMALL, CHRONOS_BOLT, etc.). */
enum class TsfmBackend {
    KRONOS_SMALL,
    CHRONOS_BOLT,
    FINTEXT_CHRONOS,
    TIMESFM_2_0
}

/** Provider identity information. */
interface TsfmProvider {
    /** Unique identifier for the provider. */
    val providerId: String
    /** Version string for the provider/model. */
    val version: String
    /** Preferred backend for execution. */
    val preferredBackend: TsfmBackend
    /** Whether model weights need to be downloaded before first use. */
    val requiresDownload: Boolean
    /** Provider priority for ensemble selection (higher = preferred). */
    val priority: Int
    /** True only when this implementation executes a neural graph. */
    val isNeural: Boolean
        get() = false

    /** Check if the provider's model is available locally. */
    fun isAvailable(): Boolean

    /** Generate a fallback provider if this one is unavailable. */
    val fallbackProvider: TsfmProvider?

    /** Predict future price distribution given market-state tensor.
     *  @return TsfmPrediction with P10-P90 quantiles and other statistics. */
    fun predict(input: TsfmInput): TsfmPrediction

    /** Create a TsfmPrediction given individual quantile values. */
    companion object {
        fun fromQuantiles(
            p10: Double,
            p25: Double,
            p50: Double,
            p75: Double,
            p90: Double,
            expectedPrice: Double,
            lowerConfidenceBoundary: Double,
            upperConfidenceBoundary: Double,
            forecastSkew: Double,
            forecastDispersion: Double,
            modelId: String,
            version: String,
            backend: TsfmBackend
        ): TsfmPrediction = TsfmPrediction(
            p10 = p10,
            p25 = p25,
            p50 = p50,
            p75 = p75,
            p90 = p90,
            expectedPrice = expectedPrice,
            lowerConfidenceBoundary = lowerConfidenceBoundary,
            upperConfidenceBoundary = upperConfidenceBoundary,
            forecastSkew = forecastSkew,
            forecastDispersion = forecastDispersion,
            modelId = modelId,
            version = version,
            backend = backend,
            timestamp = System.currentTimeMillis()
        )
    }
}

/** Complete prediction output from a TSFM provider (spec §16).
 *  Contains P10-P90 quantile distributions plus derived statistics.
 */
data class TsfmPrediction(
    /** 10th percentile of the forecast distribution. */
    val p10: Double,
    /** 25th percentile of the forecast distribution. */
    val p25: Double,
    /** 50th percentile (median) of the forecast distribution. */
    val p50: Double,
    /** 75th percentile of the forecast distribution. */
    val p75: Double,
    /** 90th percentile of the forecast distribution. */
    val p90: Double,
    /** Expected (mean) price forecast. */
    val expectedPrice: Double,
    /** Lower confidence boundary (typically P10). */
    val lowerConfidenceBoundary: Double,
    /** Upper confidence boundary (typically P90). */
    val upperConfidenceBoundary: Double,
    /** Skew of the forecast distribution: (P90-P50) - (P50-P10). */
    val forecastSkew: Double,
    /** Width of the forecast distribution: P90 - P10. */
    val forecastDispersion: Double,
    /** Model identifier (provider@version). */
    val modelId: String,
    /** Version string for the model. */
    val version: String,
    /** Backend used for execution. */
    val backend: TsfmBackend,
    /** Timestamp of prediction generation. */
    val timestamp: Long
)