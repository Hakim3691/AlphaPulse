package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CoinCalibration
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CandidatePath
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.PathDirection
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.data.model.TakeProfitTarget
import com.aistudio.alphapulse.data.model.ZoneType
import kotlin.math.max

/** §12 mirror pairs: support↔resistance, reclaim↔fade, breakout↔breakdown. */
private val MIRROR_FAMILIES: Map<CampaignFamily, CampaignFamily> = mapOf(
    CampaignFamily.SUPPORT_LONG to CampaignFamily.RESISTANCE_SHORT,
    CampaignFamily.RESISTANCE_SHORT to CampaignFamily.SUPPORT_LONG,
    CampaignFamily.RECLAIM_LONG to CampaignFamily.RECLAIM_FADE_SHORT,
    CampaignFamily.RECLAIM_FADE_SHORT to CampaignFamily.RECLAIM_LONG,
    CampaignFamily.BREAKOUT_LONG to CampaignFamily.BREAKDOWN_SHORT,
    CampaignFamily.BREAKDOWN_SHORT to CampaignFamily.BREAKOUT_LONG
)

/**
 * Generates all 6 symmetric campaign plans and assigns asymmetric board roles.
 * When [calibration] is supplied (§10), target depth is stretched along the side the
 * coin historically moves fastest.
 */
class CampaignEngine {

