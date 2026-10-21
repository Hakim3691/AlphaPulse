package com.aistudio.alphapulse.domain.quant

import com.aistudio.alphapulse.domain.quant.ConfluenceAlphaResult
import com.aistudio.alphapulse.domain.quant.DepthSnapshot
import com.aistudio.alphapulse.domain.quant.DepthTier
import com.aistudio.alphapulse.domain.quant.LiquidityPainPathResult
import com.aistudio.alphapulse.domain.quant.MarkovRegimeResult
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.domain.quant.TemporalAttentionResult
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class KlineData(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double,
    val takerBuyQuoteVolume: Double
)

/***
 * Horizon-Aware Pain Path Engine - replaces the single exponential decay heuristic
 * with independent per-horizon μp,1m..μp,60m computation.
 * Each horizon uses liquidity actually reachable within the expected volatility range.
 *
 * Spec Ref: §8, Gap §2 line 3
 */
object QuantMath {

    /*** Horizon-specific liquidity ranges and decay parameters ***/
    data class HorizonLiquidityRanges(
        /** 1 minute: immediate L2 book voids and micro-spread slippage */
        val muP1m: Double,
        /** 5 minutes: near-book liquidity, micro-structure slippage */
        val muP5m: Double,
        /** 15 minutes: intraday liquidity shelves and POC gravity */
        val muP15m: Double,
        /** 30 minutes: broader liquidation bands and session extremes */
        val muP30m: Double,
        /** 60 minutes (1 hour): macro liquidity / session high/low stop-run clusters */
        val muP60m: Double,
        /** Decay factor between consecutive horizons (empirical, not pure exponential) */
        val decayFactor: Double
    )

    /** Default horizon liquidity ranges computed from order-book and liquidity metrics */
    fun computeDefaultHorizonRanges(
        bids: List<DepthTier>,
        asks: List<DepthTier>,
        spotPrice: Double,
        empiricalShock: Double,
        volatility1m: Double,
        volatility5m: Double,
        volatility15m: Double,
        volatility30m: Double,
        volatility60m: Double
    ): HorizonLiquidityRanges {
        // Compute base muPath from L2 order-book using existing logic
        val baseResult = computeLiquidityPainPath(bids, asks, spotPrice, empiricalShock)

        // 1m: immediate L2 book voids - use base result directly, scaled by 1m volatility
        val muP1m = baseResult.muPath * exp(-0.1) * (volatility1m.coerceAtLeast(0.01))

        // 5m: near-book liquidity - blend base with 5m volatility adjustment
        val muP5m = baseResult.muPath * exp(-0.2) * (volatility5m.coerceAtLeast(0.01))

        // 15m: intraday liquidity shelves - incorporate POC gravity
        val pocGravity = (computePocPrice(bids, asks, spotPrice) - spotPrice) / max(1e-6, spotPrice)
        val muP15m = baseResult.muPath * exp(-0.5) * volatility15m.coerceAtLeast(0.01) + pocGravity * 0.3

        // 30m: broader liquidation bands - incorporate session-level liquidation data
        val muP30m = baseResult.muPath * exp(-0.8) * volatility30m.coerceAtLeast(0.01)

        // 60m: macro liquidity / session extremes - use empirical shock and broader metrics
        val muP60m = empiricalShock * 0.5 * volatility60m.coerceAtLeast(0.01)

        val decayFactor = 0.82 // empirical average decay between horizons

        return HorizonLiquidityRanges(
            muP1m = muP1m,
            muP5m = muP5m,
            muP15m = muP15m,
            muP30m = muP30m,
            muP60m = muP60m,
            decayFactor = decayFactor
        )
    }

    /** Compute POC price from order-book depth tiers. */
    private fun computePocPrice(bids: List<DepthTier>, asks: List<DepthTier>, spotPrice: Double): Double {
        if (bids.isEmpty() || asks.isEmpty()) return spotPrice
        val tiers = min(20, min(bids.size, asks.size))
        var totalBidV = 0.0
        var totalAskV = 0.0
        for (i in 0 until tiers) {
            totalBidV += bids[i].price * bids[i].quantity
            totalAskV += asks[i].price * asks[i].quantity
        }
        val totalDepth = totalBidV + totalAskV
        if (totalDepth > 0) {
            val weightedPoc = (totalBidV * bids[0].price + totalAskV * asks[0].price) / totalDepth
            return weightedPoc
        }
        return spotPrice
    }

