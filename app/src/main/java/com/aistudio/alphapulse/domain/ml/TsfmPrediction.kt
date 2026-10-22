package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.data.model.ChartPoint

/** Probabilistic TSFM forecast output with P10-P90 quantiles. */
data class TsfmPrediction(
    /** P10 quantile (10th percentile) */
    val p10: Double,
    /** P25 quantile (25th percentile) */
    val p25: Double,
    /** P50 quantile (50th percentile, median) */
    val p50: Double,
    /** P75 quantile (75th percentile) */
    val p75: Double,
    /** P90 quantile (90th percentile) */
    val p90: Double,
    /** Expected price (P50 or mean) */
    val expectedPrice: Double,
    /** Lower confidence boundary (P10) */
    val lowerConfidenceBoundary: Double,
    /** Upper confidence boundary (P90) */
    val upperConfidenceBoundary: Double,
    /** Forecast skew: (P90 - P50) - (P50 - P10) */
    val forecastSkew: Double,
    /** Forecast dispersion (P90 - P10) */
    val forecastDispersion: Double,
    /** Model ID for attribution */
    val modelId: String,
    /** Model version for attribution */
    val version: String,
    /** Inference backend used */
    val backend: TsfmBackend,
    /** Timestamp of prediction */
    val timestamp: Long = System.currentTimeMillis()
)

/** TSFM backend types. */
enum class TsfmBackend(val displayName: String) {
    KRONOS_SMALL("Kronos Small"),
    FINTEXT_CHRONOS("FinText Chronos"),
    CHRONOS_BOLT("Chronos Bolt"),
    TIMESFM_2_0("TimesFM 2.0"),
    TIMESFM_3_0("TimesFM 3.0");

    /** Select backend from a model name string. */
    companion object {
        fun fromName(name: String): TsfmBackend = when (name.lowercase()) {
            "kronos-small", "kronos" -> TsfmBackend.KRONOS_SMALL
            "fintext-chronos", "fintext" -> TsfmBackend.FINTEXT_CHRONOS
            "chronos-bolt", "chronos" -> TsfmBackend.CHRONOS_BOLT
            "timesfm-2.0", "timesfm" -> TsfmBackend.TIMESFM_2_0
            "timesfm-3.0" -> TsfmBackend.TIMESFM_3_0
            else -> TsfmBackend.TIMESFM_2_0
        }
    }
}

/** Structured market-state tensor input for TSFM (spec §17). */
data class TsfmInput(
    /** Timestamp of the candle/data point */
    val timestamp: Long = System.currentTimeMillis(),
    /** Open price */
    val open: Double = 0.0,
    /** High price */
    val high: Double = 0.0,
    /** Low price */
    val low: Double = 0.0,
    /** Close price */
    val close: Double = 0.0,
    /** Volume */
    val volume: Double = 0.0,
    /** Log return */
    val logReturn: Double = 0.0,
    /** Realized volatility */
    val realizedVolatility: Double = 0.0,
    /** Funding rate */
    val funding: Double = 0.0,
    /** Open interest */
    val openInterest: Double = 0.0,
    /** OI change */
    val oiChange: Double = 0.0,
    /** Taker buy ratio */
    val takerBuyRatio: Double = 0.0,
    /** VWAP distance */
    val vwapDistance: Double = 0.0,
    /** POC distance */
    val pocDistance: Double = 0.0,
    /** VAH distance */
    val vahDistance: Double = 0.0,
    /** VAL distance */
    val valDistance: Double = 0.0,
    /** L2 imbalance */
    val l2Imbalance: Double = 0.0,
    /** Spread */
    val spread: Double = 0.0,
    /** Depth velocity */
    val depthVelocity: Double = 0.0,
    /** Liquidation intensity */
    val liquidationIntensity: Double = 0.0,
    /** Upper liquidity distance */
    val upperLiquidityDistance: Double = 0.0,
    /** Lower liquidity distance */
    val lowerLiquidityDistance: Double = 0.0,
    /** Regime */
    val regime: String = "",
    /** Horizon */
    val horizon: String = "",
    /** Additional metadata */
    val metadata: Map<String, Any> = emptyMap()
)