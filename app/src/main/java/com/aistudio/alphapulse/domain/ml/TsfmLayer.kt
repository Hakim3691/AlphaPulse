package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.data.model.ChartPoint

/** Market-state aggregation layer for TSFM models.
 *
 * Spec Ref: §23 Forecast Scheduling, §31 Model Ensemble Health
 */
interface TsfmLayer {
    /** Predict future price distribution using the best available TSFM provider.
     *  @param input market-state tensor including close price, volatility, regime, horizon
     *  @return TsfmPrediction with P10-P90 quantiles and derived statistics */
    fun predict(input: TsfmInput): TsfmPrediction

    /** Aggregate predictions from multiple providers using weighted average. */
    fun aggregate(
        predictions: List<TsfmPrediction>,
        weights: List<Double>
    ): TsfmPrediction

    /** Get the best available provider for the given input. */
    fun bestProvider(input: TsfmInput): TsfmProvider

    /** Compute quantile-weighted median from sorted predictions. */
    fun weightedMedian(
        predictions: List<Double>,
        weights: List<Double>
    ): Double

    /** Convert kline data to TSFM input tensor. */
    fun klineToTsfmInput(
        kline: ChartPoint,
        horizon: String
    ): TsfmInput
}

/** Default TSFM layer implementation. */
class TsfmLayerImpl(
    private val providerRegistry: TsfmProviderRegistry = TsfmProviderRegistry()
) : TsfmLayer {

    override fun predict(input: TsfmInput): TsfmPrediction {
        val selectedProvider = providerRegistry.selectProvider()
        return selectedProvider.predict(input)
    }

    override fun aggregate(
        predictions: List<TsfmPrediction>,
        weights: List<Double>
    ): TsfmPrediction {
        if (predictions.isEmpty()) {
            throw IllegalArgumentException("Cannot aggregate empty predictions")
        }
        if (predictions.size != weights.size) {
            throw IllegalArgumentException(
                "Predictions and weights count mismatch: ${predictions.size} vs ${weights.size}"
            )
        }

        // Weighted aggregation of P50 values using provided weights
        var totalWeight = 0.0
        var weightedSum = 0.0
        for ((pred, weight) in predictions.zip(weights)) {
            weightedSum += pred.p50 * weight
            totalWeight += weight
        }

        val p50 = if (totalWeight > 0.0) weightedSum / totalWeight else predictions[0].p50

        // Weighted aggregation of other quantiles
        val p10 = weightedAverage(predictions.map { it.p10 }, weights) / totalWeight.coerceAtLeast(1.0)
        val p90 = weightedAverage(predictions.map { it.p90 }, weights) / totalWeight.coerceAtLeast(1.0)
        val expectedPrice = weightedAverage(
            predictions.map { it.expectedPrice },
            weights
        ) / totalWeight.coerceAtLeast(1.0)

        val lowerConfidenceBoundary = p10
        val upperConfidenceBoundary = p90
        val forecastSkew = (p90 - p50) - (p50 - p10)
        val forecastDispersion = p90 - p10

        return TsfmPrediction(
            p10 = p10,
            p25 = if (predictions.isNotEmpty()) predictions[0].p25 else p10,
            p50 = p50,
            p75 = if (predictions.isNotEmpty()) predictions[0].p75 else p50,
            p90 = p90,
            expectedPrice = expectedPrice,
            lowerConfidenceBoundary = lowerConfidenceBoundary,
            upperConfidenceBoundary = upperConfidenceBoundary,
            forecastSkew = forecastSkew,
            forecastDispersion = forecastDispersion,
            modelId = "aggregated",
            version = "1.0",
            backend = TsfmBackend.TIMESFM_2_0,
            timestamp = System.currentTimeMillis()
        )
    }

    override fun bestProvider(input: TsfmInput): TsfmProvider {
        return providerRegistry.bestProvider(input)
    }

    override fun weightedMedian(
        predictions: List<Double>,
        weights: List<Double>
    ): Double {
        if (predictions.isEmpty() || weights.isEmpty()) {
            throw IllegalArgumentException("Cannot compute weighted median of empty data")
        }
        if (predictions.size != weights.size) {
            throw IllegalArgumentException(
                "Predictions and weights count mismatch: ${predictions.size} vs ${weights.size}"
            )
        }

        // Sort predictions and weights together by prediction value
        val sortedPreds = predictions.sorted()
        val sortedWeights = weights.withIndex()
            .sortedBy { weights[it.index] }
            .map { it.value }

        // Compute cumulative weights
        val totalWeight = sortedWeights.sum()
        if (totalWeight == 0.0) {
            return sortedPreds.first()
        }

        // Find the weighted median
        var cumulative = 0.0
        var idx = 0
        while (idx < sortedWeights.size && cumulative < totalWeight / 2.0) {
            cumulative += sortedWeights[idx]
            idx++
        }
        return sortedPreds[idx - 1]
    }

    override fun klineToTsfmInput(
        kline: ChartPoint,
        horizon: String
    ): TsfmInput {
        val logReturn = if (kline.close > 0 && kline.open > 0) {
            Math.log(kline.close / kline.open)
        } else 0.0

        return TsfmInput(
            timestamp = kline.timestamp,
            open = kline.open,
            high = kline.high,
            low = kline.low,
            close = kline.close,
            volume = kline.volume,
            logReturn = logReturn,
            realizedVolatility = kline.realizedVolatility ?: 0.0,
            funding = kline.funding ?: 0.0,
            openInterest = kline.openInterest ?: 0.0,
            oiChange = kline.oiChange ?: 0.0,
            takerBuyRatio = kline.takerBuyRatio ?: 0.0,
            vwapDistance = kline.vwapDistance ?: 0.0,
            pocDistance = kline.pocDistance ?: 0.0,
            vahDistance = kline.vahDistance ?: 0.0,
            valDistance = kline.valDistance ?: 0.0,
            l2Imbalance = kline.l2Imbalance ?: 0.0,
            spread = kline.spread ?: 0.0,
            depthVelocity = kline.depthVelocity ?: 0.0,
            liquidationIntensity = kline.liquidationIntensity ?: 0.0,
            upperLiquidityDistance = kline.upperLiquidityDistance ?: 0.0,
            lowerLiquidityDistance = kline.lowerLiquidityDistance ?: 0.0,
            regime = kline.regime ?: "Unknown",
            horizon = horizon,
            metadata = kline.metadata ?: emptyMap<String, Any>()
        )
    }

    /** Compute weighted average of values with given weights. */
    private fun weightedAverage(values: List<Double>, weights: List<Double>): Double {
        if (values.isEmpty() || weights.isEmpty() || values.size != weights.size) {
            return 0.0
        }
        var weightedSum = 0.0
        var totalWeight = 0.0
        for ((value, weight) in values.withIndex()) {
            weightedSum += value * weight
            totalWeight += weight
        }
        return if (totalWeight > 0.0) weightedSum / totalWeight else 0.0
    }

    companion object {
        @JvmField
        val default = TsfmLayerImpl()
    }
}

/** Registry for TSFM provider instances with priority ordering. */
class TsfmProviderRegistry {
    private val providers = mutableListOf<TsfmProvider>()

    /** Register a provider with the registry. */
    fun register(provider: TsfmProvider) {
        providers.add(provider)
    }

    /** Get the best available provider for the given input. */
    fun bestProvider(input: TsfmInput): TsfmProvider {
        val available = providers.filter { it.isAvailable }
        if (available.isEmpty()) {
            throw IllegalStateException("No TSFM providers available")
        }
        return available
            .sortedBy { -it.priority }
            .first()
    }

    /** Get all available providers sorted by priority (highest first). */
    fun allAvailable(): List<TsfmProvider> {
        return providers.filter { it.isAvailable }.sortedBy { -it.priority }
    }

    /** Check if any provider is available. */
    fun hasAvailable(): Boolean {
        return providers.any { it.isAvailable }
    }

    /** Select the best available provider for the given input. */
    fun selectProvider(): TsfmProvider {
        val available = providers.filter { it.isAvailable }
        if (available.isEmpty()) {
            throw IllegalStateException("No TSFM providers available")
        }
        return available
            .sortedBy { -it.priority }
            .first()
    }
}