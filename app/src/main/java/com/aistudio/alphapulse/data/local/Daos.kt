package com.aistudio.alphapulse.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Query("SELECT * FROM crypto_assets ORDER BY quoteVolume24h DESC")
    fun getAllAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM crypto_assets WHERE symbol = :symbol LIMIT 1")
    suspend fun getAssetBySymbol(symbol: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<AssetEntity>)

    @Query("DELETE FROM crypto_assets")
    suspend fun clearAssets()
}

@Dao
interface CampaignDao {
    @Query("SELECT * FROM campaign_records ORDER BY priorityScore DESC")
    fun getAllCampaigns(): Flow<List<CampaignEntity>>

    @Query("SELECT * FROM campaign_records WHERE asset = :asset")
    fun getCampaignsForAsset(asset: String): Flow<List<CampaignEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateCampaign(campaign: CampaignEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCampaigns(campaigns: List<CampaignEntity>)

    @Query("UPDATE campaign_records SET status = :status WHERE id = :campaignId")
    suspend fun updateCampaignStatus(campaignId: String, status: String)

    @Query("DELETE FROM campaign_records WHERE id = :campaignId")
    suspend fun deleteCampaign(campaignId: String)
}

@Dao
interface AuditTrailDao {
    @Query("SELECT * FROM audit_trail ORDER BY timestamp DESC LIMIT 200")
    fun getLatestAuditLogs(): Flow<List<AuditTrailEntity>>

    @Insert
    suspend fun insertLog(log: AuditTrailEntity)

    @Query("DELETE FROM audit_trail")
    suspend fun clearLogs()
}
