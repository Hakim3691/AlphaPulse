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

object QuantMath {

    fun computeEMA(data: List<Double>, period: Int): List<Double> {
        if (data.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1.0)
        val ema = ArrayList<Double>(data.size)
        ema.add(data[0])
        for (i in 1 until data.size) {
            ema.add(data[i] * k + ema[i - 1] * (1.0 - k))
        }
        return ema
    }

    fun computeRSI(closes: List<Double>, period: Int = 14): Double {
        if (closes.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        val start = closes.size - period
        for (i in start until closes.size) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gains += diff else losses -= diff
        }
        val avgGain = gains / period
        val avgLoss = losses / period
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    data class VPResult(
        val vwap: Double,
        val poc: Double,
        val vah: Double,
        val valPrice: Double,
        val signalVP: Double,
        val rotationTarget: Double? = null,
        val rotationRuleTriggered: Boolean = false
    )

    fun computeVolumeProfileAndVWAP(
        klines: List<KlineData>,
        currentSpot: Double,
        isMomentumBurst: Boolean
    ): VPResult {
        if (klines.size < 15) {
            return VPResult(
                vwap = currentSpot,
                poc = currentSpot,
                vah = currentSpot * 1.01,
                valPrice = currentSpot * 0.99,
                signalVP = 0.0
            )
        }

        var cumulativeVolumePrice = 0.0
        var cumulativeVolume = 0.0
        var minPrice = Double.POSITIVE_INFINITY
        var maxPrice = Double.NEGATIVE_INFINITY

        for (k in klines) {
            val typical = (k.high + k.low + k.close) / 3.0
            cumulativeVolumePrice += typical * k.volume
            cumulativeVolume += k.volume

            if (k.low < minPrice) minPrice = k.low
            if (k.high > maxPrice) maxPrice = k.high
        }

        val vwap = if (cumulativeVolume > 0) cumulativeVolumePrice / cumulativeVolume else currentSpot

        // Freedman-Diaconis bin width for Volume Profile
        var binsCount = 50
        val spanTotal = max(0.0001, maxPrice - minPrice)
        var binSize = spanTotal / binsCount
        if (klines.size >= 20) {
            val prices = klines.map { (it.high + it.low + it.close) / 3.0 }.sorted()
            val q1 = prices[prices.size / 4]
            val q3 = prices[(3 * prices.size) / 4]
            val iqr = max(0.0001, q3 - q1)
            val fdBinWidth = 2.0 * iqr / (prices.size.toDouble().pow(1.0 / 3.0))
            if (fdBinWidth > 0) {
                binSize = max(spanTotal / 200.0, min(spanTotal / 10.0, fdBinWidth))
                binsCount = max(10, min(100, (spanTotal / binSize).toInt()))
                binSize = spanTotal / binsCount
            }
        }
        val profileBins = DoubleArray(binsCount)

        for (k in klines) {
            val startBin = ((k.low - minPrice) / binSize).toInt().coerceIn(0, binsCount - 1)
            val endBin = ((k.high - minPrice) / binSize).toInt().coerceIn(0, binsCount - 1)
            val span = max(1, endBin - startBin + 1)
            val alloc = k.volume / span
            for (b in startBin..endBin) {
                profileBins[b] += alloc
            }
        }

        var maxVol = 0.0
        var maxVolBin = 0
        for (b in 0 until binsCount) {
            if (profileBins[b] > maxVol) {
                maxVol = profileBins[b]
                maxVolBin = b
            }
        }
        val poc = minPrice + (maxVolBin + 0.5) * binSize

        // 70% Value Area Calculation (VAH & VAL)
        val totalProfileVolume = profileBins.sum()
        val targetVAVolume = totalProfileVolume * 0.70
        var vaVolume = profileBins[maxVolBin]
        var lowerBin = maxVolBin
        var upperBin = maxVolBin

        while (vaVolume < targetVAVolume && (lowerBin > 0 || upperBin < binsCount - 1)) {
            val nextLowerVol = if (lowerBin > 0) profileBins[lowerBin - 1] else 0.0
            val nextUpperVol = if (upperBin < binsCount - 1) profileBins[upperBin + 1] else 0.0

            if (nextUpperVol >= nextLowerVol && upperBin < binsCount - 1) {
                upperBin++
                vaVolume += nextUpperVol
            } else if (lowerBin > 0) {
                lowerBin--
                vaVolume += nextLowerVol
            } else if (upperBin < binsCount - 1) {
                upperBin++
                vaVolume += nextUpperVol
            }
        }

        val vah = minPrice + (upperBin + 1.0) * binSize
        val valPrice = minPrice + (lowerBin) * binSize

        // 80% Auction Market Value Area Rotation Rule:
        // If price opens outside the Value Area and accepts back inside for 2 consecutive periods,
        // probability of rotation to the opposite boundary is 80%.
        var rotationTarget: Double? = null
        var rotationRuleTriggered = false
        if (klines.size >= 4) {
            val prev2 = klines[klines.size - 3].close
            val prev1 = klines[klines.size - 2].close
            val curr = currentSpot

            if (prev2 > vah && prev1 >= valPrice && prev1 <= vah && curr >= valPrice && curr <= vah) {
                rotationTarget = valPrice
                rotationRuleTriggered = true
            } else if (prev2 < valPrice && prev1 >= valPrice && prev1 <= vah && curr >= valPrice && curr <= vah) {
                rotationTarget = vah
                rotationRuleTriggered = true
            }
        }

        val pocDistPct = (poc - currentSpot) / currentSpot
        val vwapDistPct = (currentSpot - vwap) / currentSpot

        val pocWeight = if (isMomentumBurst) 0.20 else 0.90
        val vwapWeight = if (isMomentumBurst) 0.80 else 0.10

        var signalVP = (pocDistPct * pocWeight) + (vwapDistPct * vwapWeight)
        if (rotationRuleTriggered && rotationTarget != null) {
            val rotDirection = if (rotationTarget > currentSpot) 1.0 else -1.0
            signalVP = (signalVP * 0.3) + (rotDirection * 0.7)
        }
        signalVP = (signalVP * 10.0).coerceIn(-1.0, 1.0)

        return VPResult(
            vwap = vwap,
            poc = poc,
            vah = vah,
            valPrice = valPrice,
            signalVP = signalVP,
            rotationTarget = rotationTarget,
            rotationRuleTriggered = rotationRuleTriggered
        )
    }

    fun computeUQLOBUncertainty(
        bids: List<DepthTier>,
        asks: List<DepthTier>,
        baseVol: Double
    ): Double {
        if (bids.isEmpty() || asks.isEmpty()) return 0.5

        val bestBid = bids[0].price
        val bestAsk = asks[0].price
        val mid = max(1e-6, (bestBid + bestAsk) / 2.0)
        val spread = (bestAsk - bestBid) / mid

        val tiers = min(20, min(bids.size, asks.size))
        var totalBidV = 0.0
        var totalAskV = 0.0
        for (i in 0 until tiers) {
            totalBidV += bids[i].quantity
            totalAskV += asks[i].quantity
        }
        val imbalance = abs(totalBidV - totalAskV) / max(1e-6, totalBidV + totalAskV)

        val spreadPenalty = min(1.0, spread / max(0.001, baseVol))
        val confidence = ((1.0 - spreadPenalty * 0.5) * (0.6 + 0.4 * imbalance)).coerceIn(0.10, 1.0)
        return confidence
    }

    fun computeConfluenceAlpha(
        klines: List<KlineData>,
        currentSpot: Double,
        depth: DepthSnapshot?,
        baseVol: Double,
        isMomentumBurst: Boolean
    ): ConfluenceAlphaResult {
        val vp = computeVolumeProfileAndVWAP(klines, currentSpot, isMomentumBurst)
        val confidence = if (depth != null) computeUQLOBUncertainty(depth.bids, depth.asks, baseVol) else 0.70
        val regimeScalar = if (isMomentumBurst) 1.0 else -0.5
        val muAlpha = (vp.signalVP * confidence * abs(regimeScalar)) * baseVol * 0.5

        return ConfluenceAlphaResult(
            muAlpha = muAlpha,
            vwap = vp.vwap,
            poc = vp.poc,
            vah = vp.vah,
            valPrice = vp.valPrice,
            rotationTarget = vp.rotationTarget,
            rotationRuleTriggered = vp.rotationRuleTriggered,
            confidence = confidence
        )
    }

    fun evaluateMarkovRegimeGARCH(
        returns: List<Double>,
        kurtosisHistory: MutableList<Double>,
        state1Returns: MutableList<Double>,
        state0Returns: MutableList<Double>
    ): MarkovRegimeResult {
        if (returns.size < 15) {
            return MarkovRegimeResult(
                regime = "State 0: Mean-Reverting",
                muKinematic = 0.0,
                regimeVolScale = 1.0,
                kurtosis = 3.0,
                cutoff = 3.2
            )
        }

        val recent = returns.takeLast(20)
        val mean = recent.average()
        val variance = recent.map { (it - mean).pow(2) }.average()
        val s4 = recent.map { (it - mean).pow(4) }.average()
        val kurtosis = if (variance > 1e-9) s4 / variance.pow(2) else 3.0

        if (!kurtosis.isNaN() && !kurtosis.isInfinite()) {
            kurtosisHistory.add(kurtosis)
            if (kurtosisHistory.size > 100) kurtosisHistory.removeAt(0)
        }

        var dynamicCutoff = 3.2
        if (kurtosisHistory.size >= 5) {
            val sorted = kurtosisHistory.sorted()
            val idx85 = (sorted.size * 0.85).toInt().coerceIn(0, sorted.size - 1)
            dynamicCutoff = sorted[idx85].coerceIn(2.8, 4.5)
        }

        return if (kurtosis >= dynamicCutoff) {
            state1Returns.add(mean)
            if (state1Returns.size > 50) state1Returns.removeAt(0)
            val burstDrift = if (state1Returns.size >= 3) state1Returns.average() else (mean * 1.5)
            MarkovRegimeResult(
                regime = "State 1: Momentum Burst",
                muKinematic = burstDrift,
                regimeVolScale = 1.35,
                kurtosis = kurtosis,
                cutoff = dynamicCutoff
            )
        } else {
            state0Returns.add(mean)
            if (state0Returns.size > 50) state0Returns.removeAt(0)
            val revertDrift = if (state0Returns.size >= 3) -state0Returns.average() * 0.5 else (-mean * 0.5)
            MarkovRegimeResult(
                regime = "State 0: Mean-Reverting",
                muKinematic = revertDrift,
                regimeVolScale = 0.85,
                kurtosis = kurtosis,
                cutoff = dynamicCutoff
            )
        }
    }

    fun evaluateTemporalAttention(
        klines: List<KlineData>,
        baseVol: Double
    ): TemporalAttentionResult {
        if (klines.size < 30) {
            return TemporalAttentionResult(0.0, 1.0, 1.0)
        }

        val lastClose = klines.last().close
        val close5m = klines[max(0, klines.size - 5)].close
        val close15m = klines[max(0, klines.size - 15)].close
        val close30m = klines[max(0, klines.size - 30)].close

        val r1 = ln(lastClose / close5m)
        val r2 = ln(close5m / close15m)
        val r3 = ln(close15m / close30m)

        val rawTemp = 1.0 / (max(0.0001, baseVol) * sqrt(30.0))
        val adaptiveTemp = (rawTemp * 0.15).coerceIn(5.0, 50.0)

        val q = listOf(r1, r2, r3)
        val expWeights = q.map { exp(it * adaptiveTemp) }
        val sumExp = expWeights.sum().coerceAtLeast(1e-6)
        val softmax = expWeights.map { it / sumExp }

        val muKinematic = (r1 * softmax[0] + r2 * softmax[1] + r3 * softmax[2]) / 3.0
        val upside = max(0.0, r1) + max(0.0, r2)
        val downside = max(0.0, -r1) + max(0.0, -r2)

        return TemporalAttentionResult(
            muKinematic = muKinematic,
            upperConeSkew = 1.0 + (upside * 2.0),
            lowerConeSkew = 1.0 + (downside * 2.0)
        )
    }

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

    /**
     * Discrete Horizon Liquidity Pain Paths:
     * - 1m/5m: mapped to immediate L2 book voids and micro-spread slippage.
     * - 15m/30m: mapped to intraday liquidation bands and POC gravity.
     * - 1h: mapped to session high/low stop-run clusters and Value Area extremes (VAH/VAL 80% rotation).
     */
    fun computeHorizonPainPath(
        horizonMinutes: Int,
        bids: List<DepthTier>,
        asks: List<DepthTier>,
        currentSpot: Double,
        empiricalShock: Double,
        vp: VPResult,
        klines: List<KlineData>
    ): LiquidityPainPathResult {
        val baseL2 = computeLiquidityPainPath(bids, asks, currentSpot, empiricalShock)
        return when {
            horizonMinutes <= 5 -> {
                // Immediate L2 book voids & micro slippage
                baseL2
            }
            horizonMinutes <= 30 -> {
                // Intraday liquidation bands & POC gravity
                val pocGravity = (vp.poc - currentSpot) / max(1e-6, currentSpot)
                val blendedMu = (baseL2.muPath * 0.40) + (pocGravity * 0.60)
                LiquidityPainPathResult(blendedMu, baseL2.spreadSkew, "POC/L2 Gravity")
            }
            else -> {
                // 1h: Stop-run clusters and Value Area extremes / 80% rotation rule
                if (vp.rotationRuleTriggered && vp.rotationTarget != null) {
                    val rotationDrift = (vp.rotationTarget - currentSpot) / max(1e-6, currentSpot)
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
}
