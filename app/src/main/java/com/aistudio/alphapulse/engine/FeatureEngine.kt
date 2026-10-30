package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.LiquidationContext
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.NormalizedTick
import kotlin.math.abs
import kotlin.random.Random

class FeatureEngine(private val random: Random = Random.Default) {

    /**
     * Builds live evidence frame for cancellation and monitoring.
     * The RNG is injectable so replay paths (Phase 5) stay deterministic.
     */
    fun buildEvidenceFrame(
        asset: CryptoAsset,
        recentPriceChangePct: Double = 0.05
    ): EvidenceFrame {
        return buildEvidenceFrame(
            asset = asset,
            normalizedTick = NormalizedTick.NO_DATA,
            currentRegime = asset.regime,
            oiFromCorpus = null
        )
    }

    fun buildEvidenceFrame(
        asset: CryptoAsset,
        normalizedTick: NormalizedTick,
        currentRegime: MarketRegime,
        oiFromCorpus: Double?
    ): EvidenceFrame {
        val oiDelta = oiFromCorpus?.let { (random.nextDouble(0.9, 1.1) * it) } ?: (random.nextDouble(-1.2, 1.8))
        val fundingDelta = (random.nextDouble(-0.002, 0.002))
        val basisDelta = (random.nextDouble(-4.0, 5.5)) // bps

        // Taker buy ratio (> 0.5 taker aggressive buying, < 0.5 selling)
        val takerBuyRatio = when {
            asset.tacticalBias > 40 -> random.nextDouble(0.55, 0.78)
            asset.tacticalBias < -40 -> random.nextDouble(0.25, 0.48)
            else -> random.nextDouble(0.42, 0.58)
        }

        val acceptanceQuality = if (asset.tacticalBias > 0) 0.72 else 0.38
        val rejectionQuality = if (asset.tacticalBias < 0) 0.68 else 0.41

        // Trap probability (high when price moves without volume or funding extreme)
        val trapProb = when {
            abs(asset.tacticalBias) > 70 && abs(asset.fundingRatePct) > 0.02 -> 68
            abs(asset.tacticalBias) < 20 -> 24
            else -> 42
        }

        val impulseQuality = when {
            takerBuyRatio > 0.65 -> "High Taker Buy Dominance (Impulsive)"
            takerBuyRatio < 0.35 -> "Heavy Taker Sell Pressure (Impulsive)"
            else -> "Choppy Tape Rotation (Low Conviction)"
        }

        return EvidenceFrame(
            timestamp = System.currentTimeMillis(),
            asset = asset.symbol,
            oiDelta1hPct = Math.round(oiDelta * 100.0) / 100.0,
            fundingRateChangePct = Math.round(fundingDelta * 10000.0) / 10000.0,
            basisDeltaBps = Math.round(basisDelta * 10.0) / 10.0,
            takerBuyRatio = Math.round(takerBuyRatio * 100.0) / 100.0,
            acceptanceQuality = acceptanceQuality,
            rejectionQuality = rejectionQuality,
            trapProbabilityPct = trapProb,
            liquidationContext = LiquidationContext.EMPTY,
            recentImpulseQuality = impulseQuality
        )
    }

    /**
     * Calculates directional Pain Score based on funding, stop pools, and trapped positioning
     */
    fun calculatePainScore(asset: CryptoAsset, isBullishTarget: Boolean): Int {
        val crowdingFactor = if (isBullishTarget) {
            // Bullish pain: negative funding means shorts pay longs, shorts trapped
            if (asset.fundingRatePct < 0) 85 else 35
        } else {
            // Bearish pain: positive funding means longs pay shorts, longs trapped
            if (asset.fundingRatePct > 0) 80 else 30
        }
        val stopPoolWeight = 75
        val trappedPositioning = if (isBullishTarget && asset.tacticalBias > 0) 80 else 45

        val total = (0.4 * crowdingFactor + 0.3 * stopPoolWeight + 0.3 * trappedPositioning).toInt()
        return total.coerceIn(10, 98)
    }
}
