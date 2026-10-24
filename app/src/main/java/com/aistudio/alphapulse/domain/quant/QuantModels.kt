package com.aistudio.alphapulse.domain.quant

/**
 * Pure data structures owned by the quantitative domain layer.
 *
 * This package must remain free of Android and UI dependencies so the quant
 * engine can be exercised by plain JVM unit tests (spec section 52, unit group).
 * The classes below were extracted from `data.model` in Phase 0.
 */

data class DepthTier(
    val price: Double,
    val quantity: Double
)

data class KlineData(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double,
    val takerBuyQuoteVolume: Double
)

data class DepthSnapshot(
    val bids: List<DepthTier>,
    val asks: List<DepthTier>,
    val timestamp: Long = System.currentTimeMillis()
)

data class LiquidityPainPathResult(
    val muPath: Double,
    val spreadSkew: Double,
    val source: String
)

data class MarkovRegimeResult(
    val regime: String,
    val muKinematic: Double,
    val regimeVolScale: Double,
    val kurtosis: Double,
    val cutoff: Double
)

data class TemporalAttentionResult(
    val muKinematic: Double,
    val upperConeSkew: Double,
    val lowerConeSkew: Double
)

/** Result of a volume-profile + VWAP computation over a set of klines. */
data class VPResult(
    val poc: Double = 0.0,
    val vwap: Double = 0.0,
    val vah: Double = 0.0,
    val valPrice: Double = 0.0,
    val rotationTarget: Double? = null,
    val rotationRuleTriggered: Boolean = false,
    val signalVP: Double = 0.0
)

data class ConfluenceAlphaResult(
    val muAlpha: Double,
    val vwap: Double,
    val poc: Double,
    val vah: Double = 0.0,
    val valPrice: Double = 0.0,
    val rotationTarget: Double? = null,
    val rotationRuleTriggered: Boolean = false,
    val confidence: Double = 0.70
)

/**
 * Online ensemble weights for the Tri-Factor engine (K / P / alpha).
 * Phase 1 will persist this state per symbol and per horizon; today it acts
 * as the value object shared by the engine, repository and ViewModel.
 */
data class ModelState(
    var weightK: Double = 0.30,
    var weightP: Double = 0.35,
    var weightAlpha: Double = 0.35,
    var driftBias: Double = 0.0,
    var volMultiplier: Double = 1.0,
    val learningRate: Double = 0.015,
    var mode: String = "Baseline Init",
    /**
     * Share of the mounted W4 member (Chronos-Bolt TSFM); 0.0 until downloaded,
     * initialised to EnsembleWeighting.MOUNTED_MEMBER_WEIGHT on mount, then
     * adapted online by EnsembleWeighting.applySettledSgdStep.
     */
    var weight4: Double = 0.0,
    /**
     * Share of the mounted W5 member (TimesFM TSFM); 0.0 until downloaded,
     * initialised to EnsembleWeighting.MOUNTED_MEMBER_WEIGHT on mount, then
     * adapted online by EnsembleWeighting.applySettledSgdStep.
     */
    var weight5: Double = 0.0
)
