package com.aistudio.alphapulse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PredictionEntity::class, ScreenedCoinEntity::class,
        TradeBufferEntity::class, OrderBookDeltaEntity::class,
        LiquidationHeatmapEntity::class, VolumeProfileSnapshotEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun predictionDao(): PredictionDao
    abstract fun screenedCoinDao(): ScreenedCoinDao
    abstract fun tradeBufferDao(): TradeBufferDao
    abstract fun orderBookDeltaDao(): OrderBookDeltaDao
    abstract fun liquidationHeatmapDao(): LiquidationHeatmapDao
    abstract fun volumeProfileSnapshotDao(): VolumeProfileSnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "alphapulse_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