    fun planCampaigns(
    asset: CryptoAsset,
    levelMap: LevelMap,
    paths: List<CandidatePath>,
    totalRiskBudgetUsd: Double = 1000.0,
    calibration: CoinCalibration? = null,
    /** AlphaPulse's per-horizon price predictions for this asset (empty when none). */
    forecasts: List<ForecastTarget> = emptyList()
): List<Campaign> {
    val htfSupport = levelMap.zones.find { it.zoneType == ZoneType.HTF_SUPPORT }
    val htfResist = levelMap.zones.find { it.zoneType == ZoneType.HTF_RESISTANCE }
    val reclaimZone = levelMap.zones.find { it.zoneType == ZoneType.RECLAIM_ZONE }
    val pivotZone = levelMap.zones.find { it.zoneType == ZoneType.LTF_PIVOT }
    val breakdownZone = levelMap.zones.find { it.zoneType == ZoneType.BREAKDOWN_ZONE }

    val campaigns = mutableListOf<Campaign>()

    // Assign Board Roles according to Regime and Multi-Horizon Bias (Section 12, 16, 17)
    val roles = determineBoardRoles(asset.regime, asset.tacticalBias, asset.strategicBias)

    // AlphaPulse already forecasts this asset per horizon. Its prediction, not a
    // second candle pass, decides which side the boards favour and what they price.
    val forecastBias = ForecastBias.from(forecasts)

    // 1. Proactive Support Long
    val supportZone = htfSupport ?: pivotZone
    if (supportZone != null) {
        val role = roles[CampaignFamily.SUPPORT_LONG] ?: BoardRole.SECONDARY
        val sizeBudget = calculateSizeBudget(role, totalRiskBudgetUsd)
        val ladder = buildEntryLadder(
            isLong = true,
            zoneLow = supportZone.priceLow,
            zoneHigh = supportZone.priceHigh,
            atr = asset.atr5m,
            totalNotionalUsd = sizeBudget * 5.0, // 5x leverage basis
            orderType = OrderType.PASSIVE_LIMIT
        )
        campaigns.add(
            Campaign(
                id = "${asset.symbol}_SUPPORT_LONG",
                asset = asset.symbol,
                family = CampaignFamily.SUPPORT_LONG,
                regime = asset.regime,
                role = role,
                priorityScore = calculatePriority(role, paths, isLong = true),
                sizeBudgetUsd = sizeBudget,
                sizeMultiplier = if (role == BoardRole.PRIMARY) 1.25 else 0.75,
                entryLadder = ladder,
                stopLogic = StopLogic(
                    softThreshold = 45,
                    hardThreshold = 70,
                    hardStopPrice = Math.round((supportZone.priceLow - asset.atr5m * 0.8) * 1000.0) / 1000.0
                ),
                targets = listOf(
                    TakeProfitTarget("LTF Pivot Retest", levelMap.dominantPivot, 40),
                    TakeProfitTarget("HTF Supply Shelf", htfResist?.priceLow ?: (asset.lastPrice * 1.04), 40),
                    TakeProfitTarget("Runner Liquidity Sweep", (htfResist?.priceHigh ?: (asset.lastPrice * 1.07)), 20)
                ),
                invalidationScore = 18,
                topReasons = emptyList(),
                status = if (role == BoardRole.DISABLED) CampaignState.SUPPRESSED else CampaignState.STAGED
            )
        )
    }

    // 2. Proactive Resistance Short
    if (htfResist != null) {
        val role = roles[CampaignFamily.RESISTANCE_SHORT] ?: BoardRole.SECONDARY
        val sizeBudget = calculateSizeBudget(role, totalRiskBudgetUsd)
        val ladder = buildEntryLadder(
            isLong = false,
            zoneLow = htfResist.priceLow,
            zoneHigh = htfResist.priceHigh,
            atr = asset.atr5m,
            totalNotionalUsd = sizeBudget * 5.0,
            orderType = OrderType.PASSIVE_LIMIT
        )
        campaigns.add(
            Campaign(
                id = "${asset.symbol}_RESISTANCE_SHORT",
                asset = asset.symbol,
                family = CampaignFamily.RESISTANCE_SHORT,
                regime = asset.regime,
                role = role,
                priorityScore = calculatePriority(role, paths, isLong = false),
                sizeBudgetUsd = sizeBudget,
                sizeMultiplier = if (role == BoardRole.PRIMARY) 1.25 else 0.75,
                entryLadder = ladder,
                stopLogic = StopLogic(
                    softThreshold = 45,
                    hardThreshold = 70,
                    hardStopPrice = Math.round((htfResist.priceHigh + asset.atr5m * 0.8) * 1000.0) / 1000.0
                ),
                targets = listOf(
                    TakeProfitTarget("Midpoint Pivot", levelMap.dominantPivot, 40),
                    TakeProfitTarget("HTF Demand Band", supportZone?.priceHigh ?: (asset.lastPrice * 0.96), 40),
                    TakeProfitTarget("Breakdown Continuation", (breakdownZone?.priceLow ?: (asset.lastPrice * 0.92)), 20)
                ),
                invalidationScore = 22,
                topReasons = emptyList(),
                status = if (role == BoardRole.DISABLED) CampaignState.SUPPRESSED else CampaignState.STAGED
            )
        )
    }

    // 3. Proactive Reclaim Long
    if (reclaimZone != null) {
        val role = roles[CampaignFamily.RECLAIM_LONG] ?: BoardRole.SECONDARY
        val sizeBudget = calculateSizeBudget(role, totalRiskBudgetUsd)
        val ladder = buildEntryLadder(
            isLong = true,
            zoneLow = reclaimZone.priceLow,
            zoneHigh = reclaimZone.priceHigh,
            atr = asset.atr5m,
            totalNotionalUsd = sizeBudget * 5.0,
            orderType = OrderType.PASSIVE_LIMIT
        )
        campaigns.add(
            Campaign(
                id = "${asset.symbol}_RECLAIM_LONG",
                asset = asset.symbol,
                family = CampaignFamily.RECLAIM_LONG,
                regime = asset.regime,
                role = role,
                priorityScore = calculatePriority(role, paths, isLong = true) - 5.0,
                sizeBudgetUsd = sizeBudget,
                sizeMultiplier = 1.0,
                entryLadder = ladder,
                stopLogic = StopLogic(
                    softThreshold = 45,
                    hardThreshold = 70,
                    hardStopPrice = Math.round((reclaimZone.priceLow - asset.atr5m * 0.5) * 1000.0) / 1000.0
                ),
                targets = listOf(
                    TakeProfitTarget("Reclaim Expansion", reclaimZone.priceHigh + asset.atr5m * 1.5, 50),
                    TakeProfitTarget("HTF Resistance", htfResist?.center ?: (asset.lastPrice * 1.05), 50)
                ),
                invalidationScore = 20,
                topReasons = emptyList(),
                status = if (role == BoardRole.DISABLED) CampaignState.SUPPRESSED else CampaignState.STAGED
            )
        )
    }

    // 4. Proactive Reclaim Fade Short
    val fadeZone = reclaimZone ?: htfResist
    if (fadeZone != null) {
        val role = roles[CampaignFamily.RECLAIM_FADE_SHORT] ?: BoardRole.DEFENSIVE
        val sizeBudget = calculateSizeBudget(role, totalRiskBudgetUsd)
        val ladder = buildEntryLadder(
            isLong = false,
            zoneLow = fadeZone.priceLow,
            zoneHigh = fadeZone.priceHigh,
            atr = asset.atr5m,
            totalNotionalUsd = sizeBudget * 5.0,
            orderType = OrderType.PASSIVE_LIMIT
        )
        campaigns.add(
            Campaign(
                id = "${asset.symbol}_RECLAIM_FADE_SHORT",
                asset = asset.symbol,
                family = CampaignFamily.RECLAIM_FADE_SHORT,
                regime = asset.regime,
                role = role,
                priorityScore = calculatePriority(role, paths, isLong = false) - 8.0,
                sizeBudgetUsd = sizeBudget,
                sizeMultiplier = 0.8,
                entryLadder = ladder,
                stopLogic = StopLogic(
                    softThreshold = 40,
                    hardThreshold = 65,
                    hardStopPrice = Math.round((fadeZone.priceHigh + asset.atr5m * 0.6) * 1000.0) / 1000.0
                ),
                targets = listOf(
                    TakeProfitTarget("Failed Reclaim Retest", levelMap.dominantPivot, 60),
                    TakeProfitTarget("Demand Lows", supportZone?.center ?: (asset.lastPrice * 0.95), 40)
                ),
                invalidationScore = 28,
                topReasons = emptyList(),
                status = if (role == BoardRole.DISABLED) CampaignState.SUPPRESSED else CampaignState.STAGED
            )
        )
    }

    // 5. Proactive Breakout Long
    if (htfResist != null) {
        val role = roles[CampaignFamily.BREAKOUT_LONG] ?: BoardRole.DEFENSIVE
        val sizeBudget = calculateSizeBudget(role, totalRiskBudgetUsd)
        val ladder = buildStopLadder(
            isLong = true,
            thresholdPrice = htfResist.priceHigh,
            atr = asset.atr5m,
            totalNotionalUsd = sizeBudget * 5.0
        )
        campaigns.add(
            Campaign(
                id = "${asset.symbol}_BREAKOUT_LONG",
                asset = asset.symbol,
                family = CampaignFamily.BREAKOUT_LONG,
                regime = asset.regime,
                role = role,
                priorityScore = calculatePriority(role, paths, isLong = true) - 12.0,
                sizeBudgetUsd = sizeBudget,
                sizeMultiplier = 0.9,
                entryLadder = ladder,
                stopLogic = StopLogic(
                    softThreshold = 45,
                    hardThreshold = 70,
                    hardStopPrice = Math.round((htfResist.center) * 1000.0) / 1000.0
                ),
                targets = listOf(
                    TakeProfitTarget("Overhead Stop Pool Run", htfResist.priceHigh + asset.atr5m * 2.5, 60),
                    TakeProfitTarget("Trend Squeeze Extension", htfResist.priceHigh + asset.atr5m * 5.0, 40)
                ),
                invalidationScore = 32,
                topReasons = emptyList(),
                status = if (role == BoardRole.DISABLED) CampaignState.SUPPRESSED else CampaignState.STAGED
            )
        )
    }

    // 6. Proactive Breakdown Short
    if (breakdownZone != null || supportZone != null) {
        val breakThreshold = breakdownZone?.priceLow ?: (supportZone!!.priceLow)
        val role = roles[CampaignFamily.BREAKDOWN_SHORT] ?: BoardRole.DEFENSIVE
        val sizeBudget = calculateSizeBudget(role, totalRiskBudgetUsd)
        val ladder = buildStopLadder(
            isLong = false,
            thresholdPrice = breakThreshold,
            atr = asset.atr5m,
            totalNotionalUsd = sizeBudget * 5.0
        )
        campaigns.add(
            Campaign(
                id = "${asset.symbol}_BREAKDOWN_SHORT",
                asset = asset.symbol,
                family = CampaignFamily.BREAKDOWN_SHORT,
                regime = asset.regime,
                role = role,
                priorityScore = calculatePriority(role, paths, isLong = false) - 10.0,
                sizeBudgetUsd = sizeBudget,
                sizeMultiplier = 0.9,
                entryLadder = ladder,
                stopLogic = StopLogic(
                    softThreshold = 40,
                    hardThreshold = 65,
                    hardStopPrice = Math.round((breakThreshold + asset.atr5m * 0.7) * 1000.0) / 1000.0
                ),
                targets = listOf(
                    TakeProfitTarget("Liquidation Airpocket", breakThreshold - asset.atr5m * 2.5, 60),
                    TakeProfitTarget("Macro Structural Floor", breakThreshold - asset.atr5m * 5.0, 40)
                ),
                invalidationScore = 30,
                topReasons = emptyList(),
                status = if (role == BoardRole.DISABLED) CampaignState.SUPPRESSED else CampaignState.STAGED
            )
        )
    }

    // §15 Bias Score Model: bias bands modify priority, size multiplier, ladder
    // aggressiveness, target depth and cancel sensitivity — reproducibly, in one place.
    val biasAdjusted = campaigns.map { c ->
        val band = forecastBias
            ?.let { IndicatorMath.BiasBand.of(if (c.isLong) it.score else -it.score) }
            ?: directionalBand(asset, c.isLong)
        val favored = (c.isLong && band.sizeAdj > 0) || (!c.isLong && band.sizeAdj < 0)
        val opposed = (c.isLong && band.sizeAdj < 0) || (!c.isLong && band.sizeAdj > 0)
        val sizeBudget = (c.sizeBudgetUsd * (1 + band.sizeAdj)).coerceAtLeast(0.0)
        val ladder = reweightLadder(c.entryLadder, band.sizeAdj.coerceIn(-0.6, 0.6))
        // §10 calibration extends target depth along the side the coin moves fastest.
        val calibrationScale = when {
            calibration == null -> 1.0
            c.isLong -> calibration.impulseAsymmetry.coerceIn(0.7, 1.5)
            else -> (1.0 / calibration.impulseAsymmetry).coerceIn(0.7, 1.5)
        }
        val depthScale = (1.0 + band.sizeAdj * 0.10) * calibrationScale
        val targets = c.targets.mapIndexed { i, t ->
            if (i == c.targets.lastIndex) t.copy(price = t.price * depthScale) else t
        }
        // §21/§22 forecast-priced ladder: stage each take-profit at the price
        // AlphaPulse actually predicts for that horizon on this side of the book.
        val forecastTargets = forecastBias?.targetsFor(c.isLong).orEmpty()
        val pricedTargets = if (forecastTargets.isEmpty()) {
            targets
        } else {
            targets.mapIndexed { i, target ->
                val forecast = forecastTargets.getOrNull(i) ?: return@mapIndexed target
                target.copy(
                    price = forecast.predictedPrice,
                    levelName = "${target.levelName} (${forecast.horizonId} forecast)"
                )
            }
        }
        // Favored direction relaxes cancel sensitivity slightly; opposed tightens (§15).
        val stopAdj = if (favored) 2 else if (opposed) -2 else 0
        c.copy(
            priorityScore = c.priorityScore + band.priorityAdj +
                (forecastBias?.agreement(c.isLong) ?: 0.0),
            sizeBudgetUsd = sizeBudget,
            entryLadder = ladder,
            targets = pricedTargets,
            stopLogic = c.stopLogic.copy(
                softThreshold = (c.stopLogic.softThreshold + stopAdj).coerceIn(10, 90),
                hardThreshold = (c.stopLogic.hardThreshold + stopAdj).coerceIn(20, 95)
            )
        )
    }

    // §12 mirror rules: link every board to its structural mirror so §28 mirror
    // invalidation and mirror-activation can route deterministically.
    val idByFamily = biasAdjusted.associate { it.family to it.id }
    val withMirrors = biasAdjusted.map { c ->
        val mirrorId = MIRROR_FAMILIES[c.family]?.let { idByFamily[it] }
        if (mirrorId != null && mirrorId != c.id) c.copy(mirrorCampaignId = mirrorId) else c
    }
    return withMirrors.sortedByDescending { it.priorityScore }
    }
}