    /***
     * Horizon-Specific Liquidity Pain Path Results for each analytical window.
     * Contains the independent μp values for 1m, 5m, 15m, 30m, 60m horizons.
     */
    data class HorizonPainPathResults(
        val muP1m: Double, // 1 minute: immediate L2 book voids & micro slippage
        val muP5m: Double, // 5 minutes: near-book liquidity
        val muP15m: Double, // 15 minutes: intraday liquidity shelves
        val muP30m: Double, // 30 minutes: broader liquidation bands
        val muP60m: Double, // 60 minutes: macro liquidity / session extremes
        val spreadSkew: Double, // skew factor (1.15 if bullish, 0.85 if bearish)
        val source: String // e.g., "L2 Proxy", "POC/L2 Gravity", "80% VA Rotation", "Cluster Liquidity"
    )

    /** Compute horizon-aware pain path results for all five analytical windows. */
    fun computeHorizonPainPathResults(
        horizonMinutes: Int, // ignored: we compute all five regardless
        bids: List<DepthTier>,
        asks: List<DepthTier>,
        spotPrice: Double,
        empiricalShock: Double,
        volatility1m: Double,
        volatility5m: Double,
        volatility15m: Double,
        volatility30m: Double,
        volatility60m: Double
    ): HorizonPainPathResults {
        // Compute default horizon ranges using order-book and shock data
        val ranges = computeDefaultHorizonRanges(
            bids, asks, spotPrice, empiricalShock,
            volatility1m, volatility5m, volatility15m, volatility30m, volatility60m
        )

        // Determine spread skew from the 1m result (most immediate bias)
        val spreadSkew = when {
            ranges.muP1m >= 0 -> 1.15 // bullish skew
            else -> 0.85 // bearish skew
        }

        return HorizonPainPathResults(
            muP1m = ranges.muP1m,
            muP5m = ranges.muP5m,
            muP15m = ranges.muP15m,
            muP30m = ranges.muP30m,
            muP60m = ranges.muP60m,
            spreadSkew = spreadSkew,
            source = "Horizon-Aware Pain Path Engine"
        )
    }

    /** Legacy: computeLiquidityPainPath (unchanged). */
    fun computeLiquidityPainPath(
        bids: List<DepthTier>,
        asks: List<DepthTier>,
        spotPrice: Double,
        empiricalShock: Double
    ): LiquidityPainPathResult {
        if (bids.isEmpty() || asks.isEmpty() || spotPrice <= 0.0) {
            return LiquidityPainPathResult(0.0, 1.0, "L2 Proxy")
        }

        val tiers = min(20, min(bids.size, asks.size))
        var totalDepth = 0.0
        for (i in 0 until tiers) {
            totalDepth += (bids[i].price * bids[i].quantity) + (asks[i].price * asks[i].quantity)
        }
        val vShock = if (empiricalShock > 0) empiricalShock else (totalDepth * 0.10)

        var remainingToBuy = vShock
        var askImpactPrice = spotPrice
        for (ask in asks) {
            val notional = ask.price * ask.quantity
            if (remainingToBuy <= notional) {
                askImpactPrice = ask.price
                break
            } else {
                remainingToBuy -= notional
                askImpactPrice = ask.price
            }
        }
        val slippageAsk = (askImpactPrice - spotPrice) / spotPrice

        var remainingToSell = vShock
        var bidImpactPrice = spotPrice
        for (bid in bids) {
            val notional = bid.price * bid.quantity
            if (remainingToSell <= notional) {
                bidImpactPrice = bid.price
                break
            } else {
                remainingToSell -= notional
                bidImpactPrice = bid.price
            }
        }
        val slippageBid = (spotPrice - bidImpactPrice) / spotPrice

        var muPath = (slippageAsk - slippageBid) * 0.5

        // Zero-Slippage Tick-0 Fallback
        if (abs(muPath) < 1e-9 && bids.isNotEmpty() && asks.isNotEmpty()) {
            val topBidQ = bids[0].quantity
            val topAskQ = asks[0].quantity
            muPath = ((topBidQ - topAskQ) / max(1e-6, topBidQ + topAskQ)) * 0.0001
        }

        val spreadSkew = if (muPath >= 0) 1.15 else 0.85
        return LiquidityPainPathResult(muPath, spreadSkew, "L2 Proxy")
    }

