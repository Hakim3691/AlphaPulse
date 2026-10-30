package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.db.ScreenedCoinEntity
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.data.model.LiquidationContext
import com.aistudio.alphapulse.data.model.MarketRegime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Feeds the campaign engine from AlphaPulse's **already-screened** coin list.
 *
 * Why this exists
 * ---------------
 * Crypto Trader Pro opened its pipeline with its own universe discovery: pull the
 * whole Binance perp list, validate spot + CoinGlass coverage, filter $50M-$750M
 * quote volume, measure L2 spread under 0.10%, fetch candles per symbol to derive
 * multi-horizon bias and a regime, then rank by pain score.
 *
 * AlphaPulse runs that selection upstream, with a stricter screen of its own (the
 * four institutional filters: $5M daily volume, taker-buy dominance, volume/OI
 * expansion, 40% displacement, plus the ten-day persistence rule) and forecasts the
 * price at every horizon. Re-discovering the universe inside the campaign engine
 * therefore duplicated work, could disagree with the list the user sees, and burned
 * REST weight on symbols AlphaPulse had already rejected.
 *
 * This bridge is the seam the campaign engine consumes instead: AlphaPulse's
 * `screened_coins` rows plus its `predictions`, mapped onto the [CryptoAsset] shape
 * the existing structures expect. No discovery, no volume/spread/CoinGlass
 * re-filtering, no candle-based bias recomputation.
 *
 * What is deliberately not carried over
 * -------------------------------------
 * - `orderBookSpreadPct`, `fundingRatePct`, `basisPremiumPct`: those come from
 *   Crypto Trader Pro's exchange/CoinGlass enrichment. AlphaPulse's screen does not
 *   collect them, and re-collecting them here would reintroduce the duplicate
 *   discovery pass this change removes. They are zeroed; venue guardrails simply
 *   stay quiet until the caller supplies a live measurement.
 * - Tactical/strategic bias: derived from AlphaPulse's forecast, never from candles.
 */
class ScreenedUniverseBridge {

    /**
     * Converts one screen + forecast snapshot into the campaign engine's universe.
     *
     * The upstream rank is preserved: AlphaPulse already ordered this list, so the
     * result is not re-sorted here.
     */
    fun toCryptoAssets(
        screened: List<ScreenedCoinEntity>,
        forecasts: Map<String, List<PredictionEntity>>
    ): List<CryptoAsset> {
        if (screened.isEmpty()) return emptyList()
        return screened
            .sortedBy { it.rank }
            .map { entity -> toCryptoAsset(entity, forecasts[entity.symbol].orEmpty()) }
    }

    /** One screen row plus its prediction ladder, on the engine's asset shape. */
    fun toCryptoAsset(
        entity: ScreenedCoinEntity,
        predictions: List<PredictionEntity>
    ): CryptoAsset {
        val forecast = ForecastBias.from(predictions.map { it.toForecastTarget() })
        val price = entity.lastPrice.takeIf { it > 0.0 } ?: entity.openPrice

        // AlphaPulse's daily range, volatility-scaled to each decision timeframe.
        // This is a derivation, not a new data fetch: the screen already measured
        // high/low for the day.
        val dayRange = max(0.0, entity.highPrice - entity.lowPrice)
        val atr5m = max(dayRange * sqrt(5.0 / MINUTES_PER_DAY), price * 0.0005)
        val atr1h = max(dayRange * sqrt(60.0 / MINUTES_PER_DAY), atr5m)
        val atr4h = max(dayRange * sqrt(240.0 / MINUTES_PER_DAY), atr1h)

        // Taker-buy dominance is the one directional reading the screen carries even
        // when the coin has no fresh forecast yet (50% is neutral).
        val flowBias = ((entity.realTakerBuyRatio - 50.0) * 2.0).coerceIn(-100.0, 100.0).toInt()

        return CryptoAsset(
            symbol = entity.symbol,
            baseAsset = entity.symbol.removeSuffix(QUOTE_SUFFIX),
            lastPrice = price,
            priceChange24h = entity.priceChangePercent,
            quoteVolume24h = entity.volume,
            orderBookSpreadPct = 0.0,
            fundingRatePct = 0.0,
            predictedFundingPct = 0.0,
            openInterestUsd = entity.exactOI,
            basisPremiumPct = 0.0,
            atr5m = atr5m,
            atr1h = atr1h,
            atr4h = atr4h,
            regime = classifyRegime(forecast),
            tacticalBias = forecast?.score ?: flowBias,
            strategicBias = forecast?.strategicScore ?: flowBias,
            primaryPainPath = describePath(forecast, flowBias),
            liquidationContext = LiquidationContext.EMPTY
        )
    }

    /**
     * AlphaPulse's forecast rows for one asset, reduced to the per-horizon targets the
     * campaign engine prices against. Newest prediction per horizon wins.
     */
    fun forecastTargetsFor(predictions: List<PredictionEntity>): List<ForecastTarget> =
        predictions
            .groupBy { it.horizonId }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.issuedTimestamp } }
            .map { it.toForecastTarget() }
            .sortedBy { it.minutes }

    /** §9 regime, from the forecast rather than a second candle pass. */
    private fun classifyRegime(forecast: ForecastBias?): MarketRegime {
        if (forecast == null) return MarketRegime.BALANCE
        val tactical = forecast.score
        val strategic = forecast.strategicScore
        return when {
            strategic >= 20 && tactical >= -19 -> MarketRegime.BULLISH_CONTINUATION
            strategic <= -20 && tactical <= 19 -> MarketRegime.BEARISH_CONTINUATION
            abs(strategic - tactical) >= 40 -> MarketRegime.TRANSITION
            else -> MarketRegime.BALANCE
        }
    }

    private fun describePath(forecast: ForecastBias?, flowBias: Int): String {
        if (forecast == null) {
            return "Awaiting AlphaPulse forecast (flow bias $flowBias)"
        }
        val nearest = forecast.targets.first()
        return "AlphaPulse ${nearest.horizonId} forecast ${formatPct(nearest.deltaPct)} " +
            "to ${formatPrice(nearest.predictedPrice)} (bias ${forecast.score})"
    }

    private fun PredictionEntity.toForecastTarget(): ForecastTarget {
        val deltaPct = if (basePrice > 0.0) ((predictedPrice - basePrice) / basePrice) * 100.0 else 0.0
        return ForecastTarget(
            horizonId = horizonId,
            label = horizonLabel,
            minutes = minutes,
            modelType = modelType,
            predictedPrice = predictedPrice,
            upperPrice = upperBound,
            lowerPrice = lowerBound,
            deltaPct = deltaPct,
            isBullish = deltaPct >= 0.0
        )
    }

    private fun formatPct(value: Double): String {
        val sign = if (value >= 0.0) "+" else ""
        return "$sign${Math.round(value * 100.0) / 100.0}%"
    }

    private fun formatPrice(value: Double): String = Math.round(value * 100000.0).div(100000.0).toString()

    private companion object {
        const val MINUTES_PER_DAY = 1440.0
        const val QUOTE_SUFFIX = "USDT"
    }
}
