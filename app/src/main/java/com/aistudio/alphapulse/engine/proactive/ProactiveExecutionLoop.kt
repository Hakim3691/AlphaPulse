package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CandidatePath
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.InvalidationFamily
import com.aistudio.alphapulse.data.model.InvalidationReason
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.engine.CampaignEngine
import com.aistudio.alphapulse.engine.ExecutionEngine
import com.aistudio.alphapulse.engine.RiskEngine

/**
 * §31 — the event-driven automation loop.
 *
 * One frame of market data enters; the loop answers three questions in the order the
 * spec's pseudocode does:
 *
 *  1. Does AlphaPulse still permit staging? A NEUTRAL / NO_TRADE decision, a quorum
 *     below 0.70, a guardrail breach or a spread blowout suppresses new staging
 *     instead of guessing.
 *  2. Does an existing board still deserve to live? The §28 side-agnostic score is
 *     applied to every live board — soft at 45 cancels unfilled slices, hard at 70
 *     flattens and locks re-entry for the §34 cooldown — then §26 survival and §29
 *     capital routing run on the structural cadence.
 *  3. What may be staged now? Campaigns are planned, risk-gated and routed through
 *     the operator's execution mode only on the 5m/15m cadence, and never while
 *     staging is suppressed.
 *
 * The loop owns no timers of its own: §25's [MonitoringScheduler] decides which
 * evidence is due for the frame, so a tick only reconciles fills and checks the
 * spread while the expensive score and structure passes run on their cadences.
 *
 * Spec Ref: Phase 2 §31, §25, §26, §28, §29
 */
enum class DecisionState { LONG, SHORT, NEUTRAL, NO_TRADE }

data class AlphaPulseDecision(
    val state: DecisionState,
    val quorum: Double = 0.0
) {
    val quorumPasses: Boolean get() = quorum >= MIN_QUORUM

    /** §31: NEUTRAL, NO_TRADE or a sub-quorum answer forbids automated staging. */
    val suppressesStaging: Boolean
        get() = state == DecisionState.NEUTRAL ||
            state == DecisionState.NO_TRADE ||
            !quorumPasses

    companion object {
        const val MIN_QUORUM = 0.70
    }
}

data class ProactiveLoopFrame(
    val nowMillis: Long,
    val asset: CryptoAsset,
    val levelMap: LevelMap,
    val paths: List<CandidatePath>,
    /** Null when the host has no AlphaPulse decision feed; staging is then gated by the other rules. */
    val decision: AlphaPulseDecision? = null,
    val evidence: EvidenceFrame,
    val campaigns: List<Campaign>,
    val envelope: RiskEnvelope = RiskEnvelope(),
    val venueHealth: VenueHealth = VenueHealth(),
    val mode: ExecutionMode = ExecutionMode.SHADOW,
    val hasActiveSession: Boolean = false,
    val marketCorrelation: Double = 0.0,
    val oneMinuteCandleClosed: Boolean = false,
    val fiveMinuteCandleClosed: Boolean = false,
    val fifteenMinuteCandleClosed: Boolean = false,
    val preLaunch: PreLaunchVerdict? = null,
    /** The base app plans its own boards; monitoring passes set this false. */
    val allowNewStaging: Boolean = true
)

data class ProactiveLoopTelemetry(
    val asset: String,
    val invalidationScore: Int,
    val cadences: List<MonitoringCadence>,
    val actions: List<MonitoringAction>,
    val mode: ExecutionMode,
    val activeBoardCount: Int,
    val stagedBoardCount: Int,
    val suppressionReason: String?
)

data class ProactiveLoopResult(
    val campaigns: List<Campaign>,
    val cadences: List<MonitoringCadence>,
    val actions: List<MonitoringAction>,
    val stagedCampaigns: List<Campaign>,
    val stagingSuppressed: Boolean,
    val suppressionReason: String?,
    val invalidationScore: Int,
    val telemetry: ProactiveLoopTelemetry
)

