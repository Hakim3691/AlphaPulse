package com.aistudio.alphapulse.data.model

/**
 * Application-layer data models (UI-facing DTOs shared between repository,
 * ViewModel and Compose components).
 *
 * Quantitative-domain value objects (depth, pain-path, regime, ensemble
 * weights) live in `com.aistudio.alphapulse.domain.quant.QuantModels` so the
 * quant engine stays a pure Kotlin surface with zero Android dependencies.
 */

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

data class ChartPoint(
    val timeLabel: String,
    val realizedPrice: Double?,
    val forecastPrice: Double?,
    val upperCone: Double?,
    val lowerCone: Double?
)
