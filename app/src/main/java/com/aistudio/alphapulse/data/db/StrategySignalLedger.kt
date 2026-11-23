package com.aistudio.alphapulse.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.aistudio.alphapulse.domain.strategy.SignalDirection
import com.aistudio.alphapulse.domain.strategy.StrategySignal
import kotlinx.coroutines.flow.Flow

/**
 * §74 / Contract C-56 — persistence for the §3/§74 quantitative strategy signals.
 *
 * Why this exists
 * ---------------
 * The nine quantitative models and the seven technical screeners were executable, but
 * their output lived only inside `AssetEvaluationResult`: the board painted whatever the
 * last cycle computed, and the previous cycle's call — its entry, its stop, its
 * invalidation — was gone. That made §74's library impossible to audit after the fact and
 * impossible to grade later, while the price and pattern ledgers keep every row they
 * issue.
 *
 * This ledger is the §74 record, in its own table and its own DAO so the price ledger's
 * baseline schema is untouched (C-23's rule). One row is written per strategy per bar the
 * series evaluated, keyed by `asset|strategy|barOpenTime`, so re-running the same bar
 * replaces that row instead of appending a duplicate and a ten-second cycle cannot inflate
 * the table.
 *
 * Spec Ref: §74 Auxiliary Confluence Screeners & Quantitative Strategy Models, C-56
 */
object StrategySignalSchema {

    const val TABLE = "strategy_signal_ledger"

    /** The row key: `asset|strategyId|barOpenTime`. */
    const val KEY_COLUMN = "id"

    /** The column order is the record's contract; exports and the guard spec read it. */
    val LEDGER_FIELDS: List<String> = listOf(
        "asset",
        "strategyId",
        "strategyName",
        "direction",
        "entry",
        "exit",
        "invalidation",
        "distanceAtr",
        "zoneTouches",
        "detail",
        "barOpenTime",
        "issuedTimestamp"
    )

    /** The bar's own key, so one bar is one row per strategy. */
    fun rowKey(asset: String, strategyId: String, barOpenTime: Long): String =
        "$asset|$strategyId|$barOpenTime"

    /** The production 11 -> 12 statement, shared with the migration and its guard. */
    val DDL: String = buildString {
        append("CREATE TABLE IF NOT EXISTS `").append(TABLE).append("` (")
        append("`id` TEXT NOT NULL, ")
        append("`asset` TEXT NOT NULL, ")
        append("`strategyId` TEXT NOT NULL, ")
        append("`strategyName` TEXT NOT NULL, ")
        append("`direction` TEXT NOT NULL, ")
        append("`entry` REAL, ")
        append("`exit` REAL, ")
        append("`invalidation` REAL, ")
        append("`distanceAtr` REAL, ")
        append("`zoneTouches` INTEGER NOT NULL, ")
        append("`detail` TEXT NOT NULL, ")
        append("`barOpenTime` INTEGER NOT NULL, ")
        append("`issuedTimestamp` INTEGER NOT NULL, ")
        append("PRIMARY KEY(`id`))")
    }
}

/** One §74 signal row: the rule, its verdict and the levels it defined, for one bar. */
@Entity(tableName = StrategySignalSchema.TABLE, primaryKeys = ["id"])
data class StrategySignalLedgerEntity(
    val id: String,
    val asset: String,
    val strategyId: String,
    val strategyName: String,
    /** [SignalDirection] name, stored as text so the row survives an enum reorder. */
    val direction: String,
    val entry: Double?,
    val exit: Double?,
    val invalidation: Double?,
    val distanceAtr: Double?,
    val zoneTouches: Int,
    val detail: String,
    val barOpenTime: Long,
    val issuedTimestamp: Long
)

/**
 * Maps one evaluated signal onto its bar-keyed row.
 *
 * A NEUTRAL reading is recorded too: "this rule saw nothing on this bar" is evidence, and
 * omitting it would make the ledger's coverage look better than it is.
 */
fun StrategySignal.toLedgerRow(
    asset: String,
    barOpenTime: Long,
    issuedTimestamp: Long
): StrategySignalLedgerEntity = StrategySignalLedgerEntity(
    id = StrategySignalSchema.rowKey(asset, id, barOpenTime),
    asset = asset,
    strategyId = id,
    strategyName = name,
    direction = direction.name,
    entry = entry,
    exit = exit,
    invalidation = invalidation,
    distanceAtr = distanceAtr,
    zoneTouches = zoneTouches,
    detail = detail,
    barOpenTime = barOpenTime,
    issuedTimestamp = issuedTimestamp
)

/** The row back on the domain shape, so a reader and the board cannot drift apart. */
fun StrategySignalLedgerEntity.toSignal(): StrategySignal = StrategySignal(
    id = strategyId,
    name = strategyName,
    direction = runCatching { SignalDirection.valueOf(direction) }
        .getOrDefault(SignalDirection.NEUTRAL),
    entry = entry,
    exit = exit,
    invalidation = invalidation,
    detail = detail,
    distanceAtr = distanceAtr,
    zoneTouches = zoneTouches
)

@Dao
interface StrategySignalLedgerDao {

    /** One row per strategy per bar: the same bar replaces, a new bar appends. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<StrategySignalLedgerEntity>)

    @Query(
        "SELECT * FROM ${StrategySignalSchema.TABLE} WHERE asset = :asset " +
            "ORDER BY barOpenTime DESC, strategyId ASC LIMIT :limit"
    )
    suspend fun latest(asset: String, limit: Int): List<StrategySignalLedgerEntity>

    @Query(
        "SELECT * FROM ${StrategySignalSchema.TABLE} WHERE asset = :asset " +
            "ORDER BY barOpenTime DESC, strategyId ASC LIMIT :limit"
    )
    fun observeLatest(asset: String, limit: Int): Flow<List<StrategySignalLedgerEntity>>

    /** Bounded growth: rows older than [before] are no longer part of the audit window. */
    @Query("DELETE FROM ${StrategySignalSchema.TABLE} WHERE asset = :asset AND barOpenTime < :before")
    suspend fun prune(asset: String, before: Long)
}
