package com.aistudio.alphapulse.data.model

enum class MarketRegime(val label: String, val shortDesc: String) {
    BULLISH_CONTINUATION("Bullish Continuation", "HTF rising/reclaimed; buy pullbacks"),
    BEARISH_CONTINUATION("Bearish Continuation", "HTF declining/broken; sell rallies"),
    TRANSITION("Transition", "Stretched/damaged structure; split asymmetrical bias"),
    BALANCE("Balance", "Rotating in value range; mean reversion favored")
}

enum class NormalizedTick(
    val label: String,
    val volatility: Double,
    val impulse: Double
) {
    LOW_VOL(label = "low_vol", volatility = 0.0005, impulse = 0.2),
    HIGH_VOL(label = "high_vol", volatility = 0.004, impulse = 0.8),
    SAY_PRICE(label = "say_price", volatility = 0.0, impulse = 0.5),
    NO_DATA(label = "no_data", volatility = 0.0, impulse = 0.0);

    companion object {
        const val TICK_LOW_VOL = 0.0005
        const val TICK_HIGH_VOL = 0.004
        const val TICK_FLAT = 0.0
        const val TICK_LOW_IMPACT = 0.2
        const val TICK_HIGH_IMPACT = 0.8
        const val TICK_MID_IMPACT = 0.5
    }
}

enum class ZoneType(val displayName: String, val isLongAffinity: Boolean) {
    HTF_SUPPORT("HTF Support Band", true),
    HTF_RESISTANCE("HTF Resistance Band", false),
    LTF_PIVOT("LTF Dynamic Pivot", true),
    RECLAIM_ZONE("Reclaim Zone", true),
    BREAKDOWN_ZONE("Breakdown Retest", false),
    STOP_POOL("Stop Pool Cluster", false)
}

data class LevelZone(
    val id: String,
    val zoneType: ZoneType,
    val priceLow: Double,
    val priceHigh: Double,
    val timeframe: String, // "4h", "1d", "1h", "15m", "5m"
    val confidence: Int,   // 0-100
    val touches: Int,
    val freshnessScore: Int // 0-100 (decays automatically over time)
) {
    val center: Double get() = (priceLow + priceHigh) / 2.0
    val width: Double get() = priceHigh - priceLow
}

data class LevelMap(
    val asset: String,
    val zones: List<LevelZone>,
    val dominantPivot: Double,
    val damageScore: Int, // 0-100
    val confidenceSummary: Int,
    val timestamp: Long
)

enum class PathDirection { BULLISH, BEARISH }

data class CandidatePath(
    val pathId: String,
    val direction: PathDirection,
    val targetDescription: String,
    val targetPrice: Double,
    val painScore: Int,       // 0-100
    val efficiencyScore: Int, // 0-100
    val cleanlinessScore: Int,// 0-100
    val probabilityScore: Int // 0-100
) {
    val combinedPriority: Double
        get() = (painScore * 0.35 + efficiencyScore * 0.25 + cleanlinessScore * 0.2 + probabilityScore * 0.2)
}

data class CoinCalibration(
    val symbol: String,
    val impulseAsymmetry: Double,     // Upward impulse vel / Downward impulse vel
    val wickAsymmetry: Double,        // Upside wick rejection vs Downside
    val followThroughAsymmetry: Double,
    val crowdingElasticity: Double,
    val meanReversionHalfLifeMinutes: Int,
    val typicalSlippageBps: Double
)

data class CryptoAsset(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String = "USDT",
    val lastPrice: Double,
    val priceChange24h: Double,
    val quoteVolume24h: Double,      // in USD ($50M to $750M mid-caps)
    val orderBookSpreadPct: Double,  // < 0.10%
    val fundingRatePct: Double,      // Periodic 8h funding
    val predictedFundingPct: Double,
    val openInterestUsd: Double,
    val basisPremiumPct: Double,
    val atr5m: Double,
    val atr1h: Double,
    val atr4h: Double,
    val regime: MarketRegime,
    val tacticalBias: Int,           // -100 to +100
    val strategicBias: Int,          // -100 to +100
    val primaryPainPath: String,
    val venueHealthScore: Int = 98,  // 0-100
    /** Liquidation context for the §26 asymmetry read; empty until measured. */
    val liquidationContext: LiquidationContext = LiquidationContext.EMPTY
) {
    val isMidCapQualified: Boolean
        get() = quoteVolume24h in 50_000_000.0..750_000_000.0 && orderBookSpreadPct < 0.10
}
