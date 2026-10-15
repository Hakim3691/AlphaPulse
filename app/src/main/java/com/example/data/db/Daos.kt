package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PredictionDao {
    @Query("SELECT * FROM predictions ORDER BY issuedTimestamp DESC")
    fun getAllPredictionsFlow(): Flow<List<PredictionEntity>>

    @Query("SELECT * FROM predictions WHERE symbol = :symbol ORDER BY issuedTimestamp DESC")
    fun getPredictionsBySymbolFlow(symbol: String): Flow<List<PredictionEntity>>

    @Query("SELECT * FROM predictions WHERE symbol = :symbol ORDER BY issuedTimestamp DESC")
    suspend fun getPredictionsBySymbol(symbol: String): List<PredictionEntity>

    @Query("SELECT * FROM predictions ORDER BY issuedTimestamp DESC")
    suspend fun getAllPredictions(): List<PredictionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prediction: PredictionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(predictions: List<PredictionEntity>)

    @Update
    suspend fun update(prediction: PredictionEntity)

    @Query("DELETE FROM predictions WHERE symbol = :symbol")
    suspend fun clearBySymbol(symbol: String)

    @Query("DELETE FROM predictions")
    suspend fun clearAll()
}

@Dao
interface ScreenedCoinDao {
    @Query("SELECT * FROM screened_coins ORDER BY rank ASC")
    fun getAllScreenedCoinsFlow(): Flow<List<ScreenedCoinEntity>>

    @Query("SELECT * FROM screened_coins ORDER BY rank ASC")
    suspend fun getAllScreenedCoins(): List<ScreenedCoinEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(coins: List<ScreenedCoinEntity>)

    @Query("DELETE FROM screened_coins")
    suspend fun clearAll()
}
