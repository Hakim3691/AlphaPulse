package com.aistudio.alphapulse.data.model

enum class CampaignFamily(
    val displayName: String,
    val isLong: Boolean,
    val structuralDescription: String
) {
    SUPPORT_LONG(
        "Proactive Support Long",
        true,
        "Resting bids across support shelf/pivot before confirmation bounce"
    ),
    RESISTANCE_SHORT(
        "Proactive Resistance Short",
        false,
        "Resting offers into mapped supply zone before confirmation rejection"
    ),
    RECLAIM_LONG(
        "Proactive Reclaim Long",
        true,
        "Pre-stage longs around reclaim threshold before full acceptance sequence"
    ),
    RECLAIM_FADE_SHORT(
        "Proactive Reclaim Fade Short",
        false,
        "Sell weak bounce into damaged structure / failed reclaim"
    ),
    BREAKOUT_LONG(
        "Proactive Breakout Long",
        true,
        "Capture upside continuation through resistance threshold before extended"
    ),
    BREAKDOWN_SHORT(
        "Proactive Breakdown Short",
        false,
        "Participate in continuation when support fails and downside accelerates"
    )
}

enum class BoardRole(val label: String, val sizeBudgetPct: String, val cancelPatience: String) {
    PRIMARY("Primary", "40% - 60%", "Slowest to cancel (rules-based)"),
    SECONDARY("Secondary", "20% - 35%", "Moderate cancel sensitivity"),
    DEFENSIVE("Defensive", "5% - 20%", "Fast cancel sensitivity"),
    DISABLED("Disabled", "0%", "No orders allowed")
}

enum class CampaignState(val label: String) {
    PLANNED("Planned"),
    STAGED("Staged (Resting)"),
    PARTIALLY_FILLED("Partially Filled"),
    ACTIVE("Active"),
    REDUCED("Reduced / De-risked"),
    CANCELLED("Cancelled (Invalidated)"),
    COMPLETED("Completed (TP Reached)"),
    SUPPRESSED("Suppressed (Guardrail)")
}

enum class OrderType {
    PASSIVE_LIMIT,
    STOP_LIMIT,
    STOP_MARKET
}

data class LadderSlice(
    val sliceIndex: Int,
    val price: Double,
    val qty: Double,
    val notionalUsd: Double,
    val orderType: OrderType = OrderType.PASSIVE_LIMIT,
    val isFilled: Boolean = false,
    val isResting: Boolean = true,
    /** True once the staged take-profit executed: the slice closed at target. */
    val isClosedAtTarget: Boolean = false
)

data class TakeProfitTarget(
    val levelName: String,
    val price: Double,
    val percentClose: Int,
    val isHit: Boolean = false
)

data class StopLogic(
    val softThreshold: Int = 45,  // Cancel unfilled & trim active
    val hardThreshold: Int = 70,  // Flatten & cooldown
    val hardStopPrice: Double
)

enum class InvalidationFamily(val label: String) {
    STRUCTURE_FAILURE("Structure Failure"),
    POSITIONING_CONTRADICTION("Positioning Contradiction (OI/Price)"),
    FLOW_FAILURE("Flow Failure (No Taker Response)"),
    CROWDING_REVERSAL("Crowding Reversal"),
    LIQUIDITY_DISORDER("Liquidity Disorder"),
    TIME_EXPIRATION("Time Stagnation")
}

data class InvalidationReason(
    val family: InvalidationFamily,
    val points: Int,
    val explanation: String
)

data class LiquidationContext(
    val longLiquidatedUsd24h: Double,
    val shortLiquidatedUsd24h: Double,
    val lastUpdatedMs: Long
) {
    companion object {
        val EMPTY = LiquidationContext(0.0, 0.0, 0L)
    }
}

data class EvidenceFrame(
    val timestamp: Long,
    val asset: String,
    val oiDelta1hPct: Double,
    val fundingRateChangePct: Double,
    val basisDeltaBps: Double,
    val takerBuyRatio: Double,      // > 0.5 taker buy dominance, < 0.5 taker sell
    val acceptanceQuality: Double,  // 0.0 to 1.0
    val rejectionQuality: Double,   // 0.0 to 1.0
    val trapProbabilityPct: Int,    // 0 to 100
    val liquidationContext: LiquidationContext = LiquidationContext.EMPTY,
    val recentImpulseQuality: String
)

data class Campaign(
    val id: String,
    val asset: String,
    val family: CampaignFamily,
    val regime: MarketRegime,
    val role: BoardRole,
    val priorityScore: Double,      // Path prob * Cleanliness * Level conf
    val sizeBudgetUsd: Double,
    val sizeMultiplier: Double,
    val entryLadder: List<LadderSlice>,
    val stopLogic: StopLogic,
    val targets: List<TakeProfitTarget>,
    val invalidationScore: Int,     // 0-100
    val topReasons: List<InvalidationReason>,
    val status: CampaignState,
    val mirrorCampaignId: String? = null,
    val cooldownUntilTs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isLong: Boolean get() = family.isLong
    val filledNotional: Double get() = entryLadder.filter { it.isFilled }.sumOf { it.notionalUsd }
    val isSoftInvalidated: Boolean get() = invalidationScore >= stopLogic.softThreshold
    val isHardInvalidated: Boolean get() = invalidationScore >= stopLogic.hardThreshold
}
