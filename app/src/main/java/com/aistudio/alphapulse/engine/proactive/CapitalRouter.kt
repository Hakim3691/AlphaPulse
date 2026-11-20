package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.BoardRole

/**
 * §29 — capital routing for live boards.
 *
 * Margin is reserved by board role before execution. When overall crypto market
 * correlation exceeds 0.85 — a BTC flash dump dragging every alt with it — defensive
 * and secondary boards are cancelled first and the remaining liquidity is
 * concentrated exclusively on the highest-ranking primary campaign, because in that
 * regime a diversified board set is one position with extra fees.
 *
 * Spec Ref: Phase 2 §29, §17
 */
data class RoutedBoard(
    val campaignId: String,
    val role: BoardRole,
    val priorityScore: Double,
    val sizeBudgetUsd: Double
)

data class CapitalRoutingDecision(
    val marketCorrelation: Double,
    val crisisMode: Boolean,
    val cancelledBoardIds: List<String>,
    val retainedBoardIds: List<String>,
    val concentratedBoardId: String?,
    val releasedNotionalUsd: Double,
    val reason: String
)

class CapitalRouter(
    private val correlationThreshold: Double = CORRELATION_CRISIS_THRESHOLD
) {
    init {
        require(correlationThreshold > 0.0 && correlationThreshold <= 1.0) {
            "Correlation threshold must be in (0, 1], was $correlationThreshold"
        }
    }

    fun route(boards: List<RoutedBoard>, marketCorrelation: Double): CapitalRoutingDecision {
        if (marketCorrelation <= correlationThreshold || boards.isEmpty()) {
            return CapitalRoutingDecision(
                marketCorrelation = marketCorrelation,
                crisisMode = false,
                cancelledBoardIds = emptyList(),
                retainedBoardIds = boards.map { it.campaignId },
                concentratedBoardId = null,
                releasedNotionalUsd = 0.0,
                reason = "Market correlation ${"%.2f".format(marketCorrelation)} within " +
                    "normal band; every board keeps its role budget"
            )
        }

        val primary = boards.filter { it.role == BoardRole.PRIMARY }
            .maxByOrNull { it.priorityScore }
        val survivor = primary ?: boards.maxByOrNull { it.priorityScore }
        val cancelled = boards.filter { it.campaignId != survivor?.campaignId }

        return CapitalRoutingDecision(
            marketCorrelation = marketCorrelation,
            crisisMode = true,
            cancelledBoardIds = cancelled.map { it.campaignId },
            retainedBoardIds = listOfNotNull(survivor?.campaignId),
            concentratedBoardId = survivor?.campaignId,
            releasedNotionalUsd = cancelled.sumOf { it.sizeBudgetUsd },
            reason = "Market correlation ${"%.2f".format(marketCorrelation)} exceeds " +
                "${"%.2f".format(correlationThreshold)}: defensive and secondary boards " +
                "cancelled first, capital concentrated on the highest-ranking primary"
        )
    }

    companion object {
        const val CORRELATION_CRISIS_THRESHOLD = 0.85
    }
}
