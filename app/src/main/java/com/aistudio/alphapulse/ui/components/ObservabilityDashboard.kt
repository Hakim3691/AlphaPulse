/**
 * Observability Dashboard - Section 56
 * 
 * Engineering diagnostics screen showing:
 * - WS/REST latency metrics
 * - Data freshness (age of last received data)
 * - CPU/memory/battery usage
 * - Inference latency per backend (Kronos, TimesFM, LOB)
 * - Database latency and query performance
 * - Model error rates and failure counts
 * - WebSocket connection quality and message drop rates
 * - Sequence gap detection and rate limiting status
 * 
 * Per SPEC §56: "Engineering diagnostics screen showing WS/REST latency,
 * data freshness, CPU/memory/battery, inference/DB latency, model errors"
 */
package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Observability Dashboard data models
 */

// WS/REST Latency metrics
data class WSRestLatencyMetrics(
    val wsAvgMs: Float = 0f,
    val wsP95Ms: Float = 0f,
    val wsLossPct: Float = 0f,
    val restAvgMs: Float = 0f,
    val restP95Ms: Float = 0f,
    val restErrorPct: Float = 0f,
    val bookAvgMs: Float = 0f,
    val bookP95Ms: Float = 0f,
    val bookLossPct: Float = 0f,
    val aggAvgMs: Float = 0f,
    val aggP95Ms: Float = 0f,
    val aggLossPct: Float = 0f
)

// Data Freshness metrics
data class DataFreshnessMetrics(
    val lastWsAgeMs: Long = 0,
    val lastRestAgeMs: Long = 0,
    val lastBookAgeMs: Long = 0,
    val lastPredictionAgeMs: Long = 0,
    val lastKlineAgeMs: Long = 0
)

// Resource metrics
data class ResourceMetrics(
    val cpuPercent: Int = 0,
    val memoryPercent: Int = 0,
    val batteryPercent: Int = 0,
    val temperature: Float = 0f,
    val uptimeMinutes: Int = 0
)

// Inference metrics
data class InferenceMetrics(
    val kronosAvgMs: Float = 0f,
    val kronosP95Ms: Float = 0f,
    val kronosBackend: String = "CPU",
    val timesFmAvgMs: Float = 0f,
    val timesFmP95Ms: Float = 0f,
    val timesFmBackend: String = "CPU",
    val lobAvgMs: Float = 0f,
    val lobP95Ms: Float = 0f,
    val lobBackend: String = "CPU",
    val chronosBoltAvgMs: Float = 0f,
    val chronosBoltP95Ms: Float = 0f,
    val chronosBoltBackend: String = "CPU"
)

// Database metrics
data class DatabaseMetrics(
    val poolActive: Int = 0,
    val poolMax: Int = 10,
    val avgQueryMs: Float = 0f,
    val p95QueryMs: Float = 0f,
    val slowQueryCount: Int = 0,
    val writeMs: Float = 0f,
    val writeP95Ms: Float = 0f,
    val readMs: Float = 0f,
    val readP95Ms: Float = 0f
)

// Model metrics
data class ModelMetrics(
    val kronosErrorPct: Float = 0f,
    val timesFmErrorPct: Float = 0f,
    val lobErrorPct: Float = 0f,
    val decisionErrorPct: Float = 0f,
    val kronosLastError: String = "",
    val timesFmLastError: String = "",
    val lobLastError: String = "",
    val decisionLastError: String = ""
)

// Connectivity metrics
data class ConnectivityMetrics(
    val wsConnected: Boolean = false,
    val wsMessagesPerSec: Float = 0f,
    val restStatus: String = "unknown",
    val rateLimiterActive: Boolean = false,
    val seqGapCount: Int = 0,
    val lastSeqReceived: Long = 0
)

/**
 * Repository function to fetch observability metrics
 * In production, this would read from live system sources
 */
fun fetchObservabilityMetrics(): WSRestLatencyMetrics {
    return WSRestLatencyMetrics(
        wsAvgMs = 23.5f,
        wsP95Ms = 45f,
        wsLossPct = 0.5f,
        restAvgMs = 128f,
        restP95Ms = 256f,
        restErrorPct = 0.2f,
        bookAvgMs = 15f,
        bookP95Ms = 32f,
        bookLossPct = 0.1f,
        aggAvgMs = 28f,
        aggP95Ms = 55f,
        aggLossPct = 0.3f
    )
}