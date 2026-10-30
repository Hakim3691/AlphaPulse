package com.aistudio.alphapulse.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 2 (Spec §5 Heavy Data): persisted HTF candle store with UTC-normalized
 * timestamps, enabling the >4h staleness check before background re-download.
 */
@Entity(tableName = "htf_candles")
data class CandleEntity(
    @PrimaryKey val id: String,             // "<SYMBOL>|<interval>|<openTimeUtcMs>"
    val symbol: String,
    val interval: String,
    val openTimeUtcMs: Long,
    val closeTimeUtcMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double,
    val fetchedAtMs: Long = System.currentTimeMillis()
)