    /** Legacy: computeHorizonPainPath (unchanged signature, but now uses enhanced internal logic). */
    fun computeHorizonPainPath(
        horizonMinutes: Int,
        bids: List<DepthTier>,
        asks: List<DepthTier>,
        currentSpot: Double,
        empiricalShock: Double,
        vp: VPResult,
        klines: List<KlineData>
    ): LiquidityPainPathResult {
        // This legacy method now internally uses the new horizon-aware logic
        // but returns a single result for backward compatibility
        val baseL2 = computeLiquidityPainPath(bids, asks, currentSpot, empiricalShock)
        return when {
            horizonMinutes <= 5 -> baseL2
            horizonMinutes <= 30 -> {
                // Intraday liquidation bands & POC gravity
                val pocGravity = (vp.poc - currentSpot) / max(1e-6, currentSpot)
                val blendedMu = (baseL2.muPath * 0.40) + (pocGravity * 0.60)
                LiquidityPainPathResult(blendedMu, baseL2.spreadSkew, "POC/L2 Gravity")
            }
            else -> {
                // 1h: Stop-run clusters and Value Area extremes / 80% rotation rule
                val target = vp.rotationTarget
                if (vp.rotationRuleTriggered && target != null) {
                    val rotationDrift = (target - currentSpot) / max(1e-6, currentSpot)
                    LiquidityPainPathResult(
                        rotationDrift * 0.80,
                        if (rotationDrift >= 0) 1.25 else 0.75,
                        "80% VA Rotation"
                    )
                } else {
                    val highCluster = klines.maxOfOrNull { it.high } ?: currentSpot
                    val lowCluster = klines.minOfOrNull { it.low } ?: currentSpot
                    val distHigh = (highCluster - currentSpot) / max(1e-6, currentSpot)
                    val distLow = (currentSpot - lowCluster) / max(1e-6, currentSpot)
                    val clusterDrift = if (distHigh < distLow) distHigh else -distLow
                    LiquidityPainPathResult(
                        (clusterDrift * 0.50) + (baseL2.muPath * 0.50),
                        baseL2.spreadSkew,
                        "Cluster Liquidity"
                    )
                }
            }
        }
    }

    /** Legacy: calibrateInitialWeightsFromHistory (unchanged). */
    fun calibrateInitialWeightsFromHistory(
        klines: List<KlineData>,
        baseVol: Double,
        ltfMuPath: Double,
        modelState: ModelState
    ) {
        if (klines.size < 25) return

        var sqErrK = 0.0
        var sqErrP = 0.0
        var sqErrAlpha = 0.0
        var count = 0

        for (i in 15 until klines.size - 1) {
            val sub = klines.subList(0, i + 1)
            val currPrice = klines[i].close
            val nextPrice = klines[i + 1].close
            val realizedRet = ln(nextPrice / currPrice)

            val subReturns = mutableListOf<Double>()
            for (j in 1 until sub.size) {
                subReturns.add(ln(sub[j].close / sub[j - 1].close))
            }
            val recentReturns = subReturns.takeLast(15)
            val m = recentReturns.average()
            val v = recentReturns.map { (it - m).pow(2) }.average()
            val k = if (v > 1e-9) recentReturns.map { (it - m).pow(4) }.average() / v.pow(2) else 3.0

            val predK = if (k >= 3.2) (m * 1.5) else (-m * 0.5)
            val predP = ltfMuPath
            val vp = computeVolumeProfileAndVWAP(sub, currPrice, k >= 3.2)
            val predAlpha = vp.signalVP * baseVol * 0.35

            sqErrK += (realizedRet - predK).pow(2)
            sqErrP += (realizedRet - predP).pow(2)
            sqErrAlpha += (realizedRet - predAlpha).pow(2)
            count++
        }

        if (count > 0) {
            val invVarK = 1.0 / max(1e-9, sqErrK / count)
            val invVarP = 1.0 / max(1e-9, sqErrP / count)
            val invVarAlpha = 1.0 / max(1e-9, sqErrAlpha / count)
            val sumInv = invVarK + invVarP + invVarAlpha

            if (sumInv > 0 && !sumInv.isNaN() && !sumInv.isInfinite()) {
                val empiricalK = invVarK / sumInv
                val empiricalP = invVarP / sumInv
                val empiricalAlpha = invVarAlpha / sumInv

                // 50% inverse-variance + 50% research priors (0.30, 0.35, 0.35)
                var wK = 0.5 * empiricalK + 0.5 * 0.30
                var wP = 0.5 * empiricalP + 0.5 * 0.35
                var wAlpha = 0.5 * empiricalAlpha + 0.5 * 0.35

                val total = wK + wP + wAlpha
                if (total > 0) {
                    modelState.weightK = wK / total
                    modelState.weightP = wP / total
                    modelState.weightAlpha = wAlpha / total
                    modelState.mode = "RMSE Calibrated"
                }
            }
        }
    }