/** §15: directional bias for a campaign side (bullish bias boosts longs, not shorts). */
private fun directionalBand(asset: CryptoAsset, isLong: Boolean): IndicatorMath.BiasBand =
    IndicatorMath.BiasBand.of(if (isLong) asset.tacticalBias else -asset.tacticalBias)

/**
 * §15 ladder aggressiveness: front-loads weight toward the near-price edge for the
 * favored direction, pushes weight to the deeper exhaustion edge when opposed.
 */
private fun reweightLadder(ladder: List<LadderSlice>, frontLoad: Double): List<LadderSlice> {
    if (ladder.size != 3 || ladder.any { it.price <= 0.0 }) return ladder
    val totalNotional = ladder.sumOf { it.notionalUsd }
    if (totalNotional <= 0.0) return ladder
    val w0 = (0.30 + 0.15 * frontLoad).coerceIn(0.10, 0.55)
    val w1 = (0.35 - 0.075 * frontLoad).coerceIn(0.10, 0.55)
    val w2 = (1.0 - w0 - w1).coerceIn(0.05, 0.80)
    val weights = listOf(w0, w1, w2)
    return ladder.mapIndexed { i, slice ->
        val notional = Math.round(totalNotional * weights[i] * 100.0) / 100.0
        slice.copy(
            notionalUsd = notional,
            qty = Math.round(notional / slice.price * 100.0) / 100.0
        )
    }
}

