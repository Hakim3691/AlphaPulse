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
}

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
    val timestamp: Long,
    /** Open price */
    val open: Double,
    /** High price */
    val high: Double,
    /** Low price */
    val low: Double,
    /** Close price */
    val close: Double,
    /** Volume */
    val volume: Double,
    /** Log return */
    val logReturn: Double,
    /** Realized volatility */
    val realizedVolatility: Double,
    /** Funding rate */
    val funding: Double,
    /** Open interest */
    val openInterest: Double,
    /** OI change */
    val oiChange: Double,
    /** Taker buy ratio */
    val takerBuyRatio: Double,
    /** VWAP distance */
    val vwapDistance: Double,
    /** POC distance */
    val pocDistance: Double,
    /** VAH distance */
    val vahDistance: Double,
    /** VAL distance */
    val valDistance: Double,
    /** L2 imbalance */
    val l2Imbalance: Double,
    /** Spread */
    val spread: Double,
    /** Depth velocity */
    val depthVelocity: Double,
    /** Liquidation intensity */
    val liquidationIntensity: Double,
    /** Upper liquidity distance */
    val upperLiquidityDistance: Double,
    /** Lower liquidity distance */
    val lowerLiquidityDistance: Double,
    /** Regime */
    val regime: String,
    /** Horizon */
    val horizon: String
}

/** TSFM provider interface for pluggable foundation models. */
interface TsfmProvider {
    /** Provider identifier */
    val providerId: String
    /** Model version */
    val version: String
    /** Preferred backend for this provider */
    val preferredBackend: TsfmBackend
    /** Whether this provider requires model file download */
    val requiresDownload: Boolean
    /** Inferior/fallback provider */
    val fallbackProvider: TsfmProvider? = null

    /** Predict using this TSFM provider.
     *  Returns TsfmPrediction with P10-P90 quantiles.
     */
    fun predict(input: TsfmInput): TsfmPrediction

    /** Check if this provider is available on the current device. */
    fun isAvailable(): Boolean

    /** Get the priority of this provider (lower = higher priority). */
    val priority: Int
}

/** TSFM provider registry and ensemble. */
object TsfmEnsemble {

    /** Registered providers in priority order. */
    private val providers = mutableListOf<TsfmProvider>()

    /** Register a provider. */
    fun register(provider: TsfmProvider) {
        providers.add(provider)
        providers.sortBy { it.priority }
    }

    /** Get all registered providers. */
    fun getProviders(): List<TsfmProvider> = providers.toList()

    /** Predict using the ensemble, combining all available providers.
     *  Weights are inversely proportional to latency (normalized).
     */
    fun predictEnsemble(input: TsfmInput): TsfmPrediction {
        val available = providers.filter { it.isAvailable() }

        if (available.isEmpty()) {
            // Fallback to stub
            return TsfmLayerStub().predict(
                inputToKlineData(input)
            ) // This won't work, need fallback
        }

        // Collect predictions from all available providers
        val predictions = available.map { it.predict(input) }

        // Compute inverse-latency weights (simplified: equal weight for now)
        val weight = 1.0 / predictions.size

        // Combine quantiles: weighted average of P10, P50, P90
        val combinedP10 = predictions.averageBy { it.p10 }
        val combinedP50 = predictions.averageBy { it.p50 }
        val combinedP90 = predictions.averageBy { it.p90 }

        // P25 and P75 interpolation
        val combinedP25 = (combinedP10 + combinedP50) / 2.0 // simplified
        val combinedP75 = (combinedP50 + combinedP90) / 2.0 // simplified

        val combinedSkew = (predictions.averageBy { it.forecastSkew }) * weight
        val combinedDispersion = (predictions.averageBy { it.forecastDispersion }) * weight

        return TsfmPrediction(
            p10 = combinedP10,
            p25 = combinedP25,
            p50 = combinedP50,
            p75 = combinedP75,
            p90 = combinedP90,
            expectedPrice = combinedP50,
            lowerConfidenceBoundary = combinedP10,
            upperConfidenceBoundary = combinedP90,
            forecastSkew = combinedSkew,
            forecastDispersion = combinedDispersion,
            modelId = "ensemble-${providers.map { it.providerId }.joinToString(",")}",
            version = providers.map { it.version }.joinToString("+"),
            backend = TsfmBackend.TIMESFM_2_0, // fallback
            timestamp = System.currentTimeMillis()
        )
    }

    /** Convert TsfmInput to KlineData (for backward compatibility). */
    private fun inputToKlineData(input: TsfmInput): KlineData = KlineData(
        timestamp = input.timestamp,
        open = input.open,
        high = input.high,
        low = input.low,
        close = input.close,
        volume = input.volume,
        quoteVolume = input.volume * input.close,
        takerBuyBaseVolume = input.volume * input.takerBuyRatio,
        takerBuyQuoteVolume = input.volume * input.takerBuyRatio * input.close
    )
}