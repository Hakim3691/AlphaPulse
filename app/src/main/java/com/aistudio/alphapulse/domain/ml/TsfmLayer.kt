package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.data.model.ChartPoint
import com.aistudio.alphapulse.domain.quant.KlineData

/** Time-Series Foundation Model (TSFM) layer - Phase 3 integration.
 *  Wraps pluggable TSFM providers (Kronos, FinText Chronos, Chronos, TimesFM)
 *  with market-state tensor input and P10-P90 quantile output.
 */
interface TsfmLayer {

    /** Predict future price distribution using the best available TSFM provider.
     *  @param input market-state tensor including close price, volatility, regime, horizon
     *  @return TsfmPrediction with P10-P90 quantiles and derived statistics */
    fun predict(input: TsfmInput): TsfmPrediction

    /** Legacy method for ChartPoint output (backward compatibility). */
    @Deprecated(
        message = "Use predict(TsfmInput) instead. Returns ChartPoint list for Phase 2 compatibility."
    )
    fun predict(
        klines: List<KlineData>,
        horizonMinutes: List<Int>
    ): List<ChartPoint>
        get = emptyList()
}

/** Default TsfmLayer implementation that uses the provider registry.
 *  Selects the highest-priority available provider and delegates to it.
 */
class TsfmLayerImpl(private val providerRegistry: TsfmProviderRegistry) : TsfmLayer {

    override fun predict(input: TsfmInput): TsfmPrediction {
        val selectedProvider = providerRegistry.selectProvider()
        return selectedProvider.predict(input)
    }

    @Deprecated(
        message = "Use predict(TsfmInput) instead. Returns ChartPoint list for Phase 2 compatibility."
    )
    override fun predict(
        klines: List<KlineData>,
        horizonMinutes: List<Int>
    ): List<ChartPoint> {
        // Legacy: convert kline data to TsfmInput and call predict
        val close = klines.lastOrNull()?.close ?: 0.0
        val realizedVolatility = calculateVolatility(klines)
        val regime = detectRegime(klines)
        val horizon = horizonMinutes.firstOrNull() ?: 60

        val input = TsfmInput(
            close = close,
            realizedVolatility = realizedVolatility,
            regime = regime,
            horizon = horizon
        )

        val prediction = predict(input)
        // Convert to ChartPoint (simple mapping for legacy support)
        return listOf(ChartPoint(
            timestamp = System.currentTimeMillis(),
            close = prediction.expectedPrice,
            open = close,
            high = prediction.upperConfidenceBoundary,
            low = prediction.lowerConfidenceBoundary,
            volume = 0
        ))
    }

    private fun calculateVolatility(klines: List<KlineData>): Double {
        if (klines.size < 2) return 0.0
        val returns = mutableListOf<Double>()
        for (i in 1 until klines.size) {
            val prevClose = klines[i - 1].close
            val currClose = klines[i].close
            if (prevClose != 0) {
                returns.add((currClose - prevClose) / prevClose)
            }
        }
        if (returns.isEmpty()) return 0.0
        val mean = returns.average() ?: 0.0
        val variance = returns.map { (it - mean).pow(2) }.average() ?: 0.0
        return sqrt(variance)
    }

    private fun detectRegime(klines: List<KlineData>): String {
        if (klines.size < 10) return "Sideways"
        // Simple regime detection: compare recent trend to historical average
        val recentStart = klines.size / 3
        val recentEnd = klines.size - 1
        val recentChange = (klines[recentEnd].close - klines[recentStart].close) / klines[recentStart].coerceAtLeast(1e-10)
        val earlyChange = (klines[recentStart / 2].close - klines[0].close) / klines[0].coerceAtLeast(1e-10)

        return when {
            recentChange > 0.05 && math.abs(recentChange) > math.abs(earlyChange) -> "Momentum Burst"
            recentChange < -0.05 && math.abs(recentChange) > math.abs(earlyChange) -> "Mean Reversion"
            else -> "Sideways"
        }
    }
}

/** Registry that selects the best available TSFM provider based on priority and availability. */
class TsfmProviderRegistry {

    private val providers: List<TsfmProvider> = listOf(
        KronosProvider(),
        FinTextChronosProvider(),
        ChronosBoltProvider(),
        TimesFMProvider()
    ).sortedBy { it.priority }.reversed()

    fun selectProvider(): TsfmProvider {
        return providers
            .firstOrNull { it.isAvailable() }
            ?: throw IllegalStateException("No TSFM provider is available. Ensure model files are downloaded.")
    }
}