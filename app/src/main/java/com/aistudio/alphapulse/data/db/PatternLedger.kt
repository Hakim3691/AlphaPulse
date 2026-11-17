package com.aistudio.alphapulse.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEntry
import kotlinx.coroutines.flow.Flow

/**
 * §73 / Contract C-23 — persistence for the secondary pattern ledger.
 *
 * Why this exists
 * ---------------
 * The pattern ledger must survive a restart and be isolated from the price ledger: the
 * price rows are the baseline contract and must not be touched by pattern learning. That
 * means its own table, its own key and its own DAO — not new nullable columns on
 * `predictions`, which would put the baseline schema at risk.
 *
 * The DDL lives here rather than inline in the migration so the migration, the entity and
 * its guard spec read one definition, exactly as the §71 ledger does.
 *
 * Spec Ref: §73 Pattern Prediction & Learning Ledger, C-23
 */
object PatternLedgerSchema {

    const val TABLE = "pattern_ledger"

    /** The row key. Not part of the directive's field list; the table needs one. */
    const val KEY_COLUMN = "id"

    /** Bookkeeping columns beside the directive's fields. */
    const val ISSUED_COLUMN = "issuedTimestamp"
    const val SETTLED_COLUMN = "settled"

    /**
     * The directive's `PatternLedgerEntry` fields, with `predictedPattern` flattened.
     * Order is the column order of the exports, so it is part of the contract.
     */
    val LEDGER_FIELDS: List<String> = listOf(
        "asset",
        "horizon",
        "targetTime",
        "predictedPatternId",
        "predictedPatternName",
        "predictedCategory",
        "confidence",
        "realizedPattern",
        "fitLoss",
        "status",
        "sgdAction"
    )

    /** The production 7 -> 8 statement, shared with the migration and its guard. */
    val DDL: String = buildString {
        append("CREATE TABLE IF NOT EXISTS `").append(TABLE).append("` (")
        append("`").append(KEY_COLUMN).append("` TEXT NOT NULL, ")
        append("`").append(ISSUED_COLUMN).append("` INTEGER NOT NULL, ")
        append("`asset` TEXT NOT NULL, ")
        append("`horizon` TEXT NOT NULL, ")
        append("`targetTime` INTEGER NOT NULL, ")
        append("`predictedPatternId` TEXT NOT NULL, ")
        append("`predictedPatternName` TEXT NOT NULL, ")
        append("`predictedCategory` TEXT NOT NULL, ")
        append("`confidence` REAL NOT NULL, ")
        append("`realizedPattern` TEXT, ")
        append("`fitLoss` REAL, ")
        append("`status` TEXT NOT NULL, ")
        append("`sgdAction` TEXT NOT NULL, ")
        append("`").append(SETTLED_COLUMN).append("` INTEGER NOT NULL, ")
        append("PRIMARY KEY(`").append(KEY_COLUMN).append("`))")
    }

    /** Separate table: weights are a model, not an audit row. */
    const val WEIGHTS_TABLE = "pattern_sgd_weights"

    val WEIGHTS_DDL: String =
        "CREATE TABLE IF NOT EXISTS `$WEIGHTS_TABLE` (" +
            "`compositeKey` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
            "`horizonId` TEXT NOT NULL, `weightsCsv` TEXT NOT NULL, " +
            "`learningRate` REAL NOT NULL, `steps` INTEGER NOT NULL, " +
            "`lastLoss` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`compositeKey`))"
}

/** One persisted pattern prediction / settlement. */
@Entity(tableName = PatternLedgerSchema.TABLE)
data class PatternLedgerEntity(
    @PrimaryKey val id: String,
    val issuedTimestamp: Long,
    val asset: String,
    val horizon: String,
    val targetTime: Long,
    val predictedPatternId: String,
    val predictedPatternName: String,
    val predictedCategory: String,
    val confidence: Double,
    val realizedPattern: String?,
    val fitLoss: Double?,
    val status: String,
    val sgdAction: String,
    val settled: Boolean
) {
    /** The screen and the engine both speak the domain record. */
    fun toEntry(): PatternLedgerEntry = PatternLedgerEntry(
        id = id,
        asset = asset,
        horizon = horizon,
        targetTime = targetTime,
        issuedTimestamp = issuedTimestamp,
        predictedPatternId = predictedPatternId,
        predictedPatternName = predictedPatternName,
        predictedCategory = predictedCategory,
        confidence = confidence,
        realizedPattern = realizedPattern,
        fitLoss = fitLoss,
        status = status,
        sgdAction = sgdAction,
        settled = settled
    )

    companion object {
        fun from(entry: PatternLedgerEntry): PatternLedgerEntity = PatternLedgerEntity(
            id = entry.id,
            issuedTimestamp = entry.issuedTimestamp,
            asset = entry.asset,
            horizon = entry.horizon,
            targetTime = entry.targetTime,
            predictedPatternId = entry.predictedPatternId,
            predictedPatternName = entry.predictedPatternName,
            predictedCategory = entry.predictedCategory,
            confidence = entry.confidence,
            realizedPattern = entry.realizedPattern,
            fitLoss = entry.fitLoss,
            status = entry.status,
            sgdAction = entry.sgdAction,
            settled = entry.settled
        )
    }
}

/**
 * One online SGD head per asset and horizon.
 *
 * `compositeKey` is `asset#horizon`; the CSV holds the class-affinity matrix. Relearning
 * replaces the row instead of appending a contradictory second model.
 */
@Entity(tableName = PatternLedgerSchema.WEIGHTS_TABLE)
data class PatternSgdWeightsEntity(
    @PrimaryKey val compositeKey: String,
    val symbol: String,
    val horizonId: String,
    val weightsCsv: String,
    val learningRate: Double,
    val steps: Int,
    val lastLoss: Double,
    val updatedAt: Long
)

@Dao
interface PatternLedgerDao {

    /** The selected asset's rows, oldest boundary first, so the table reads like a clock. */
    @Query("SELECT * FROM pattern_ledger WHERE asset = :asset ORDER BY targetTime ASC")
    fun flowForAsset(asset: String): Flow<List<PatternLedgerEntity>>

    @Query("SELECT * FROM pattern_ledger WHERE asset = :asset ORDER BY targetTime ASC")
    suspend fun forAsset(asset: String): List<PatternLedgerEntity>

    /** Unsettled rows for one horizon, used to decide whether a new one may be issued. */
    @Query(
        "SELECT * FROM pattern_ledger WHERE asset = :asset AND horizon = :horizon " +
            "AND settled = 0 ORDER BY targetTime ASC"
    )
    suspend fun openFor(asset: String, horizon: String): List<PatternLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PatternLedgerEntity)

    @Update
    suspend fun update(entry: PatternLedgerEntity)

    /**
     * Keep the newest [keep] rows per asset. The engine issues every horizon every bar, so
     * an append-only table would grow without bound on a 24/7 device.
     */
    @Query(
        "DELETE FROM pattern_ledger WHERE asset = :asset AND id NOT IN " +
            "(SELECT id FROM pattern_ledger WHERE asset = :asset ORDER BY targetTime DESC LIMIT :keep)"
    )
    suspend fun prune(asset: String, keep: Int)

    @Query("DELETE FROM pattern_ledger")
    suspend fun clearAll()

    @Query("SELECT * FROM pattern_sgd_weights WHERE compositeKey = :key")
    suspend fun weights(key: String): PatternSgdWeightsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWeights(weights: PatternSgdWeightsEntity)

    @Query("DELETE FROM pattern_sgd_weights")
    suspend fun clearWeights()
}
