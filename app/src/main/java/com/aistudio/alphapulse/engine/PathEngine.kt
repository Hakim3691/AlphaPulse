package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.CandidatePath
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.PathDirection
import com.aistudio.alphapulse.data.model.ZoneType

class PathEngine(private val featureEngine: FeatureEngine) {

    fun rankCandidatePaths(asset: CryptoAsset, levelMap: LevelMap): List<CandidatePath> {
        val paths = mutableListOf<CandidatePath>()

        val htfResist = levelMap.zones.find { it.zoneType == ZoneType.HTF_RESISTANCE }
        val htfSupport = levelMap.zones.find { it.zoneType == ZoneType.HTF_SUPPORT }
        val reclaimZone = levelMap.zones.find { it.zoneType == ZoneType.RECLAIM_ZONE }
        val breakdownZone = levelMap.zones.find { it.zoneType == ZoneType.BREAKDOWN_ZONE }

        // Path 1: Upside Pain Sweep towards HTF Resistance
        if (htfResist != null) {
            val pain = featureEngine.calculatePainScore(asset, isBullishTarget = true)
            val efficiency = if (asset.tacticalBias > 0) 86 else 48
            val cleanliness = if (asset.priceChange24h > 0) 82 else 55
            val prob = ((pain * 0.4) + (efficiency * 0.3) + (cleanliness * 0.3)).toInt().coerceIn(10, 95)
            paths.add(
                CandidatePath(
                    pathId = "PATH_BULL_SWEEP",
                    direction = PathDirection.BULLISH,
                    targetDescription = "HTF Supply Sweep & Exhaustion",
                    targetPrice = htfResist.center,
                    painScore = pain,
                    efficiencyScore = efficiency,
                    cleanlinessScore = cleanliness,
                    probabilityScore = prob
                )
            )
        }

        // Path 2: Downside Liquidation Flush to HTF Support
        if (htfSupport != null) {
            val pain = featureEngine.calculatePainScore(asset, isBullishTarget = false)
            val efficiency = if (asset.tacticalBias < 0) 84 else 52
            val cleanliness = if (asset.priceChange24h < 0) 80 else 60
            val prob = ((pain * 0.4) + (efficiency * 0.3) + (cleanliness * 0.3)).toInt().coerceIn(10, 95)
            paths.add(
                CandidatePath(
                    pathId = "PATH_BEAR_FLUSH",
                    direction = PathDirection.BEARISH,
                    targetDescription = "Support Shelf Stop-Run & Reversal",
                    targetPrice = htfSupport.center,
                    painScore = pain,
                    efficiencyScore = efficiency,
                    cleanlinessScore = cleanliness,
                    probabilityScore = prob
                )
            )
        }

        // Path 3: Reclaim Continuation
        if (reclaimZone != null) {
            paths.add(
                CandidatePath(
                    pathId = "PATH_RECLAIM_EXP",
                    direction = PathDirection.BULLISH,
                    targetDescription = "Reclaim Threshold Expansion",
                    targetPrice = reclaimZone.center,
                    painScore = 65,
                    efficiencyScore = 78,
                    cleanlinessScore = 75,
                    probabilityScore = if (asset.tacticalBias >= 20) 74 else 45
                )
            )
        }

        // Path 4: Breakdown Cascade
        if (breakdownZone != null) {
            paths.add(
                CandidatePath(
                    pathId = "PATH_BREAKDOWN_RUN",
                    direction = PathDirection.BEARISH,
                    targetDescription = "Structural Breakdown Cascade",
                    targetPrice = breakdownZone.center,
                    painScore = 72,
                    efficiencyScore = 80,
                    cleanlinessScore = 72,
                    probabilityScore = if (asset.tacticalBias <= -20) 76 else 42
                )
            )
        }

        return paths.sortedByDescending { it.combinedPriority }
    }
}
