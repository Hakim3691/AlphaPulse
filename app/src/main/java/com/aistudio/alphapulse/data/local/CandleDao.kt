package com.aistudio.alphapulse.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Phase 2 (Spec §5): DAO for the persisted HTF candle store.
 */
@Dao
interface CandleDao {

    @Query("SELECT * FROM htf_candles WHERE symbol = :symbol AND interval = :interval ORDER BY openTimeUtcMs ASC")
    suspend fun getCandles(symbol: String, interval: String): List<CandleEntity>

    @Query("SELECT MAX(fetchedAtMs) FROM htf_candles WHERE symbol = :symbol AND interval = :interval")
    suspend fun getLatestFetchedAtMs(symbol: String, interval: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<CandleEntity>)

    @Query("DELETE FROM htf_candles WHERE symbol = :symbol AND interval = :interval")
    suspend fun clearCandles(symbol: String, interval: String)
}
