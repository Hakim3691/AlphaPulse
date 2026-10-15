package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "predictions")
data class PredictionEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val horizonId: String,
    val horizonLabel: String,
    val modelType: String,
    val minutes: Int,
    val issuedTimestamp: Long,
    val targetTimestamp: Long,
    val basePrice: Double,
    val predictedPrice: Double,
    val upperBound: Double,
    val lowerBound: Double,
    val muK: Double,
    val muP: Double,
    val muAlpha: Double,
    val settled: Boolean,
    val realizedPrice: Double? = null,
    val errorDollar: Double? = null,
    val errorPct: Double? = null,
    val inCone: Boolean? = null,
    val learningAction: String = "Pending Settlement"
)

@Entity(tableName = "screened_coins")
data class ScreenedCoinEntity(
    @PrimaryKey val symbol: String,
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
    val filter1Passed: Boolean,
    val filter1Val: String,
    val filter2Passed: Boolean,
    val filter2Val: String,
    val filter3Passed: Boolean,
    val filter3Val: String,
    val filter4Passed: Boolean,
    val filter4Val: String,
    val rank: Int
)
