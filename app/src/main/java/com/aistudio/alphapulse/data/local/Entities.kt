package com.aistudio.alphapulse.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "crypto_assets")
data class AssetEntity(
    @PrimaryKey val symbol: String,
    val baseAsset: String,
    val lastPrice: Double,
    val priceChange24h: Double,
    val quoteVolume24h: Double,
    val orderBookSpreadPct: Double,
    val fundingRatePct: Double,
    val openInterestUsd: Double,
    val basisPremiumPct: Double,
    val atr5m: Double,
    val regime: String,
    val tacticalBias: Int,
    val strategicBias: Int,
    val primaryPainPath: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "campaign_records")
data class CampaignEntity(
    @PrimaryKey val id: String,
    val asset: String,
    val familyName: String,
    val isLong: Boolean,
    val regime: String,
    val role: String,
    val priorityScore: Double,
    val sizeBudgetUsd: Double,
    val invalidationScore: Int,
    val status: String,
    val entryLadderJson: String,
    val targetsJson: String,
    val topReasonsJson: String,
    val hardStopPrice: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "audit_trail")
data class AuditTrailEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val asset: String,
    val campaignId: String,
    val action: String,
    val reasonCode: String,
    val message: String
)