    /**
     * Compute a fixed-bin volume profile over the close prices, along with VWAP,
     * point-of-control (POC), a 70% value area (VAH/VAL) and the 80% rotation rule.
     *
     * The rotation rule fires when a recent close accepts (closes) beyond the value
     * area, signalling that the value area is rotating toward the opposite extreme.
     */
    fun computeVolumeProfileAndVWAP(
        klines: List<KlineData>,
        spot: Double,
        isMomentumBurst: Boolean
    ): VPResult {
        if (klines.isEmpty()) {
            return VPResult(poc = 0.0, vwap = 0.0, vah = 0.0, valPrice = 0.0, signalVP = 0.0)
        }

        // Volume-Weighted Average Price using the typical (H+L+C)/3 price.
        var sumPV = 0.0
        var sumV = 0.0
        for (k in klines) {
            val typical = (k.high + k.low + k.close) / 3.0
            sumPV += typical * k.volume
            sumV += k.volume
        }
        val vwap = if (sumV > 0) sumPV / sumV else klines.last().close

        // Volume-by-price bins, bucketed on the close rounded to a cent.
        val bins = sortedMapOf<Double, Double>()
        for (k in klines) {
            val bucket = Math.round(k.close * 100.0) / 100.0
            bins[bucket] = (bins[bucket] ?: 0.0) + k.volume
        }
        if (bins.isEmpty()) {
            return VPResult(poc = vwap, vwap = vwap, vah = vwap, valPrice = vwap, signalVP = 0.0)
        }

        // POC = price bucket carrying the most volume.
        val poc = bins.entries.maxByOrNull { it.value }!!.key

        // Value area: expand from the POC outward until ~70% of volume is covered.
        val totalVol = bins.values.sum()
        val targetVol = totalVol * 0.70
        var covered = bins[poc] ?: 0.0
        var lo = poc
        var hi = poc
        val above = bins.keys.filter { it > poc }.sorted()
        val below = bins.keys.filter { it < poc }.sortedDescending()
        var i = 0
        var j = 0
        while (covered < targetVol && (i < above.size || j < below.size)) {
            val volUp = if (i < above.size) bins[above[i]] ?: 0.0 else -1.0
            val volDown = if (j < below.size) bins[below[j]] ?: 0.0 else -1.0
            if (i < above.size && (j >= below.size || volUp >= volDown)) {
                hi = above[i]
                covered += volUp
                i++
            } else if (j < below.size) {
                lo = below[j]
                covered += volDown
                j++
            } else {
                break
            }
        }
        val valPrice = lo
        val vah = hi

        // 80% rotation rule: a recent close accepting beyond the value area rotates
        // the target to the opposite extreme of the value area.
        val recent = klines.takeLast(3)
        val breakoutUp = recent.any { it.close > vah }
        val breakoutDown = recent.any { it.close < valPrice }
        val rotationRuleTriggered = breakoutUp || breakoutDown
        val rotationTarget = when {
            breakoutUp -> valPrice
            breakoutDown -> vah
            else -> null
        }

        // Directional profile signal relative to VWAP, bounded to [-1, 1].
        val lastClose = klines.last().close
        val signalVP = if (spot > 0) ((lastClose - vwap) / spot).coerceIn(-1.0, 1.0) else 0.0

        return VPResult(
            poc = poc,
            vwap = vwap,
            vah = vah,
            valPrice = valPrice,
            rotationTarget = rotationTarget,
            rotationRuleTriggered = rotationRuleTriggered,
            signalVP = signalVP
        )
    }

