package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.db.ScreenedCoinEntity
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.data.model.LiquidationContext
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.domain.campaign.SignalEvidence
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
 *
 * C-57: the forecast a campaign is priced from is the one this coin's own settlements
 * have proven. A horizon that has not cleared its own 85% floor (`SignalEvidence`) is
 * withheld, so a board cannot be planned on a horizon that never called its cone. A coin
 * with no proven horizon is not part of the campaign universe at all: it is removed from
 * the bridged list rather than carried as a neutral asset that can only disappoint.
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
            // C-57: an unproven coin is removed from the campaign universe. Carrying it
            // as a neutral asset meant a board could be shaped for a coin that had never
            // called a cone, and the only reading it added was taker flow.
            .filter { isProven(forecasts[it.symbol].orEmpty()) }
            .map { entity -> toCryptoAsset(entity, forecasts[entity.symbol].orEmpty()) }
    }

    /**
     * C-57: true when at least one of this coin's price horizons has proven itself.
     *
     * This is the campaign universe's admission test, not a display filter: an unproven
     * coin cannot be planned, so it does not enter the list in the first place.
     */
    fun isProven(predictions: List<PredictionEntity>): Boolean =
        evidenceFor(predictions).any { it.qualified }

    /** One screen row plus its prediction ladder, on the engine's asset shape. */
    fun toCryptoAsset(
        entity: ScreenedCoinEntity,
        predictions: List<PredictionEntity>
    ): CryptoAsset {
        // C-55: only proven horizons shape the bias, so an unproven forecast cannot tilt
        // a board's direction or its regime.
        val evidence = evidenceFor(predictions)
        val forecast = ForecastBias.from(forecastTargetsFor(predictions, evidence))
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
            primaryPainPath = describePath(forecast, flowBias, evidence),
            liquidationContext = LiquidationContext.EMPTY
        )
    }

    /**
     * C-55: this coin's settlement record, one row per horizon it has issued.
     *
     * The unit is the horizon, not the coin, because the forecast is issued and graded
     * per horizon: a coin's 5m cone can be excellent while its 1h cone is worse than a
     * coin flip, and a blended rate hides exactly that.
     */
    fun evidenceFor(predictions: List<PredictionEntity>): List<SignalEvidence.HorizonEvidence> =
        predictions.groupBy { it.horizonId }
            .map { (horizonId, rows) ->
                val settled = rows.filter { it.settled }
                SignalEvidence.assess(
                    horizonId = horizonId,
                    settled = settled.size,
                    hits = settled.count { it.inCone == true }
                )
            }
            .sortedBy { it.horizonId }

    /**
     * C-55: the predictions whose horizon this coin has proven, and nothing else.
     *
     * Fail-closed: a horizon with no evidence is withheld rather than trusted.
     */
    fun admittedPredictions(
        predictions: List<PredictionEntity>,
        evidence: List<SignalEvidence.HorizonEvidence> = evidenceFor(predictions)
    ): List<PredictionEntity> {
        val proven = evidence.filter { it.qualified }.map { it.horizonId }.toSet()
        return predictions.filter { it.horizonId in proven }
    }

    /**
     * AlphaPulse's forecast rows for one asset, reduced to the per-horizon targets the
     * campaign engine prices against. Newest prediction per horizon wins, and only a
     * horizon with evidence behind it is admitted at all (C-55).
     */
    fun forecastTargetsFor(
        predictions: List<PredictionEntity>,
        evidence: List<SignalEvidence.HorizonEvidence> = evidenceFor(predictions)
    ): List<ForecastTarget> =
        admittedPredictions(predictions, evidence)
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

    private fun describePath(
        forecast: ForecastBias?,
        flowBias: Int,
        evidence: List<SignalEvidence.HorizonEvidence>
    ): String {
        if (forecast == null) {
            // C-57: the evidence-withheld sentence is gone. Such a coin never reaches the
            // campaign universe (`toCryptoAssets` filters it out); this branch remains for
            // a direct single-asset bridge, where it states only what is awaited.
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
