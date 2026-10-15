package com.example.data.model

data class FilterBadgeData(
    val passed: Boolean,
    val valueStr: String
)

data class ScreenedCoin(
    val symbol: String,
    val lastPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val openPrice: Double,
    val volume: Double,
    val realTakerBuyRatio: Double,
    val displacement: Double,
    val exactOI: Double,
    val volToOiRatio: Double,
    val priceChangePercent: Double,
    val calmarRatio: Double,
    val sharpeRatio: Double,
    val annualizedVol: Double,
    val maxDrawdown: Double,
    val daysPassedAll: Int,
    val passedTodayAll: Boolean,
    val passed10DRule: Boolean,
    val filter1: FilterBadgeData,
    val filter2: FilterBadgeData,
    val filter3: FilterBadgeData,
    val filter4: FilterBadgeData
)

data class DepthTier(
    val price: Double,
    val quantity: Double
)

data class DepthSnapshot(
    val bids: List<DepthTier>,
    val asks: List<DepthTier>
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

data class HorizonConfig(
    val id: String,
    val label: String,
    val minutes: Int,
    val ms: Long,
    val modelType: String
)

data class ForecastTarget(
    val horizonId: String,
    val label: String,
    val minutes: Int,
    val modelType: String,
    val predictedPrice: Double,
    val upperPrice: Double,
    val lowerPrice: Double,
    val deltaPct: Double,
    val isBullish: Boolean
)

data class ModelState(
    var weightK: Double = 0.30,
    var weightP: Double = 0.35,
    var weightAlpha: Double = 0.35,
    var driftBias: Double = 0.0,
    var volMultiplier: Double = 1.0,
    val learningRate: Double = 0.015,
    var mode: String = "Baseline Init"
)

data class ChartPoint(
    val timeLabel: String,
    val realizedPrice: Double?,
    val forecastPrice: Double?,
    val upperCone: Double?,
    val lowerCone: Double?
)
