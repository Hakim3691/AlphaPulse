package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.MarketRegime

data class OrderBookLevel(val price: Double, val size: Double)
data class OrderBookSnapshot(
    val asset: String,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>,
    val spreadPct: Double
)
data class TakerTrade(
    val id: Long,
    val timestamp: Long,
    val price: Double,
    val qty: Double,
    val isBuyerMaker: Boolean // true = taker sell, false = taker buy
)

/**
 * Offline seed universe ONLY. Per Spec §4 every display input must come from the live
 * Binance/CoinGlass public feeds, so this class no longer contains any simulated tick,
 * order-book or trade generators — [LiveMarketDataProvider] and [UniverseScreener] own
 * the real data paths. The seed exists solely as an instant first-frame placeholder
 * (clearly labeled in the UI) and for unit-test determinism.
 */
class MarketDataRepository {

    // Initial universe of mid-caps strictly filtered by volume $50M - $750M
    private val initialUniverse = listOf(
        CryptoAsset(
            symbol = "SOLUSDT",
            baseAsset = "SOL",
            lastPrice = 148.65,
            priceChange24h = 4.25,
            quoteVolume24h = 620_000_000.0,
            orderBookSpreadPct = 0.02,
            fundingRatePct = -0.0125, // negative funding (shorts paying)
            predictedFundingPct = -0.0180,
            openInterestUsd = 285_000_000.0,
            basisPremiumPct = 0.08,
            atr5m = 0.85,
            atr1h = 2.40,
            atr4h = 6.80,
            regime = MarketRegime.BULLISH_CONTINUATION,
            tacticalBias = 68,
            strategicBias = 75,
            primaryPainPath = "Ascending Sweep to $154.50 Supply"
        ),
        CryptoAsset(
            symbol = "NEARUSDT",
            baseAsset = "NEAR",
            lastPrice = 5.24,
            priceChange24h = -2.15,
            quoteVolume24h = 185_000_000.0,
            orderBookSpreadPct = 0.04,
            fundingRatePct = 0.0240, // positive funding (longs paying)
            predictedFundingPct = 0.0310,
            openInterestUsd = 88_000_000.0,
            basisPremiumPct = -0.05,
            atr5m = 0.045,
            atr1h = 0.14,
            atr4h = 0.38,
            regime = MarketRegime.BEARISH_CONTINUATION,
            tacticalBias = -55,
            strategicBias = -62,
            primaryPainPath = "Breakdown Cascade below $4.95"
        ),
        CryptoAsset(
            symbol = "AVAXUSDT",
            baseAsset = "AVAX",
            lastPrice = 28.92,
            priceChange24h = 0.65,
            quoteVolume24h = 240_000_000.0,
            orderBookSpreadPct = 0.03,
            fundingRatePct = 0.0050,
            predictedFundingPct = 0.0080,
            openInterestUsd = 120_000_000.0,
            basisPremiumPct = 0.02,
            atr5m = 0.18,
            atr1h = 0.55,
            atr4h = 1.45,
            regime = MarketRegime.BALANCE,
            tacticalBias = 12,
            strategicBias = -5,
            primaryPainPath = "Range Rotation $27.80 - $29.80"
        ),
        CryptoAsset(
            symbol = "SUIUSDT",
            baseAsset = "SUI",
            lastPrice = 1.945,
            priceChange24h = 7.80,
            quoteVolume24h = 390_000_000.0,
            orderBookSpreadPct = 0.03,
            fundingRatePct = -0.0210,
            predictedFundingPct = -0.0350,
            openInterestUsd = 165_000_000.0,
            basisPremiumPct = 0.15,
            atr5m = 0.022,
            atr1h = 0.065,
            atr4h = 0.18,
            regime = MarketRegime.BULLISH_CONTINUATION,
            tacticalBias = 82,
            strategicBias = 78,
            primaryPainPath = "Compression Breakout past $2.05"
        ),
        CryptoAsset(
            symbol = "INJUSDT",
            baseAsset = "INJ",
            lastPrice = 21.30,
            priceChange24h = -3.40,
            quoteVolume24h = 115_000_000.0,
            orderBookSpreadPct = 0.05,
            fundingRatePct = 0.0180,
            predictedFundingPct = 0.0220,
            openInterestUsd = 62_000_000.0,
            basisPremiumPct = -0.03,
            atr5m = 0.16,
            atr1h = 0.48,
            atr4h = 1.25,
            regime = MarketRegime.TRANSITION,
            tacticalBias = -38,
            strategicBias = 24,
            primaryPainPath = "Split: Reclaim $22.20 vs Flush $20.40"
        ),
        CryptoAsset(
            symbol = "RENDERUSDT",
            baseAsset = "RENDER",
            lastPrice = 6.48,
            priceChange24h = 1.90,
            quoteVolume24h = 145_000_000.0,
            orderBookSpreadPct = 0.04,
            fundingRatePct = -0.0080,
            predictedFundingPct = -0.0110,
            openInterestUsd = 74_000_000.0,
            basisPremiumPct = 0.04,
            atr5m = 0.055,
            atr1h = 0.16,
            atr4h = 0.42,
            regime = MarketRegime.TRANSITION,
            tacticalBias = 32,
            strategicBias = -45,
            primaryPainPath = "Tactical Bounce to $6.85 Strategic Supply"
        )
    )

    fun getInitialUniverse(): List<CryptoAsset> = initialUniverse
}