private fun determineBoardRoles(
    regime: MarketRegime,
    tacticalBias: Int,
    strategicBias: Int
): Map<CampaignFamily, BoardRole> {
    val map = mutableMapOf<CampaignFamily, BoardRole>()

    when (regime) {
        MarketRegime.BULLISH_CONTINUATION -> {
            // Bull-dominant proactive asymmetry
            map[CampaignFamily.SUPPORT_LONG] = BoardRole.PRIMARY
            map[CampaignFamily.BREAKOUT_LONG] = BoardRole.PRIMARY
            map[CampaignFamily.RECLAIM_LONG] = BoardRole.SECONDARY
            map[CampaignFamily.RESISTANCE_SHORT] = BoardRole.DEFENSIVE
            map[CampaignFamily.RECLAIM_FADE_SHORT] = BoardRole.DISABLED
            map[CampaignFamily.BREAKDOWN_SHORT] = BoardRole.DISABLED
        }
        MarketRegime.BEARISH_CONTINUATION -> {
            // Bear-dominant proactive asymmetry
            map[CampaignFamily.RESISTANCE_SHORT] = BoardRole.PRIMARY
            map[CampaignFamily.BREAKDOWN_SHORT] = BoardRole.PRIMARY
            map[CampaignFamily.RECLAIM_FADE_SHORT] = BoardRole.SECONDARY
            map[CampaignFamily.SUPPORT_LONG] = BoardRole.DEFENSIVE
            map[CampaignFamily.BREAKOUT_LONG] = BoardRole.DISABLED
            map[CampaignFamily.RECLAIM_LONG] = BoardRole.DISABLED
        }
        MarketRegime.TRANSITION -> {
            // Split-bias: e.g. Tactical Bullish vs Strategic Bearish or vice-versa
            if (tacticalBias >= 0 && strategicBias < 0) {
                map[CampaignFamily.SUPPORT_LONG] = BoardRole.PRIMARY   // tactical
                map[CampaignFamily.RESISTANCE_SHORT] = BoardRole.PRIMARY // strategic supply
                map[CampaignFamily.RECLAIM_LONG] = BoardRole.SECONDARY
                map[CampaignFamily.RECLAIM_FADE_SHORT] = BoardRole.SECONDARY
                map[CampaignFamily.BREAKOUT_LONG] = BoardRole.DEFENSIVE
                map[CampaignFamily.BREAKDOWN_SHORT] = BoardRole.DEFENSIVE
            } else {
                map[CampaignFamily.RESISTANCE_SHORT] = BoardRole.PRIMARY
                map[CampaignFamily.SUPPORT_LONG] = BoardRole.PRIMARY
                map[CampaignFamily.RECLAIM_FADE_SHORT] = BoardRole.SECONDARY
                map[CampaignFamily.RECLAIM_LONG] = BoardRole.SECONDARY
                map[CampaignFamily.BREAKOUT_LONG] = BoardRole.DEFENSIVE
                map[CampaignFamily.BREAKDOWN_SHORT] = BoardRole.DEFENSIVE
            }
        }
        MarketRegime.BALANCE -> {
            // Mean reversion at edges
            map[CampaignFamily.SUPPORT_LONG] = BoardRole.PRIMARY
            map[CampaignFamily.RESISTANCE_SHORT] = BoardRole.PRIMARY
            map[CampaignFamily.RECLAIM_LONG] = BoardRole.SECONDARY
            map[CampaignFamily.RECLAIM_FADE_SHORT] = BoardRole.SECONDARY
            map[CampaignFamily.BREAKOUT_LONG] = BoardRole.DISABLED
            map[CampaignFamily.BREAKDOWN_SHORT] = BoardRole.DISABLED
        }
    }
    return map
}

