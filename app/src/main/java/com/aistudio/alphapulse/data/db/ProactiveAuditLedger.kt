package com.aistudio.alphapulse.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * §71 / Contract C-22 — the 25-field ProactiveMS decision audit ledger.
 *
 * Why this exists
 * ---------------
 * The AlphaPulse ledger (§21) records *what the router decided about a snapshot*. The
 * ProactiveMS ledger records something different and stricter: **why a position was
 * cancelled or held**, in enough detail that the decision can be re-derived later
 * without trusting the machine that made it.
 *
 * That needs the numbers the decision was actually made from — the cone that was
 * breached, its confidence and magnitude, the SGD rank either side of the trigger, the
 * proactive tolerance in force, and the walk-forward window the parameter came from.
 * A row that records only "exited at 0.42R" cannot be audited; it can only be believed.
 *
 * The shadow field ([subsequentlyReachedMinus2R]) is the honest one: it records what
 * price did *after* the exit, so a proactive exit that merely avoided a normal
 * pullback is distinguishable from one that genuinely avoided the hard stop.
 *
 * Schema lives here rather than inline in the migration so the migration and its guard
 * spec read the same list — a copy of the DDL inside a test proves nothing about the
 * migration the app actually runs.
 *
 * Spec Ref: §71 25-Field Audit Ledger, §21.2, §19, C-22
 */
object ProactiveAuditSchema {

    const val TABLE = "proactive_audit_ledger"

    /** The record key. Not one of the 25 audit fields; the ledger needs a stable id. */
    const val KEY_COLUMN = "id"

    /**
     * The 25 mandatory fields of §21.2, in the order the specification lists them.
     *
     * Order matters for the CSV/JSON exports, which are column-stable by contract.
     */
    val AUDIT_FIELDS: List<String> = listOf(
        "timestamp",                    // 1  Timestamp
        "asset",                        // 2  Asset
        "positionDirection",            // 3  LONG / SHORT
        "entryPrice",                   // 4  Entry price
        "currentPrice",                 // 5  Current price
        "initialRiskR",                 // 6  Initial risk ("1R")
        "currentPnlR",                  // 7  Current P&L in R
        "coneCentre",                   // 8  Cone centre
        "coneBoundary",                 // 9  Cone boundary
        "coneConfidencePct",            // 10 Cone confidence / significance level
        "breachMagnitude",              // 11 Breach magnitude
        "sgdRankBefore",                // 12 SGD ranking before trigger
        "sgdRankAfter",                 // 13 SGD ranking after trigger
        "rerankingMagnitude",           // 14 Re-ranking magnitude
        "triggerType",                  // 15 Trigger type
        "proactiveThresholdR",          // 16 ProactiveMS_MaxLoss_R
        "actionTaken",                  // 17 Action taken
        "exitBeforeHalfR",              // 18 Exit occurred before 0.5R
        "exitBeforeOneR",               // 19 Exit occurred before 1R
        "subsequentlyReachedMinus2R",   // 20 Shadow: reached -2R after the exit
        "finalOutcome",                 // 21 Final trade outcome
        "walkForwardCycleId",           // 22 Walk-forward cycle id
        "trainingWindowId",             // 23 Training-window id
        "outOfSampleWindowId",          // 24 Out-of-sample-window id
        "parameterSetVersion"           // 25 Parameter set / version
    )

    /** The production 6 -> 7 schema statement, shared with the migration and its guard. */
    val DDL: String = buildString {
        append("CREATE TABLE IF NOT EXISTS `").append(TABLE).append("` (")
        append("`").append(KEY_COLUMN).append("` TEXT NOT NULL, ")
        append("`timestamp` INTEGER NOT NULL, ")
        append("`asset` TEXT NOT NULL, ")
        append("`positionDirection` TEXT NOT NULL, ")
        append("`entryPrice` REAL NOT NULL, ")
        append("`currentPrice` REAL NOT NULL, ")
        append("`initialRiskR` REAL NOT NULL, ")
        append("`currentPnlR` REAL NOT NULL, ")
        append("`coneCentre` REAL NOT NULL, ")
        append("`coneBoundary` REAL NOT NULL, ")
        append("`coneConfidencePct` REAL NOT NULL, ")
        append("`breachMagnitude` REAL NOT NULL, ")
        append("`sgdRankBefore` INTEGER NOT NULL, ")
        append("`sgdRankAfter` INTEGER NOT NULL, ")
        append("`rerankingMagnitude` INTEGER NOT NULL, ")
        append("`triggerType` TEXT NOT NULL, ")
        append("`proactiveThresholdR` REAL NOT NULL, ")
        append("`actionTaken` TEXT NOT NULL, ")
        append("`exitBeforeHalfR` INTEGER NOT NULL, ")
        append("`exitBeforeOneR` INTEGER NOT NULL, ")
        append("`subsequentlyReachedMinus2R` INTEGER NOT NULL, ")
        append("`finalOutcome` TEXT NOT NULL, ")
        append("`walkForwardCycleId` TEXT NOT NULL, ")
        append("`trainingWindowId` TEXT NOT NULL, ")
        append("`outOfSampleWindowId` TEXT NOT NULL, ")
        append("`parameterSetVersion` TEXT NOT NULL, ")
        append("PRIMARY KEY(`").append(KEY_COLUMN).append("`))")
    }
}

/**
 * One audited ProactiveMS decision.
 *
 * Every property after [id] is one of the 25 mandatory fields; the names are the
 * contract's, not a convenient shorthand.
 */
@Entity(tableName = ProactiveAuditSchema.TABLE)
data class ProactiveAuditLedgerEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val asset: String,
    val positionDirection: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val initialRiskR: Double,
    val currentPnlR: Double,
    val coneCentre: Double,
    val coneBoundary: Double,
    val coneConfidencePct: Double,
    val breachMagnitude: Double,
    val sgdRankBefore: Int,
    val sgdRankAfter: Int,
    val rerankingMagnitude: Int,
    val triggerType: String,
    val proactiveThresholdR: Double,
    val actionTaken: String,
    val exitBeforeHalfR: Boolean,
    val exitBeforeOneR: Boolean,
    val subsequentlyReachedMinus2R: Boolean,
    val finalOutcome: String,
    val walkForwardCycleId: String,
    val trainingWindowId: String,
    val outOfSampleWindowId: String,
    val parameterSetVersion: String
)

/** The ledger is append-only: a decision is never edited after it is written. */
@Dao
interface ProactiveAuditLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ProactiveAuditLedgerEntity)

    @Query("SELECT * FROM proactive_audit_ledger ORDER BY timestamp DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<ProactiveAuditLedgerEntity>

    @Query("SELECT * FROM proactive_audit_ledger WHERE asset = :asset ORDER BY timestamp DESC")
    suspend fun forAsset(asset: String): List<ProactiveAuditLedgerEntity>
}