class ProactiveExecutionLoop(
    private val config: ProductionExecutionConfig = ProductionExecutionConfig(),
    private val campaignEngine: CampaignEngine = CampaignEngine(),
    private val riskEngine: RiskEngine = RiskEngine(),
    private val executionEngine: ExecutionEngine = ExecutionEngine(RiskEngine()),
    private val capitalRouter: CapitalRouter = CapitalRouter(),
    private val survivalMonitor: BoardSurvivalMonitor = BoardSurvivalMonitor(),
    private val sideAgnostic: SideAgnosticInvalidation = SideAgnosticInvalidation(config),
    private val scheduler: MonitoringScheduler = MonitoringScheduler()
) {

    fun step(frame: ProactiveLoopFrame): ProactiveLoopResult {
        val cadences = scheduler.dueCadences(
            nowMillis = frame.nowMillis,
            oneMinuteCandleClosed = frame.oneMinuteCandleClosed,
            fiveMinuteCandleClosed = frame.fiveMinuteCandleClosed,
            fifteenMinuteCandleClosed = frame.fifteenMinuteCandleClosed
        )
        cadences.forEach { scheduler.markRun(it, frame.nowMillis) }
        val actions = cadences.flatMap { it.actions() }.distinct()

        var working = frame.campaigns
        var invalidationScore = working.maxOfOrNull { it.invalidationScore } ?: 0

        // §25: the score is recomputed on the fast telemetry cadence and the 1m close,
        // never on every delta.
        val scoresDue = MonitoringAction.RECOMPUTE_INVALIDATION_SCORE in actions ||
            MonitoringAction.EVALUATE_ACCEPTANCE_INVALIDATION in actions
        if (scoresDue) {
            working = working.map { evaluateInvalidation(it, frame) }
            invalidationScore = working.maxOfOrNull { it.invalidationScore } ?: 0
        }

        // §27/§33: a spread blowout makes every resting board unmanageable; watch-only.
        if (MonitoringAction.SPREAD_BLOWOUT_SAFETY in actions &&
            frame.asset.orderBookSpreadPct > config.maxSpreadPct
        ) {
            working = working.map { campaign ->
                if (campaign.status in RESTING_STATES) {
                    campaign.copy(
                        status = CampaignState.SUPPRESSED,
                        entryLadder = campaign.entryLadder.map { it.copy(isResting = false) },
                        topReasons = listOf(
                            InvalidationReason(
                                InvalidationFamily.LIQUIDITY_DISORDER,
                                20,
                                "Spread ${frame.asset.orderBookSpreadPct}% exceeds the " +
                                    "${config.maxSpreadPct}% ceiling; staging suppressed"
                            )
                        )
                    )
                } else {
                    campaign
                }
            }
        }

        if (MonitoringAction.EVALUATE_BOARD_PROMOTION in actions) {
            working = applySurvival(working, frame)
        }
        if (MonitoringAction.SUPPRESS_SECONDARY_OR_COOLDOWN in actions) {
            working = applyCapitalRouting(working, frame.marketCorrelation)
        }

        val suppressionReason = suppressionReason(frame)
        var staged: List<Campaign> = emptyList()
        if (suppressionReason == null &&
            frame.allowNewStaging &&
            MonitoringAction.EVALUATE_BOARD_PROMOTION in actions
        ) {
            val planned = campaignEngine.planCampaigns(frame.asset, frame.levelMap, frame.paths)
            val gated = executionEngine.gateStaging(
                planned,
                frame.mode,
                frame.envelope,
                frame.venueHealth,
                frame.hasActiveSession,
                frame.preLaunch
            )
            val knownIds = working.map { it.id }.toSet()
            val produced = gated
                .map { it.campaign }
                .filter { it.status != CampaignState.SUPPRESSED && it.id !in knownIds }
            // Only boards the execution mode actually routed count as staged; a
            // PLANNED board (shadow, no session, or a locked §38 gate) is visible in
            // the campaign list but routes nothing.
            staged = produced.filter { it.status in ROUTED_STATES }
            working = working + produced
        }

        val telemetry = ProactiveLoopTelemetry(
            asset = frame.asset.symbol,
            invalidationScore = invalidationScore,
            cadences = cadences,
            actions = actions,
            mode = frame.mode,
            activeBoardCount = working.count { it.status in LIVE_STATES },
            stagedBoardCount = working.count { it.status == CampaignState.STAGED },
            suppressionReason = suppressionReason
        )

        return ProactiveLoopResult(
            campaigns = working,
            cadences = cadences,
            actions = actions,
            stagedCampaigns = staged,
            stagingSuppressed = suppressionReason != null,
            suppressionReason = suppressionReason,
            invalidationScore = invalidationScore,
            telemetry = telemetry
        )
    }

    // ------------------------------------------------------------- invalidation

    private fun evaluateInvalidation(campaign: Campaign, frame: ProactiveLoopFrame): Campaign {
        if (campaign.status !in LIVE_STATES) return campaign

        val evidence = sideEvidenceFor(campaign, frame)
        val readings = sideAgnostic.evaluate(
            campaign.isLong,
            evidence,
            timeBudgetMinutes(campaign.role)
        )
        val score = readings.sumOf { it.points }.coerceIn(0, 100)
        val reasons = readings.map { it.toInvalidationReason() }

        return when {
            score >= config.hardInvalidationThreshold -> campaign.copy(
                invalidationScore = score,
                topReasons = reasons.take(3),
                status = CampaignState.CANCELLED,
                entryLadder = campaign.entryLadder.map { it.copy(isResting = false) },
                cooldownUntilTs = frame.nowMillis + config.reentryCooldownMillis()
            )

            score >= config.softInvalidationThreshold -> campaign.copy(
                invalidationScore = score,
                topReasons = reasons.take(3),
                status = if (campaign.entryLadder.any { it.isFilled }) {
                    CampaignState.REDUCED
                } else {
                    CampaignState.CANCELLED
                },
                entryLadder = campaign.entryLadder.map { slice ->
                    if (slice.isFilled) slice else slice.copy(isResting = false)
                }
            )

            else -> campaign.copy(invalidationScore = score, topReasons = reasons.take(3))
        }
    }

    private fun sideEvidenceFor(
        campaign: Campaign,
        frame: ProactiveLoopFrame
    ): SideAgnosticEvidence {
        val prices = campaign.entryLadder.map { it.price }
        val price = frame.asset.lastPrice
        val zoneLow = prices.minOrNull() ?: (price * 0.99)
        val zoneHigh = prices.maxOrNull() ?: (price * 1.01)
        val inside = price in zoneLow..zoneHigh
        val minutesInside = if (inside) {
            ((frame.nowMillis - campaign.createdAt).coerceAtLeast(0L)) / 60_000L
        } else {
            0L
        }
        return SideAgnosticEvidence(
            oneMinuteClose = price,
            zoneLow = zoneLow,
            zoneHigh = zoneHigh,
            counterImpulseQuality = if (campaign.isLong) {
                frame.evidence.acceptanceQuality
            } else {
                frame.evidence.rejectionQuality
            },
            reclaimReversedInstantly =
                frame.evidence.trapProbabilityPct >= TRAP_REVERSAL_PROBABILITY_PCT,
            priceChangePct = frame.asset.priceChange24h,
            oiDelta1hPct = frame.evidence.oiDelta1hPct,
            takerBuyRatio = frame.evidence.takerBuyRatio,
            minutesInsideZone = minutesInside
        )
    }

    private fun SideAgnosticReading.toInvalidationReason(): InvalidationReason {
        val family = when (trigger) {
            SideAgnosticTrigger.FLOW_CONTRADICTION -> InvalidationFamily.FLOW_FAILURE
            SideAgnosticTrigger.TIME_STAGNATION -> InvalidationFamily.TIME_EXPIRATION
            else -> InvalidationFamily.STRUCTURE_FAILURE
        }
        return InvalidationReason(family, points, explanation)
    }

    // ---------------------------------------------------------------- survival

    private fun applySurvival(
        campaigns: List<Campaign>,
        frame: ProactiveLoopFrame
    ): List<Campaign> {
        val livePrimary = campaigns.any {
            it.role == BoardRole.PRIMARY && it.status in LIVE_STATES
        }
        val primaryStalled = !livePrimary && campaigns.any { it.role == BoardRole.PRIMARY }
        val volumeConfirmed =
            maxOf(frame.evidence.acceptanceQuality, frame.evidence.rejectionQuality) >=
                SECONDARY_VOLUME_CONFIRMATION_QUALITY

        return campaigns.map { campaign ->
            if (campaign.status !in RESTING_STATES) return@map campaign

            val verdict = survivalMonitor.evaluate(
                BoardSurvivalInput(
                    campaignId = campaign.id,
                    role = campaign.role,
                    isLong = campaign.isLong,
                    priceChangePct = frame.asset.priceChange24h,
                    oiDelta1hPct = frame.evidence.oiDelta1hPct,
                    hasFilled = campaign.entryLadder.any { it.isFilled },
                    primaryStalled = primaryStalled,
                    secondaryVolumeConfirmed = volumeConfirmed,
                    structuralIntegrityBroken = boardStructureBroken(campaign, frame)
                )
            )

            when (verdict.action) {
                BoardSurvivalAction.KEEP -> campaign

                BoardSurvivalAction.DEGRADE_AND_CANCEL -> campaign.copy(
                    status = CampaignState.CANCELLED,
                    invalidationScore = maxOf(
                        campaign.invalidationScore,
                        config.softInvalidationThreshold
                    ),
                    topReasons = listOf(
                        InvalidationReason(
                            InvalidationFamily.POSITIONING_CONTRADICTION,
                            35,
                            verdict.explanation
                        )
                    ),
                    entryLadder = campaign.entryLadder.map { it.copy(isResting = false) }
                )

                BoardSurvivalAction.PROMOTE_TO_PRIMARY -> campaign.copy(
                    role = BoardRole.PRIMARY,
                    sizeMultiplier = verdict.sizeMultiplier,
                    priorityScore = campaign.priorityScore + PROMOTION_PRIORITY_BONUS
                )

                BoardSurvivalAction.SUPPRESS_SECONDARY -> campaign.copy(
                    status = CampaignState.SUPPRESSED,
                    entryLadder = campaign.entryLadder.map { it.copy(isResting = false) }
                )
            }
        }
    }

    private fun boardStructureBroken(campaign: Campaign, frame: ProactiveLoopFrame): Boolean {
        val readings = sideAgnostic.evaluate(
            campaign.isLong,
            sideEvidenceFor(campaign, frame),
            timeBudgetMinutes(campaign.role)
        )
        return readings.any {
            it.trigger == SideAgnosticTrigger.ACCEPTANCE ||
                it.trigger == SideAgnosticTrigger.TRAP_FAILURE
        }
    }

    // ----------------------------------------------------------------- routing

    private fun applyCapitalRouting(
        campaigns: List<Campaign>,
        marketCorrelation: Double
    ): List<Campaign> {
        val live = campaigns.filter { it.status in LIVE_STATES }
        val decision = capitalRouter.route(
            live.map {
                RoutedBoard(it.id, it.role, it.priorityScore, it.sizeBudgetUsd)
            },
            marketCorrelation
        )
        if (!decision.crisisMode) return campaigns

        return campaigns.map { campaign ->
            if (campaign.id in decision.cancelledBoardIds) {
                campaign.copy(
                    status = CampaignState.CANCELLED,
                    entryLadder = campaign.entryLadder.map { it.copy(isResting = false) }
                )
            } else {
                campaign
            }
        }
    }

    // ------------------------------------------------------------- suppression

    private fun suppressionReason(frame: ProactiveLoopFrame): String? = when {
        frame.decision?.suppressesStaging == true ->
            "AlphaPulse ${frame.decision.state} (quorum " +
                "${"%.2f".format(frame.decision.quorum)} below " +
                "${AlphaPulseDecision.MIN_QUORUM}) suppresses automated staging"

        frame.envelope.isKillSwitchEngaged ->
            "Emergency kill-switch engaged: staging locked"

        frame.envelope.isDailyLossExceeded ->
            "Daily loss limit ${frame.envelope.dailyLossLimitR}R reached: staging locked"

        !frame.venueHealth.isHealthy ->
            "Venue or hardware health guardrail tripped: staging suppressed"

        frame.asset.orderBookSpreadPct > config.maxSpreadPct ->
            "Spread ${frame.asset.orderBookSpreadPct}% exceeds " +
                "${config.maxSpreadPct}%: passive staging disabled"

        else -> null
    }

    private fun timeBudgetMinutes(role: BoardRole): Long = when (role) {
        BoardRole.PRIMARY -> 240L
        BoardRole.SECONDARY -> 180L
        BoardRole.DEFENSIVE -> 90L
        BoardRole.DISABLED -> 60L
    }

    companion object {
        /** A trap probability at or above this is the §28 instant-reversal evidence. */
        const val TRAP_REVERSAL_PROBABILITY_PCT = 70

        /** A secondary needs this much level quality before it may replace a primary. */
        const val SECONDARY_VOLUME_CONFIRMATION_QUALITY = 0.60

        /** Promotion must outrank the board it replaces, not merely tie it. */
        const val PROMOTION_PRIORITY_BONUS = 20.0

        val LIVE_STATES = setOf(
            CampaignState.STAGED,
            CampaignState.PARTIALLY_FILLED,
            CampaignState.ACTIVE,
            CampaignState.REDUCED
        )

        val RESTING_STATES = setOf(
            CampaignState.PLANNED,
            CampaignState.STAGED,
            CampaignState.PARTIALLY_FILLED
        )

        /** Statuses that mean orders or internal paper fills actually rest. */
        val ROUTED_STATES = setOf(
            CampaignState.STAGED,
            CampaignState.PARTIALLY_FILLED,
            CampaignState.ACTIVE
        )
    }
}