private fun calculateSizeBudget(role: BoardRole, totalBudget: Double): Double {
    return when (role) {
        BoardRole.PRIMARY -> totalBudget * 0.50
        BoardRole.SECONDARY -> totalBudget * 0.28
        BoardRole.DEFENSIVE -> totalBudget * 0.12
        BoardRole.DISABLED -> 0.0
    }
}

private fun calculatePriority(
    role: BoardRole,
    paths: List<CandidatePath>,
    isLong: Boolean
): Double {
    val base = when (role) {
        BoardRole.PRIMARY -> 85.0
        BoardRole.SECONDARY -> 65.0
        BoardRole.DEFENSIVE -> 45.0
        BoardRole.DISABLED -> 10.0
    }
    val pathBonus = paths.firstOrNull { (it.direction == PathDirection.BULLISH) == isLong }?.probabilityScore ?: 50
    return base + (pathBonus * 0.15)
}

private fun buildEntryLadder(
    isLong: Boolean,
    zoneLow: Double,
    zoneHigh: Double,
    atr: Double,
    totalNotionalUsd: Double,
    orderType: OrderType
): List<LadderSlice> {
    val span = zoneHigh - zoneLow
    // 3 slices across zone
    val weights = listOf(0.30, 0.35, 0.35)
    return (0..2).map { i ->
        val price = if (isLong) {
            // Ladder bids down from high edge to low edge
            zoneHigh - (span * (i / 2.0))
        } else {
            // Ladder offers up from low edge to high edge
            zoneLow + (span * (i / 2.0))
        }
        val notional = totalNotionalUsd * weights[i]
        val qty = notional / price
        LadderSlice(
            sliceIndex = i + 1,
            price = Math.round(price * 1000.0) / 1000.0,
            qty = Math.round(qty * 100.0) / 100.0,
            notionalUsd = Math.round(notional * 100.0) / 100.0,
            orderType = orderType,
            isFilled = false, // fills arrive only from the execution/fill layer (§22, §25)
            isResting = true
        )
    }
}

private fun buildStopLadder(
    isLong: Boolean,
    thresholdPrice: Double,
    atr: Double,
    totalNotionalUsd: Double
): List<LadderSlice> {
    val weights = listOf(0.40, 0.60)
    return (0..1).map { i ->
        val price = if (isLong) {
            thresholdPrice + (atr * 0.15 * (i + 1))
        } else {
            thresholdPrice - (atr * 0.15 * (i + 1))
        }
        val notional = totalNotionalUsd * weights[i]
        val qty = notional / price
        LadderSlice(
            sliceIndex = i + 1,
            price = Math.round(price * 1000.0) / 1000.0,
            qty = Math.round(qty * 100.0) / 100.0,
            notionalUsd = Math.round(notional * 100.0) / 100.0,
            orderType = OrderType.STOP_LIMIT,
            isFilled = false,
            isResting = true
        )
    }
}