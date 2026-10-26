package com.aistudio.alphapulse.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/*** PredictionEntity - Links every prediction to ModelVersion for scientific reproducibility.
 * See §55: Per-component model versioning. Each prediction records which version of
 * Quant Engine, TSFM, LOB Model, Decision Router, SLM, and Feature Schema produced it.
 */
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
    /** Drift term of mounted member W4 (Chronos-Bolt); 0.0 while unmounted. */
    @ColumnInfo(defaultValue = "0.0") val muW4: Double = 0.0,
    /** Drift term of mounted member W5 (TimesFM); 0.0 while unmounted. */
    @ColumnInfo(defaultValue = "0.0") val muW5: Double = 0.0,
    val settled: Boolean,
    val realizedPrice: Double? = null,
    val errorDollar: Double? = null,
    val errorPct: Double? = null,
    val inCone: Boolean? = null,
    val learningAction: String = "Pending Settlement",
    /** Model provider that generated this forecast (for §19 independent cross-validation). */
    val modelProvider: String = "",
    val versionTag: String = "" // Links to ModelVersion for §55 reproducibility
)

/*** Remaining entities ***/

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

@Entity(tableName = "trade_buffer")
data class TradeBufferEntity(
    @PrimaryKey val tradeId: Long,
    val symbol: String,
    val price: Double,
    val quantity: Double,
    val quoteVolume: Double,
    val buyerMaker: Boolean,
    val timestamp: Long
)

@Entity(tableName = "order_book_deltas")
data class OrderBookDeltaEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val updateId: Long,
    val side: String,
    val price: Double,
    val quantity: Double,
    val timestamp: Long
)

@Entity(tableName = "liquidation_heatmap")
data class LiquidationHeatmapEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val price: Double,
    val notional: Double,
    val side: String,
    val timestamp: Long
)

@Entity(tableName = "volume_profile_snapshots")
data class VolumeProfileSnapshotEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val pocPrice: Double,
    val vahPrice: Double,
    val valPrice: Double,
    val vwap: Double,
    val timestamp: Long
)

/*** New entities for §47-48, §54, §55 ***/

@Entity(tableName = "model_registry")
data class ModelRegistryEntity(
    @PrimaryKey val modelId: String,
    val version: String,
    val checksumSha256: String,
    val parameterCount: Int,
    val sizeBytes: Long,
    val quantization: String,
    val backend: String,
    val latencyMs: Int,
    val accuracyPct: Double,
    val isActive: Boolean = false,
    val licenseStatus: String = "pending",
    val downloadTimestamp: Long = System.currentTimeMillis(),
    val lastActivated: Long = 0L,
    val notes: String = ""
)

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val sessionId: String = "current",
    val startedAt: Long = System.currentTimeMillis(),
    val lastActive: Long = System.currentTimeMillis(),
    val weightsSnapshot: String = "",
    val predictionsSnapshot: String = "",
    val orderBookStateSnapshot: String = "",
    val regimeState: String = "Mean-Reverting",
    val symbol: String = "",
    val versionTag: String = "1.0"
)

/*** Ensemble Weights per symbol/horizon (SPEC §20: persistent per-coin/per-horizon weight tables) ***/

@Entity(tableName = "ensemble_weights")
data class EnsembleWeightsEntity(
    @PrimaryKey val compositeKey: String,  // "symbol#horizon"
    val symbol: String,
    val horizonId: String,
    val weightK: Double,
    val weightP: Double,
    val weightAlpha: Double,
    val weight4: Double,
    val weight5: Double,
    val lastUpdated: Long = System.currentTimeMillis(),
    val modelVersion: String = ""
)

/*** Remaining entities ***/