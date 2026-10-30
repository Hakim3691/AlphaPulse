package com.aistudio.alphapulse.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        AssetEntity::class,
        CampaignEntity::class,
        AuditTrailEntity::class,
        CandleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun campaignDao(): CampaignDao
    abstract fun auditTrailDao(): AuditTrailDao
    abstract fun candleDao(): CandleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1→v2 (Phase 2): adds the §5 heavy-data candle store without wiping the
         * existing asset/campaign/audit history. Exposed for migration tests. The
         * destructive fallback remains only as a last-resort safety net for unknown
         * future versions — this migration is the normal upgrade path now.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `htf_candles` (`id` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
                        "`interval` TEXT NOT NULL, `openTimeUtcMs` INTEGER NOT NULL, `closeTimeUtcMs` INTEGER NOT NULL, " +
                        "`open` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, `close` REAL NOT NULL, " +
                        "`volume` REAL NOT NULL, `quoteVolume` REAL NOT NULL, `fetchedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "proactive_ms_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
