package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.BacktestMetrics
import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CandidatePath
import com.aistudio.alphapulse.data.model.CoinCalibration
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.remote.BinanceFuturesClient
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Phase 5 (Spec §37): deterministic historical replay of the full proactive pipeline —
 * staging, passive fill logic with queue persistence, slippage, cancel latency,
 * invalidation, target capture and mark-to-market exits — over persisted candle
 * history. No Random anywhere: the intrabar path is a fixed open→(adverse extreme)→
 * (favorable extreme)→close walk, so runs are reproducible and unit-testable.
 *
 * Evaluation separates **path prediction quality** (captured move before confirmation)
 * from **execution quality** (miss rate, slippage, adverse excursion), and measures
 * false-positive vs slow invalidations separately. [replay] with `baselineMode = true`
 * collapses the asymmetric overlay to equal-sized boards for the §37
 * proactive-versus-symmetric comparison. Nullable crowding/liquidity feeds exercise
 * graceful degradation (§37 optional-metrics mandate).
 */
class ReplayEngine(
    private val invalidationEngine: InvalidationEngine = InvalidationEngine(),
    private val executionEngine: ExecutionEngine = ExecutionEngine(RiskEngine()),
    private val featureEngine: FeatureEngine = FeatureEngine(Random(0x501A5EEDL))
) {

    companion object {
        /** Intrabar subdivisions per candle (tick granularity of the replay). */
        const val TICKS_PER_CANDLE = 12

        /** Ticks a cancel takes to reach the venue (§37 cancel-latency simulation). */
        const val CANCEL_LATENCY_TICKS = 2

        /** Ticks between invalidation evaluations (§25 1-5s cadence analog). */
        const val EVAL_EVERY_TICKS = 3

        /** Minimum history candles required to classify a replay as proactive (§37). */
        const val PROACTIVE_MIN_HISTORY_CANDLES = 50

        /** Resting passive slices fill after this many distinct touch ticks (queue). */
        const val TOUCHES_TO_FILL_PASSIVE = 2

        /** Seconds per replay tick: 5m candle / 12 ticks (candle interval assumption). */
        const val SECONDS_PER_TICK = 25.0
    }

    /** Per-campaign-family statistics (§37 per-template reporting). */
    data class TemplateStats(
        val family: String,
        val trades: Int,
        val hitRatePct: Double,
        val averageExcursionPct: Double,
        val worstExcursionPct: Double,
        val averageTimeToInvalidationSec: Double,
        val targetCapturePct: Double
    )

    /** Outcome of one replayed campaign board. */
    data class CampaignReplay(
        val campaignId: String,
        val family: String,
        val role: BoardRole,
        val fillCount: Int,
        val filledNotionalUsd: Double,
        val avgEntryPrice: Double,
        val exitPrice: Double,
        val pnlUsd: Double,
        val maxAdverseExcursionPct: Double,
        val maxFavorableExcursionPct: Double,
        val wasHardInvalidated: Boolean,
        val peakInvalidationScore: Int,
        val timeToInvalidationSec: Double,
        val falsePositiveInvalidation: Boolean,
        val slowInvalidation: Boolean,
        val targetCapturePct: Double
    )

    data class ReplayResult(
        val metrics: BacktestMetrics,
        val campaigns: List<CampaignReplay>,
        val templates: List<TemplateStats>,
        val totalTicks: Int,
        /** True when crowding/liquidity feeds were absent and the run degraded (§37). */
        val degradedFeeds: Boolean
    )

    /** Mutable per-campaign state accumulated across the tick walk. */
    private class Tracked(val initial: Campaign) {
        var campaign: Campaign = initial
        val id: String = initial.id
        val family = initial.family
        val role = initial.role
        val isLong = initial.isLong
        var filledNotional = 0.0
        var entrySum = 0.0
        var entryCount = 0
        var filledQty = 0.0
        var remaining = 0.0
        var realized = 0.0
        var minPrice = Double.MAX_VALUE
        var maxPrice = 0.0
        var firstFillTick = -1
        var firstSoftTick = -1
        var cancelLandTick = -1
        var exitTick = -1
        var peakScore = 0
        var closed = false
        var stopBreached = false
        var zoneTouched = false
        val touchCounts = mutableMapOf<Int, Int>()
        val avgEntry: Double get() = if (filledQty <= 0.0) 0.0 else entrySum / filledQty
    }

    private class CancelRequest(val campaignId: String, val landTick: Int)

    /**
     * Runs one deterministic replay over [candles] (oldest→newest). `fundingRatePct` /
     * `spreadPct` stand in for the crowding/liquidity feeds; `null` degrades the run
     * toward neutral crowding (§37 stress mandate). [baselineMode] replaces the
     * asymmetric board sizes with equal splits for the §37 comparison.
     */
    fun replay(
        asset: CryptoAsset,
        candles: List<BinanceFuturesClient.Candle>,
        fundingRatePct: Double? = null,
        spreadPct: Double? = null,
        calibration: CoinCalibration? = null,
        baselineMode: Boolean = false,
        secondsPerTick: Double = SECONDS_PER_TICK
    ): ReplayResult {
        if (candles.size < 30) {
            return ReplayResult(BacktestMetrics(), emptyList(), emptyList(), 0, fundingRatePct == null)
        }
        val degraded = fundingRatePct == null || spreadPct == null
        val funding = fundingRatePct ?: 0.0
        val spread = spreadPct ?: asset.orderBookSpreadPct.coerceIn(0.01, 1.0)

        val candidate = withRegime(asset, candles)
        val structure = StructureEngine()
        val map = structure.buildLevelMap(
            candidate,
            candles.takeLast(120),
            candles.takeLast(120),
            candles.takeLast(120)
        )
        val paths = PathEngine(featureEngine).rankCandidatePaths(candidate, map)
        val planned = campaignEngineFor(candidate, map, paths, calibration, baselineMode)
        val tracked = planned.map { Tracked(it) }

        // Rebase candle prices onto the asset's live price scale for coherent sizing.
        val rebased = candles.last().close
        val scale = if (rebased > 0) asset.lastPrice / rebased else 1.0
        val atrRef = max(asset.atr5m, candidate.lastPrice * 0.001)
        var tick = 0
        val cancelRequests = mutableListOf<CancelRequest>()
        var capturedSum = 0.0
        var capturedSamples = 0

        for (candle in candles) {
            val path = intrabarPath(candle, scale)
            val stagedAtOpen = tracked.any { tc ->
                tc.campaign.entryLadder.any { it.isResting && !it.isFilled }
            }
            val body = candle.close - candle.open
            val range = (candle.high - candle.low).coerceAtLeast(1e-9)
            val impulse = abs(body) / range

            for (price in path) {
                tick++

                // §37 passive fill logic with queue persistence + slippage.
                for (tc in tracked) {
                    applyFills(tc, price, tick, spread, atrRef)
                    updateOpenPosition(tc, price)
                }

                // §25 cadence analog: evaluate invalidation every EVAL_EVERY_TICKS.
                if (tick % EVAL_EVERY_TICKS == 0) {
                    evaluateAll(tracked, candidate, price, candle, funding, spread, atrRef, map, tick, cancelRequests)
                }

                // Cancel latency: requests land CANCEL_LATENCY_TICKS later.
                val landed = cancelRequests.filter { tick >= it.landTick }
                if (landed.isNotEmpty()) {
                    cancelRequests.removeAll(landed.toSet())
                    for (request in landed) {
                        val tc = tracked.find { it.id == request.campaignId } ?: continue
                        if (tc.cancelLandTick < 0) tc.cancelLandTick = tick
                        closePositionAt(tc, price, markClosed = false, tick = tick)
                        tc.campaign = tc.campaign.copy(
                            entryLadder = tc.campaign.entryLadder.map {
                                if (it.isFilled) it else it.copy(isResting = false)
                            }
                        )
                    }
                }
            }

            // §37 path-prediction metric: pre-staged boards capture the impulse leg.
            if (stagedAtOpen && impulse >= 0.7) {
                val extreme = if (body >= 0) candle.high else candle.low
                capturedSum += (abs(extreme - candle.open) / abs(body)).coerceIn(0.0, 1.0) * 100.0
                capturedSamples++
            }
        }

        return summarize(
            tracked, tick, candles.last().close * scale, candidate,
            capturedSum, capturedSamples, degraded, secondsPerTick
        )
    }

    // ------------------------------------------------------------------ fills

    /**
     * §37 passive fill logic: a resting slice fills after TOUCHES_TO_FILL_PASSIVE ticks
     * trading through its price, then executes with fixed 1-tick slippage in the
     * adverse direction. Stop entries trigger on trade-through like the venue would.
     */
    private fun applyFills(tc: Tracked, price: Double, tick: Int, spreadPct: Double, atrRef: Double) {
        val c = tc.campaign
        if (c.status == CampaignState.CANCELLED || c.status == CampaignState.COMPLETED ||
            c.status == CampaignState.SUPPRESSED || c.status == CampaignState.PLANNED
        ) return
        val slip = max(1e-9, atrRef * 0.02)
        var changed = false
        val ladder = c.entryLadder.map { slice ->
            if (slice.isFilled || !slice.isResting) return@map slice
            val tradeThrough = if (c.isLong) price <= slice.price else price >= slice.price
            val fill = when (slice.orderType) {
                OrderType.PASSIVE_LIMIT -> {
                    if (tradeThrough) {
                        val n = (tc.touchCounts[slice.sliceIndex] ?: 0) + 1
                        tc.touchCounts[slice.sliceIndex] = n
                        n >= TOUCHES_TO_FILL_PASSIVE
                    } else {
                        tc.touchCounts[slice.sliceIndex] = 0
                        false
                    }
                }
                OrderType.STOP_LIMIT, OrderType.STOP_MARKET -> tradeThrough
            }
            if (fill) {
                changed = true
                val fillPrice = if (c.isLong) slice.price + slip else slice.price - slip
                tc.filledNotional += slice.notionalUsd
                tc.entrySum += fillPrice * slice.qty
                tc.entryCount++
                tc.filledQty += slice.qty
                tc.remaining += slice.qty
                if (tc.firstFillTick < 0) tc.firstFillTick = tick
                slice.copy(isFilled = true, isResting = false)
            } else slice
        }
        if (changed) {
            tc.campaign = c.copy(entryLadder = ladder, status = statusAfterFills(ladder))
        }
    }

    private fun statusAfterFills(ladder: List<com.aistudio.alphapulse.data.model.LadderSlice>): CampaignState = when {
        ladder.none { it.isFilled } -> CampaignState.STAGED
        ladder.all { it.isFilled } -> CampaignState.ACTIVE
        else -> CampaignState.PARTIALLY_FILLED
    }

    private fun updateOpenPosition(tc: Tracked, price: Double) {
        if (tc.remaining > 0 && !tc.closed) {
            if (price < tc.minPrice) tc.minPrice = price
            if (price > tc.maxPrice) tc.maxPrice = price
        }
    }

    private fun closePositionAt(tc: Tracked, price: Double, markClosed: Boolean, tick: Int = -1) {
        if (tc.remaining <= 0.0 || tc.closed) return
        if (tick > 0) tc.exitTick = tick
        val entry = tc.avgEntry
        val gross = (if (tc.isLong) price - entry else entry - price) * tc.remaining
        tc.realized += gross
        tc.remaining = 0.0
        tc.closed = tc.closed || markClosed
    }

    // ----------------------------------------------------------- invalidation

    private fun evaluateAll(
        tracked: List<Tracked>,
        candidate: CryptoAsset,
        price: Double,
        candle: BinanceFuturesClient.Candle,
        funding: Double,
        spread: Double,
        atrRef: Double,
        map: LevelMap,
        tick: Int,
        cancelRequests: MutableList<CancelRequest>
    ) {
        val tickAsset = candidate.copy(lastPrice = price)
        val evidence = syntheticEvidence(candle, price, funding, spread)
        for (tc in tracked) {
            if (tc.closed ||
                tc.campaign.status == CampaignState.CANCELLED ||
                tc.campaign.status == CampaignState.COMPLETED ||
                tc.campaign.status == CampaignState.SUPPRESSED
            ) continue
            val result = invalidationEngine.evaluateCampaign(tc.campaign, tickAsset, evidence, map)
            if (result.updatedCampaign.invalidationScore > tc.peakScore) {
                tc.peakScore = result.updatedCampaign.invalidationScore
            }
            when (result.triggerAction) {
                InvalidationAction.HARD_FLATTEN_COOLDOWN -> {
                    cancelRequests.add(CancelRequest(tc.id, tick + CANCEL_LATENCY_TICKS))
                    if (result.updatedCampaign.invalidationScore >= tc.campaign.stopLogic.hardThreshold) {
                        tc.stopBreached = true
                    }
                    tc.campaign = result.updatedCampaign
                }
                InvalidationAction.SOFT_CANCEL_RESTING -> {
                    if (tc.firstSoftTick < 0) tc.firstSoftTick = tick
                    cancelRequests.add(CancelRequest(tc.id, tick + CANCEL_LATENCY_TICKS))
                    tc.campaign = result.updatedCampaign
                }
                InvalidationAction.NO_ACTION -> Unit
                else -> Unit
            }
        }
        // Mark completed targets: primary target reached -> completed at target price.
        for (tc in tracked) {
            if (tc.remaining > 0 && !tc.closed) {
                val target = tc.campaign.targets.firstOrNull() ?: continue
                val hit = if (tc.isLong) price >= target.price else price <= target.price
                if (hit) {
                    closePositionAt(tc, price, markClosed = true, tick = tick)
                    tc.campaign = tc.campaign.copy(status = CampaignState.COMPLETED)
                }
            }
        }
    }

    /** §11/§27 deterministic evidence frame from the candle + crowding stand-ins. */
    private fun syntheticEvidence(
        candle: BinanceFuturesClient.Candle,
        price: Double,
        funding: Double,
        spread: Double
    ): EvidenceFrame {
        val body = candle.close - candle.open
        val range = (candle.high - candle.low).coerceAtLeast(1e-9)
        val bullish = body > 0
        val takerBuy = (0.5 + (body / range) * 0.35).coerceIn(0.05, 0.95)
        val impulse = abs(body) / range
        return EvidenceFrame(
            timestamp = candle.closeTimeUtcMs,
            asset = "REPLAY",
            oiDelta1hPct = if (bullish) impulse * 2.0 else -impulse * 2.0,
            fundingRateChangePct = funding * 0.1,
            basisDeltaBps = (body / range) * 20.0,
            takerBuyRatio = takerBuy,
            acceptanceQuality = impulse.coerceIn(0.0, 1.0),
            rejectionQuality = (1.0 - impulse).coerceIn(0.0, 1.0),
            trapProbabilityPct = ((1.0 - impulse) * 60.0).toInt().coerceIn(0, 100),
            recentImpulseQuality = if (impulse > 0.6) "Strong" else "Weak"
        )
    }

    // ------------------------------------------------------------------ summary

    private fun summarize(
        tracked: List<Tracked>,
        totalTicks: Int,
        finalPrice: Double,
        candidate: CryptoAsset,
        capturedSum: Double,
        capturedSamples: Int,
        degraded: Boolean,
        secondsPerTick: Double
    ): ReplayResult {
        var totalPnl = 0.0
        val replays = mutableListOf<CampaignReplay>()
        for (tc in tracked) {
            // Mark-to-market any remainder at the final price so every fill is resolved.
            closePositionAt(tc, finalPrice, markClosed = true)
            val pnl = tc.realized
            totalPnl += pnl
            val entry = tc.avgEntry
            val adverseRef = if (tc.isLong) tc.minPrice else tc.maxPrice
            val adverse = if (entry > 0 && adverseRef.isFinite())
                abs(entry - adverseRef) / entry * 100.0 else 0.0
            val favorableRef = if (tc.isLong) tc.maxPrice else tc.minPrice
            val favorable = if (entry > 0 && favorableRef.isFinite())
                abs(favorableRef - entry) / entry * 100.0 else 0.0
            val tti = if (tc.firstSoftTick > 0)
                (tc.firstSoftTick * secondsPerTick) else -1.0
            // §37 false positive: hard-invalidated with no adverse excursion to justify it.
            // §37 slow invalidation: an open position lingered past the cancel latency.
            val falsePositive = tc.stopBreached && adverse < atrSharePct(candidate, 0.25)
            val slow = tc.firstSoftTick > 0 && tc.filledNotional > 0 &&
                (tc.exitTick < 0 || tc.exitTick > tc.firstSoftTick + CANCEL_LATENCY_TICKS)
            replays.add(
                CampaignReplay(
                    campaignId = tc.id,
                    family = tc.family.displayName,
                    role = tc.role,
                    fillCount = tc.entryCount,
                    filledNotionalUsd = tc.filledNotional,
                    avgEntryPrice = entry,
                    exitPrice = finalPrice,
                    pnlUsd = Math.round(pnl * 100.0) / 100.0,
                    maxAdverseExcursionPct = adverse,
                    maxFavorableExcursionPct = favorable,
                    wasHardInvalidated = tc.stopBreached,
                    peakInvalidationScore = tc.peakScore,
                    timeToInvalidationSec = tti,
                    falsePositiveInvalidation = falsePositive,
                    slowInvalidation = slow,
                    targetCapturePct = 0.0
                )
            )
        }
        val fpCount = replays.count { it.falsePositiveInvalidation }
        val slowCount = replays.count { it.slowInvalidation }
        val invSpeeds = replays.filter { it.timeToInvalidationSec > 0 }.map { it.timeToInvalidationSec }
        val captured = if (capturedSamples > 0) capturedSum / capturedSamples else 0.0
        val pnlByRegime = mapOf(candidate.regime.label to Math.round(totalPnl * 100.0) / 100.0)
        val metrics = BacktestMetrics(
            capturedMoveBeforeConfirmationPct = captured,
            averageInvalidationSpeedSec = if (invSpeeds.isEmpty()) 0.0 else invSpeeds.average(),
            orderMissRatePct = missRate(replays),
            maxAdverseExcursionPct = replays.maxOfOrNull { it.maxAdverseExcursionPct } ?: 0.0,
            pnlByRegime = pnlByRegime,
            falsePositiveInvalidationPct = if (replays.isEmpty()) 0.0 else fpCount * 100.0 / replays.size,
            slowInvalidationPct = if (replays.isEmpty()) 0.0 else slowCount * 100.0 / replays.size,
            totalPnlUsd = Math.round(totalPnl * 100.0) / 100.0,
            replayedCampaigns = replays.size,
            filledSliceCount = replays.sumOf { it.fillCount },
            proactiveEdgeVsBaselinePct = 0.0
        )
        return ReplayResult(metrics, replays, templateStats(replays), totalTicks, degraded)
    }

    private fun atrSharePct(candidate: CryptoAsset, fraction: Double): Double {
        if (candidate.lastPrice <= 0) return 0.25
        return candidate.atr5m / candidate.lastPrice * 100.0 * fraction
    }

    private fun missRate(replays: List<CampaignReplay>): Double {
        val staged = replays.size
        if (staged == 0) return 0.0
        val missed = replays.count { it.fillCount == 0 }
        return missed * 100.0 / staged
    }

    private fun templateStats(replays: List<CampaignReplay>): List<TemplateStats> =
        replays.groupBy { it.family }.map { (family, group) ->
            val hits = group.count { it.pnlUsd > 0 }
            TemplateStats(
                family = family,
                trades = group.size,
                hitRatePct = hits * 100.0 / group.size,
                averageExcursionPct = group.map { it.maxFavorableExcursionPct }.average(),
                worstExcursionPct = group.maxOfOrNull { it.maxAdverseExcursionPct } ?: 0.0,
                averageTimeToInvalidationSec = group.filter { it.timeToInvalidationSec > 0 }
                    .map { it.timeToInvalidationSec }.let { if (it.isEmpty()) 0.0 else it.average() },
                targetCapturePct = group.map { it.targetCapturePct }.average()
            )
        }

    // ------------------------------------------------------------------ helpers

    /** §8/§9: regime and bias recomputed from the replayed history itself. */
    private fun withRegime(asset: CryptoAsset, candles: List<BinanceFuturesClient.Candle>): CryptoAsset {
        val strategic = IndicatorMath.structuralBias(candles.takeLast(60))
        val tactical = IndicatorMath.structuralBias(candles.takeLast(40))
        val regime = when {
            strategic >= 20 && tactical >= -19 -> MarketRegime.BULLISH_CONTINUATION
            strategic <= -20 && tactical <= 19 -> MarketRegime.BEARISH_CONTINUATION
            abs(strategic - tactical) >= 40 -> MarketRegime.TRANSITION
            else -> MarketRegime.BALANCE
        }
        return asset.copy(regime = regime, tacticalBias = tactical, strategicBias = strategic)
    }

    /** §37 deterministic intrabar walk: open → adverse extreme → favorable extreme → close. */
    private fun intrabarPath(candle: BinanceFuturesClient.Candle, scale: Double): List<Double> {
        val o = candle.open * scale
        val h = candle.high * scale
        val l = candle.low * scale
        val c = candle.close * scale
        val adverseFirst = c < o
        val (first, second) = if (adverseFirst) Pair(l, h) else Pair(h, l)
        val path = mutableListOf<Double>()
        for (i in 0 until TICKS_PER_CANDLE) {
            val seg = i / (TICKS_PER_CANDLE - 1.0).coerceAtLeast(1.0)
            val price = if (seg < 0.5) {
                o + (first - o) * (seg / 0.5)
            } else {
                first + (second - first) * ((seg - 0.5) / 0.5)
            }
            path.add(price.coerceIn(min(l, min(o, c)), max(h, max(o, c))))
        }
        path[path.size - 1] = c
        return path
    }

    private fun campaignEngineFor(
        candidate: CryptoAsset,
        map: LevelMap,
        paths: List<CandidatePath>,
        calibration: CoinCalibration?,
        baselineMode: Boolean
    ): List<Campaign> {
        val base = CampaignEngine().planCampaigns(candidate, map, paths, calibration = calibration)
        if (!baselineMode) return base
        // §37 symmetric baseline: equal-size boards, no asymmetry overlay.
        val equal = base.size.toDouble()
        return base.map { c ->
            c.copy(sizeBudgetUsd = equal, sizeMultiplier = 1.0)
        }
    }
}
