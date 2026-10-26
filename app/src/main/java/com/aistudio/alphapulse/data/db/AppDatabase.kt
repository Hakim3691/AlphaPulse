package com.aistudio.alphapulse.data.db

/**
 * The on-device database: one Room database for all saved state.
 *
 * Predictions, screened coins, trade buffers, order-book deltas, liquidation
 * maps, volume profiles, the model registry and sessions all live here, so the
 * app still has history after a restart.
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
        ModelRegistryEntity::class, SessionEntity::class],
    version = 4,
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "alphapulse_db"
                ).addMigrations(MIGRATION_3_4).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
