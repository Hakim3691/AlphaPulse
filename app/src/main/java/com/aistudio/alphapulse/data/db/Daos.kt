package com.aistudio.alphapulse.data.db

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

@Dao
interface TradeBufferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrade(trade: TradeBufferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrades(trades: List<TradeBufferEntity>)

    @Query("SELECT * FROM trade_buffer WHERE symbol = :symbol ORDER BY tradeId DESC LIMIT :limit")
    suspend fun getLatestTrades(symbol: String, limit: Int): List<TradeBufferEntity>

    @Query("DELETE FROM trade_buffer")
    suspend fun clearAll()

    @Query("DELETE FROM trade_buffer WHERE symbol = :symbol")
    suspend fun clearBySymbol(symbol: String)
}

@Dao
interface OrderBookDeltaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderBookDelta(delta: OrderBookDeltaEntity)

    @Query("SELECT * FROM order_book_deltas WHERE symbol = :symbol ORDER BY updateId DESC LIMIT :limit")
    suspend fun getLatestOrderBookDeltas(symbol: String, limit: Int): List<OrderBookDeltaEntity>

    @Query("DELETE FROM order_book_deltas")
    suspend fun clearAll()
}

@Dao
interface LiquidationHeatmapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiquidationHeat(lh: LiquidationHeatmapEntity)

    @Query("SELECT * FROM liquidation_heatmap WHERE symbol = :symbol ORDER BY id DESC LIMIT :limit")
    suspend fun getLatestLiquidationHeat(symbol: String, limit: Int): List<LiquidationHeatmapEntity>

    @Query("DELETE FROM liquidation_heatmap")
    suspend fun clearAll()
}

@Dao
interface VolumeProfileSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVolumeProfileSnapshot(vps: VolumeProfileSnapshotEntity)

    @Query("SELECT * FROM volume_profile_snapshots WHERE symbol = :symbol ORDER BY id DESC LIMIT :limit")
    suspend fun getLatestVolumeProfileSnapshots(symbol: String, limit: Int): List<VolumeProfileSnapshotEntity>

    @Query("DELETE FROM volume_profile_snapshots")
    suspend fun clearAll()
}

/*** New DAOs for Model Registry and Session management ***/

@Dao
interface ModelRegistryDao {
    @Query("SELECT * FROM model_registry WHERE modelId = :modelId")
    fun getModelById(modelId: String): ModelRegistryEntity?

    @Query("SELECT * FROM model_registry ORDER BY downloadTimestamp DESC")
    fun getAllModelsFlow(): Flow<List<ModelRegistryEntity>>

    @Query("SELECT * FROM model_registry ORDER BY downloadTimestamp DESC")
    suspend fun getAllModels(): List<ModelRegistryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelRegistryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<ModelRegistryEntity>)

    @Query("DELETE FROM model_registry WHERE modelId = :modelId")
    suspend fun clearByModelId(modelId: String)

    @Query("DELETE FROM model_registry")
    suspend fun clearAll()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session WHERE sessionId = :sessionId")
    fun getSession(sessionId: String): SessionEntity?

    @Query("SELECT * FROM session ORDER BY lastActive DESC LIMIT 1")
    fun getLatestSession(): SessionEntity?

    @Query("DELETE FROM session")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSession(session: SessionEntity)
}