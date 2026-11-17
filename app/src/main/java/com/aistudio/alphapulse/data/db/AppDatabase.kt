package com.aistudio.alphapulse.data.db

/**
 * The on-device database: one Room database for all saved state.
 *
 * Predictions, screened coins, trade buffers, order-book deltas, liquidation
 * maps, volume profiles, the model registry, recorded decisions and sessions all
 * live here, so the app still has history after a restart.
 *
 * Spec Ref: §37 Room Database, §54 Session Recovery
 */

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PredictionEntity::class, ScreenedCoinEntity::class,
        TradeBufferEntity::class, OrderBookDeltaEntity::class,
        LiquidationHeatmapEntity::class, VolumeProfileSnapshotEntity::class,
        ModelRegistryEntity::class, SessionEntity::class, DecisionRecordEntity::class,
        EnsembleWeightsEntity::class, ProactiveAuditLedgerEntity::class,
        PatternLedgerEntity::class, PatternSgdWeightsEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun predictionDao(): PredictionDao
    abstract fun screenedCoinDao(): ScreenedCoinDao
    abstract fun tradeBufferDao(): TradeBufferDao
    abstract fun orderBookDeltaDao(): OrderBookDeltaDao
    abstract fun liquidationHeatmapDao(): LiquidationHeatmapDao
    abstract fun volumeProfileSnapshotDao(): VolumeProfileSnapshotDao
    abstract fun modelRegistryDao(): ModelRegistryDao
    abstract fun sessionDao(): SessionDao
    abstract fun decisionRecordDao(): DecisionRecordDao
    abstract fun ensembleWeightsDao(): EnsembleWeightsDao
    abstract fun proactiveAuditLedgerDao(): ProactiveAuditLedgerDao
    abstract fun patternLedgerDao(): PatternLedgerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Adds the drift terms of the optionally mounted W4/W5 ensemble members. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE predictions ADD COLUMN muW4 REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE predictions ADD COLUMN muW5 REAL NOT NULL DEFAULT 0.0")
            }
        }

        /** Adds the recorded §29 verdicts the audit export joins onto predictions. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `decision_records` (" +
                        "`id` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
                        "`issuedTimestamp` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, `source` TEXT NOT NULL, " +
                        "`referenceHorizon` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * Adds the per-coin, per-horizon 30-day calibration weights (§20).
         *
         * `internal` rather than `private` so `EnsembleWeightsMigrationBddSpec` can
         * open a version-5 file with this exact object; a copy of the SQL in a test
         * would prove nothing about the migration the app actually runs.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ensemble_weights` (" +
                        "`compositeKey` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
                        "`horizonId` TEXT NOT NULL, `weightK` REAL NOT NULL, " +
                        "`weightP` REAL NOT NULL, `weightAlpha` REAL NOT NULL, " +
                        "`weight4` REAL NOT NULL, `weight5` REAL NOT NULL, " +
                        "`lastUpdated` INTEGER NOT NULL, `modelVersion` TEXT NOT NULL, " +
                        "PRIMARY KEY(`compositeKey`))"
                )
            }
        }

        /**
         * Adds the §71 25-field ProactiveMS audit ledger (C-22).
         *
         * The statement comes from [ProactiveAuditSchema] rather than being written out
         * here, so the migration, the entity and the guard spec read one definition. It
         * is `internal` for the same reason as [MIGRATION_5_6]: the guard drives this
         * exact object instead of a copy of its SQL.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(ProactiveAuditSchema.DDL)
            }
        }

        /**
         * Adds the §73 pattern ledger and its SGD heads (C-23).
         *
         * Its own tables, never new columns on `predictions`: the price ledger is the
         * unmodified baseline the pattern ledger is mounted beside.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PatternLedgerSchema.DDL)
                db.execSQL(PatternLedgerSchema.WEIGHTS_DDL)
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "alphapulse_db"
                ).addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                )
                    .fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