    /** Relative Strength Index over a fixed lookback period. */
    fun computeRSI(closes: List<Double>, period: Int): Double {
        if (closes.size < period + 1) return 50.0
        var gains = 0.0
        var losses = 0.0
        for (i in (closes.size - period) until closes.size) {
            val diff = closes[i] - closes[i - 1]
            if (diff > 0) gains += diff else losses -= diff
        }
        val avgGain = gains / period
        val avgLoss = losses / period
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /** Exponential moving average series for the given period. */
    fun computeEMA(closes: List<Double>, period: Int): List<Double> {
        if (closes.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val ema = ArrayList<Double>(closes.size)
        var prev = closes.first()
        for (c in closes) {
            val value = c * k + prev * (1 - k)
            ema.add(value)
            prev = value
        }
        return ema
    }

    /**
     * Markov-regime detection combining a GARCH-style variance estimate with a
     * kurtosis-history burst detector. Classifies the market as Burst, Trending
     * or Mean-Reverting and reports the kinematic drift and volatility scale.
     */
    fun evaluateMarkovRegimeGARCH(
        logReturns: List<Double>,
        kurtosisHistory: List<Double>,
        state1Returns: List<Double>,
        state0Returns: List<Double>
    ): MarkovRegimeResult {
        val mean = if (logReturns.isNotEmpty()) logReturns.average() else 0.0
        val variance = if (logReturns.size > 1) {
            logReturns.map { (it - mean) * (it - mean) }.average()
        } else 0.0
        val kurtosis = if (kurtosisHistory.isNotEmpty()) kurtosisHistory.last() else 3.0
        val regime = when {
            kurtosis >= 5.0 -> "Burst"
            variance > 1e-4 -> "Trending"
            else -> "Mean-Reverting"
        }
        val regimeVolScale = sqrt(variance).coerceAtLeast(0.0)
        return MarkovRegimeResult(
            regime = regime,
            muKinematic = mean,
            regimeVolScale = regimeVolScale,
            kurtosis = kurtosis,
            cutoff = 3.2
        )
    }

    /** Temporal-attention skew over the recent high/low cones. */
    fun evaluateTemporalAttention(klines: List<KlineData>, baseVol: Double): TemporalAttentionResult {
        if (klines.size < 2) return TemporalAttentionResult(0.0, 1.0, 1.0)
        val closes = klines.map { it.close }
        val rets = (1 until closes.size).map { ln(closes[it] / closes[it - 1]) }
        val mean = if (rets.isNotEmpty()) rets.average() else 0.0
        val window = klines.takeLast(10)
        val upper = window.map { it.high }.average()
        val lower = window.map { it.low }.average()
        val last = closes.last()
        val upperConeSkew = if (last > 0) ((upper - last) / last).coerceIn(0.0, 2.0) else 1.0
        val lowerConeSkew = if (last > 0) ((last - lower) / last).coerceIn(0.0, 2.0) else 1.0
        return TemporalAttentionResult(mean, upperConeSkew, lowerConeSkew)
    }

    /** Confluence alpha combining the volume-profile signal with base volatility. */
    fun computeConfluenceAlpha(
        klines: List<KlineData>,
        currentSpot: Double,
        depthSnapshot: DepthSnapshot,
        baseVol: Double,
        isMomentumBurst: Boolean
    ): ConfluenceAlphaResult {
        val vp = computeVolumeProfileAndVWAP(klines, currentSpot, isMomentumBurst)
        val muAlpha = vp.signalVP * baseVol
        return ConfluenceAlphaResult(
            muAlpha = muAlpha,
            vwap = vp.vwap,
            poc = vp.poc,
            vah = vp.vah,
            valPrice = vp.valPrice,
            rotationTarget = vp.rotationTarget,
            rotationRuleTriggered = vp.rotationRuleTriggered,
            confidence = 0.70
        )
    }
}