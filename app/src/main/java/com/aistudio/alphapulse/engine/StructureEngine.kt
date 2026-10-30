package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.LevelZone
import com.aistudio.alphapulse.data.model.ZoneType
import com.aistudio.alphapulse.data.remote.BinanceFuturesClient
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 3 (Spec §8): level detection from real swing structure.
 *
 * [buildLevelMap] with candle history detects fractal swing highs/lows, clusters repeated
 * reaction points into zones, classifies them into the six [ZoneType] families relative
 * to the live price, and scores every zone by recency, touches, reaction magnitude and
 * timeframe importance — plus a decaying freshness score. The no-candle overload remains
 * as the offline/no-data fallback (Spec §5 staleness policy) so the map is always
 * constructible; it must never be the production path when candles exist.
 */
class StructureEngine {

    companion object {
        // §8 scoring weights (recency, touches, reaction magnitude, timeframe importance)
        private const val W_TOUCHES = 30
        private const val W_REACTION = 30
        private const val W_TIMEFRAME = 25
        private const val W_RECENCY = 15

        // §21 zone-width floor: max(0.25 x ATR(5m), a small price-relative minimum)
        private const val ATR_ZONE_MULTIPLIER = 0.25
        private const val MIN_RELATIVE_ZONE_WIDTH = 0.0005

        private fun decayRateFor(timeframe: String): Double = when (timeframe) {
            "5m" -> 1.5
            "15m" -> 0.8
            "1h" -> 0.3
            "4h" -> 0.08
            else -> 0.02
        }
    }

    /** One clustered reaction zone before [ZoneType] classification. */
    private data class SwingCluster(
        val priceLow: Double,
        val priceHigh: Double,
        val touches: Int,
        val lastTouchUtcMs: Long,
        val isHighDominant: Boolean,
        val avgReactionAtr: Double,
        val timeframe: String
    ) {
        val center: Double get() = (priceLow + priceHigh) / 2.0
    }

