package com.aistudio.alphapulse.data.model

enum class ExecutionMode(val displayName: String, val badgeColorHex: Long) {
    SHADOW("Shadow Mode", 0xFF00E5FF),     // Generate campaigns and alerts only
    PAPER("Paper Mode", 0xFF00E676),       // Simulated fills against live feed
    CAPPED_LIVE("Capped Live", 0xFFFFD600), // Tiny notional, operator supervision
    SCALED_LIVE("Scaled Live", 0xFFFF5252)  // Full production allocation
}

data class RiskEnvelope(
    val accountEquityUsd: Double = 50_000.0,
    val riskPerCampaignPct: Double = 0.50, // 0.25% to 0.75%
    val maxGrossExposureUsd: Double = 100_000.0,
    val currentGrossExposureUsd: Double = 18_400.0,
    val dailyPnLUsd: Double = 840.0,
    val dailyLossLimitR: Double = 2.0,     // 1.5R to 2.0R daily stop
    val currentRealizedLossR: Double = 0.0,
    val worstCaseStopOutR: Double = 0.85,  // Worst case concurrent stop-out visible at all times
    val isKillSwitchEngaged: Boolean = false,
    val cappedNotionalPerCampaignUsd: Double = 250.0 // Capped Live per-board clip (§36)
) {
    val isDailyLossExceeded: Boolean get() = currentRealizedLossR >= dailyLossLimitR
}

data class VenueHealth(
    val binancePingMs: Long = 28,
    val coinglassPingMs: Long = 42,
    val orderRejectRatePct: Double = 0.01,
    val spreadBps: Double = 3.5,
    val feedStalenessSec: Int = 1,
    val isFeedStale: Boolean = false,
    val isLatencySpike: Boolean = false,
    val isLowPowerMode: Boolean = false,
    val isThermallyThrottled: Boolean = false,
    val availableRamMb: Int = 3120,
    val isRamConstrained: Boolean = false
) {
    val isHealthy: Boolean
        get() = !isFeedStale && !isLatencySpike && !isLowPowerMode && !isThermallyThrottled && !isRamConstrained
}

enum class AuditLogAction(val label: String) {
    CAMPAIGN_STAGED("Campaign Staged"),
    SLICE_FILLED("Ladder Slice Filled"),
    SOFT_CANCEL("Soft Invalidation Cancel"),
    HARD_FLATTEN("Hard Flatten & Cooldown"),
    BOARD_PROMOTED("Board Promoted (Sec->Pri)"),
    DEFENSIVE_SCALED("Defensive Board Scaled"),
    EMERGENCY_KILL("Emergency Kill-Switch"),
    MANUAL_OVERRIDE("Manual Parameter Override"),
    ORPHANED_ALERT("Orphaned Order OS Intercept")
}

data class AuditLogEntry(
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val asset: String,
    val campaignId: String,
    val action: AuditLogAction,
    val reasonCode: String,
    val message: String
)

data class BacktestMetrics(
    val capturedMoveBeforeConfirmationPct: Double = 74.6, // Edge metric
    val averageInvalidationSpeedSec: Double = 4.2,         // Speed cutting bad ideas
    val orderMissRatePct: Double = 8.1,
    val maxAdverseExcursionPct: Double = 1.15,
    val pnlByRegime: Map<String, Double> = mapOf(
        "Bullish Continuation" to 4250.0,
        "Bearish Continuation" to 3890.0,
        "Transition" to 1420.0,
        "Balance" to 890.0
    ),
    val falsePositiveInvalidationPct: Double = 6.4,
    val slowInvalidationPct: Double = 3.1,
    // §37 replay-harness outputs (computed, not canned): zero until a replay runs.
    val totalPnlUsd: Double = 0.0,
    val replayedCampaigns: Int = 0,
    val filledSliceCount: Int = 0,
    val proactiveEdgeVsBaselinePct: Double = 0.0
)
