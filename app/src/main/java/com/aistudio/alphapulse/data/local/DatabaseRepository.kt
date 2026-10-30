package com.aistudio.alphapulse.data.local

import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DatabaseRepository(private val database: AppDatabase) {

    val allAssets: Flow<List<AssetEntity>> = database.assetDao().getAllAssets()
    val allCampaigns: Flow<List<CampaignEntity>> = database.campaignDao().getAllCampaigns()
    val latestAuditLogs: Flow<List<AuditLogEntry>> = database.auditTrailDao().getLatestAuditLogs().map { list ->
        list.map { entity ->
            val action = try {
                AuditLogAction.valueOf(entity.action)
            } catch (_: Exception) {
                AuditLogAction.CAMPAIGN_STAGED
            }
            AuditLogEntry(
                id = entity.id,
                timestamp = entity.timestamp,
                asset = entity.asset,
                campaignId = entity.campaignId,
                action = action,
                reasonCode = entity.reasonCode,
                message = entity.message
            )
        }
    }

    suspend fun saveAssets(entities: List<AssetEntity>) {
        database.assetDao().insertAssets(entities)
    }

    suspend fun saveCampaign(entity: CampaignEntity) {
        database.campaignDao().insertOrUpdateCampaign(entity)
    }

    suspend fun saveCampaigns(entities: List<CampaignEntity>) {
        database.campaignDao().insertCampaigns(entities)
    }

    suspend fun updateCampaignStatus(campaignId: String, status: String) {
        database.campaignDao().updateCampaignStatus(campaignId, status)
    }

    suspend fun logAction(
        asset: String,
        campaignId: String,
        action: AuditLogAction,
        reasonCode: String,
        message: String
    ) {
        database.auditTrailDao().insertLog(
            AuditTrailEntity(
                timestamp = System.currentTimeMillis(),
                asset = asset,
                campaignId = campaignId,
                action = action.name,
                reasonCode = reasonCode,
                message = message
            )
        )
    }

    suspend fun clearAuditLogs() {
        database.auditTrailDao().clearLogs()
    }
}