    /**
     * Offline fallback (Spec §5): synthesizes zones from ATR offsets around current price.
     * Kept for no-candle start-up frames and unit-test determinism; production staging
     * uses the candle-driven overload below.
     */
    fun buildLevelMap(asset: CryptoAsset): LevelMap {
        val zones = synthesizedZones(asset)
        val damageScore = if (asset.priceChange24h < -3.0) 72 else 28

        return LevelMap(
            asset = asset.symbol,
            zones = zones,
            dominantPivot = asset.lastPrice - (asset.atr5m * 0.2),
            damageScore = damageScore,
            confidenceSummary = 88,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * §8 Level Detection from real history:
     *  - swing highs/lows over adaptive windows per timeframe (4h:3, 1h:3, 5m:2 pivots),
     *  - greedy clustering of repeated reaction points into bands (not single ticks),
     *  - scoring by recency, touches, reaction magnitude and timeframe importance,
     *  - freshness seeded from the last touch using the same decay rates as
     *    [decayZoneFreshness] so UI decay continues seamlessly.
     */
    fun buildLevelMap(
        asset: CryptoAsset,
        candles4h: List<BinanceFuturesClient.Candle>,
        candles1h: List<BinanceFuturesClient.Candle>,
        candles5m: List<BinanceFuturesClient.Candle>
    ): LevelMap {
        val price = asset.lastPrice
        if (price <= 0.0) return buildLevelMap(asset)

        val atr5m = max(asset.atr5m, price * 0.001)
        val tolerance = max(atr5m * 0.75, price * MIN_RELATIVE_ZONE_WIDTH * 3.0)

        // Adaptive swing windows scale with timeframe importance (§8).
        val swings4h = IndicatorMath.swingPoints(candles4h.takeLast(120), window = 3)
        val swings1h = IndicatorMath.swingPoints(candles1h.takeLast(120), window = 3)
        val swings5m = IndicatorMath.swingPoints(candles5m.takeLast(120), window = 2)

        val clusters = clusterSwings(swings4h, "4h", tolerance, atr5m, candles4h) +
            clusterSwings(swings1h, "1h", tolerance, atr5m, candles1h) +
            clusterSwings(swings5m, "5m", tolerance, atr5m, candles5m)

        if (clusters.isEmpty()) return buildLevelMap(asset)

        val now = System.currentTimeMillis()
        val below = clusters.filter { it.center < price }.sortedByDescending { it.center }
        val above = clusters.filter { it.center > price }.sortedBy { it.center }

        fun confidenceOf(c: SwingCluster, freshness: Int): Int {
            val touchPts = ((c.touches - 1) * 12).coerceIn(0, W_TOUCHES)
            val reactionPts = ((c.avgReactionAtr / 2.5) * W_REACTION).coerceIn(0.0, W_REACTION.toDouble()).toInt()
            val timeframePts = when (c.timeframe) {
                "4h" -> W_TIMEFRAME
                "1h" -> 17
                else -> 10
            }
            val recencyPts = (W_RECENCY * freshness / 100.0).toInt()
            return (touchPts + reactionPts + timeframePts + recencyPts).coerceIn(5, 100)
        }

        fun toZone(c: SwingCluster, zoneType: ZoneType): LevelZone {
            val minSpan = max(atr5m * ATR_ZONE_MULTIPLIER, price * MIN_RELATIVE_ZONE_WIDTH)
            val low = min(c.priceLow, c.center - minSpan / 2.0)
            val high = max(c.priceHigh, c.center + minSpan / 2.0)
            val minutesSinceTouch = ((now - c.lastTouchUtcMs) / 60_000.0).coerceAtLeast(0.0)
            val freshness = (100 - minutesSinceTouch * decayRateFor(c.timeframe)).toInt().coerceIn(10, 100)
            return LevelZone(
                id = "${asset.symbol}_${zoneType.name}_${c.timeframe}",
                zoneType = zoneType,
                priceLow = low,
                priceHigh = high,
                timeframe = c.timeframe,
                confidence = confidenceOf(c, freshness),
                touches = c.touches,
                freshnessScore = freshness
            )
        }

        val chosen = LinkedHashMap<ZoneType, SwingCluster>()

        // HTF bands: strongest 4h clusters, else strongest 1h clusters.
        (below.firstOrNull { it.timeframe == "4h" } ?: below.firstOrNull { it.timeframe == "1h" })
            ?.let { chosen[ZoneType.HTF_SUPPORT] = it }
        (above.firstOrNull { it.timeframe == "4h" } ?: above.firstOrNull { it.timeframe == "1h" })
            ?.let { chosen[ZoneType.HTF_RESISTANCE] = it }

        // LTF pivot: the 5m cluster nearest price (either side), else nearest 1h cluster.
        val nearest = (clusters.sortedBy { abs(it.center - price) })
        (nearest.firstOrNull { it.timeframe == "5m" } ?: nearest.firstOrNull { it.timeframe == "1h" })
            ?.let { chosen[ZoneType.LTF_PIVOT] = it }

        // Reclaim: nearest MTF cluster just above price (broken level being retested).
        (above.firstOrNull { it.timeframe == "1h" } ?: above.firstOrNull { it.timeframe == "5m" }
            ?: above.firstOrNull())
            ?.let { chosen[ZoneType.RECLAIM_ZONE] = it }

        // Breakdown retest: nearest MTF/HTF cluster below price distinct from the support pick.
        (below.firstOrNull { it.timeframe == "1h" && it !== chosen[ZoneType.HTF_SUPPORT] }
            ?: below.firstOrNull { it !== chosen[ZoneType.HTF_SUPPORT] }
            ?: below.lastOrNull())
            ?.let { chosen[ZoneType.BREAKDOWN_ZONE] = it }

        // Stop pools: thin band of stop orders just beyond the most recent wick extremes.
        val highestRecentHigh = max(
            swings4h.filter { it.isHigh }.maxOfOrNull { it.price } ?: 0.0,
            swings1h.filter { it.isHigh }.maxOfOrNull { it.price } ?: 0.0
        )
        if (highestRecentHigh > 0.0) {
            val base = max(highestRecentHigh, price)
            chosen[ZoneType.STOP_POOL] = SwingCluster(
                priceLow = base + atr5m * 0.10,
                priceHigh = base + atr5m * 0.40,
                touches = 3,
                lastTouchUtcMs = now,
                isHighDominant = true,
                avgReactionAtr = 1.0,
                timeframe = "1h"
            )
        }

        // Fill any family the detection could not locate with the synthesized fallback so
        // the Campaign Library always has a complete six-family inventory (§7).
        val fallback = synthesizedZones(asset)
        val zones = ZoneType.entries.map { type ->
            chosen[type]?.let { toZone(it, type) }
                ?: fallback.first { it.zoneType == type }
        }

        // §8 damage: how much recent structure price has broken through.
        val recentLows = swings4h.filter { !it.isHigh }.takeLast(5)
        val brokenLows = recentLows.count { price < it.price }
        val recentHighs = swings4h.filter { it.isHigh }.takeLast(5)
        val reclaimedHighs = recentHighs.count { price > it.price }
        val damageScore = ((brokenLows * 14).coerceAtMost(60) + (20 - reclaimedHighs * 7).coerceIn(0, 20))
            .coerceIn(10, 90)

        val dominantPivot = nearest.firstOrNull()?.center ?: price

        return LevelMap(
            asset = asset.symbol,
            zones = zones,
            dominantPivot = dominantPivot,
            damageScore = damageScore,
            confidenceSummary = (zones.sumOf { it.confidence } / zones.size).coerceIn(0, 100),
            timestamp = now
        )
    }

    /** Greedy price-proximity clustering of swing points with reaction-magnitude stats. */
    private fun clusterSwings(
        swings: List<IndicatorMath.SwingPoint>,
        timeframe: String,
        tolerance: Double,
        atrRef: Double,
        candles: List<BinanceFuturesClient.Candle>
    ): List<SwingCluster> {
        if (swings.isEmpty() || atrRef <= 0.0) return emptyList()
        val sorted = swings.sortedBy { it.price }
        val groups = mutableListOf<MutableList<IndicatorMath.SwingPoint>>()
        for (swing in sorted) {
            val current = groups.lastOrNull()
            if (current != null && abs(swing.price - current.last().price) <= tolerance) {
                current.add(swing)
            } else {
                groups.add(mutableListOf(swing))
            }
        }

        return groups.map { group ->
            val reactionAtr = group.map { reactionMagnitudeAtr(it, swings, candles) / atrRef }
                .average()
                .coerceIn(0.0, 3.0)
            SwingCluster(
                priceLow = group.minOf { it.price },
                priceHigh = group.maxOf { it.price },
                touches = group.size,
                lastTouchUtcMs = group.maxOf { it.timeUtcMs },
                isHighDominant = group.count { it.isHigh } >= group.size - group.count { it.isHigh },
                avgReactionAtr = reactionAtr,
                timeframe = timeframe
            )
        }
    }

    /**
     * Reaction magnitude for one swing: the absolute move away from the extreme before
     * the next opposing swing prints (how hard the market rejected that level).
     */
    private fun reactionMagnitudeAtr(
        swing: IndicatorMath.SwingPoint,
        allSwings: List<IndicatorMath.SwingPoint>,
        candles: List<BinanceFuturesClient.Candle>
    ): Double {
        val next = allSwings.filter { it.index > swing.index }.minByOrNull { it.index }
        val endIdx = (next?.index ?: (candles.size - 1)).coerceAtMost(candles.size - 1)
        if (endIdx <= swing.index) return atrRefFallback(swing.price, swing.price)
        val segment = candles.subList(swing.index.coerceAtLeast(0), endIdx + 1)
        val opposingExtreme = if (swing.isHigh) {
            segment.minOf { it.low }
        } else {
            segment.maxOf { it.high }
        }
        return abs(swing.price - opposingExtreme)
    }

    private fun atrRefFallback(a: Double, b: Double): Double = abs(a - b)

    /**
     * Freshness decay: as time lapses, older zones decay in freshness score
     * (rates per minute, keyed by timeframe; tested for monotonicity).
     */
    fun decayZoneFreshness(zone: LevelZone, elapsedMinutes: Long): LevelZone {
        val decayRate = when (zone.timeframe) {
            "5m" -> 1.5
            "15m" -> 0.8
            "1h" -> 0.3
            "4h" -> 0.08
            else -> 0.02
        }
        val newFreshness = (zone.freshnessScore - (elapsedMinutes * decayRate)).toInt().coerceIn(10, 100)
        return zone.copy(freshnessScore = newFreshness)
    }

    /** Pre-Phase-3 ATR-offset synthesis, retained as the §5 no-candle fallback inventory. */
    private fun synthesizedZones(asset: CryptoAsset): List<LevelZone> {
        val p = asset.lastPrice
        val atr = asset.atr5m
        val zoneWidth = max(atr * 0.40, p * 0.003)

        val htfResistanceHigh = p + (atr * 4.2)
        val htfResistanceLow = htfResistanceHigh - zoneWidth

        val reclaimHigh = p + (atr * 1.5)
        val reclaimLow = reclaimHigh - (zoneWidth * 0.7)

        val pivot = p - (atr * 0.2)

        val htfSupportHigh = p - (atr * 2.8)
        val htfSupportLow = htfSupportHigh - zoneWidth

        val breakdownZoneHigh = p - (atr * 4.5)
        val breakdownZoneLow = breakdownZoneHigh - (zoneWidth * 0.8)

        val stopPoolAbove = p + (atr * 5.8)

        return listOf(
            LevelZone(
                id = "${asset.symbol}_STOP_POOL_UP",
                zoneType = ZoneType.STOP_POOL,
                priceLow = stopPoolAbove - (zoneWidth * 0.5),
                priceHigh = stopPoolAbove + (zoneWidth * 0.5),
                timeframe = "1d",
                confidence = 88,
                touches = 4,
                freshnessScore = 92
            ),
            LevelZone(
                id = "${asset.symbol}_HTF_RESIST",
                zoneType = ZoneType.HTF_RESISTANCE,
                priceLow = htfResistanceLow,
                priceHigh = htfResistanceHigh,
                timeframe = "4h",
                confidence = 94,
                touches = 5,
                freshnessScore = 85
            ),
            LevelZone(
                id = "${asset.symbol}_RECLAIM_ZONE",
                zoneType = ZoneType.RECLAIM_ZONE,
                priceLow = reclaimLow,
                priceHigh = reclaimHigh,
                timeframe = "1h",
                confidence = 78,
                touches = 3,
                freshnessScore = 95
            ),
            LevelZone(
                id = "${asset.symbol}_LTF_PIVOT",
                zoneType = ZoneType.LTF_PIVOT,
                priceLow = pivot - (zoneWidth * 0.3),
                priceHigh = pivot + (zoneWidth * 0.3),
                timeframe = "15m",
                confidence = 82,
                touches = 6,
                freshnessScore = 90
            ),
            LevelZone(
                id = "${asset.symbol}_HTF_SUPP",
                zoneType = ZoneType.HTF_SUPPORT,
                priceLow = htfSupportLow,
                priceHigh = htfSupportHigh,
                timeframe = "4h",
                confidence = 91,
                touches = 4,
                freshnessScore = 88
            ),
            LevelZone(
                id = "${asset.symbol}_BREAKDOWN",
                zoneType = ZoneType.BREAKDOWN_ZONE,
                priceLow = breakdownZoneLow,
                priceHigh = breakdownZoneHigh,
                timeframe = "1d",
                confidence = 85,
                touches = 2,
                freshnessScore = 79
            )
        )
    }
}
