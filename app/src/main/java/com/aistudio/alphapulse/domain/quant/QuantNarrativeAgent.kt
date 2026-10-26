package com.aistudio.alphapulse.domain.quant

/**
 * Writes the plain-English explanation shown next to the numbers.
 *
 * It only *describes* figures the quant engine already produced - it never
 * invents a number or changes a forecast.
 *
 * Spec Ref: §41 Institutional Explanation Panel, §59 AI Narrator Requirements
 */

import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.core.Formatters
import java.util.Locale

object QuantNarrativeAgent {

    fun generateExecutiveBrief(
        symbol: String,
        spotPrice: Double,
        modelState: ModelState,
        regime: String,
        kurtosis: Double,
        kurtosisCutoff: Double,
        netImbalanceUsdt: Double,
        bidRatioPct: Double,
        vwap: Double,
        poc: Double,
        vah: Double = 0.0,
        valPrice: Double = 0.0,
        rotationRuleTriggered: Boolean = false,
        rotationTarget: Double? = null,
        rsi: Double,
        macdSignal: String,
        coneHitRatePct: Double?
    ): String {
        val cleanSymbol = symbol.replace("USDT", "")

        // Identify dominant factor
        val maxW = maxOf(modelState.weightK, modelState.weightP, modelState.weightAlpha)
        val dominantFactor = when (maxW) {
            modelState.weightK -> "Kinematic Momentum Drift (W_k = ${String.format(Locale.US, "%.3f", modelState.weightK)})"
            modelState.weightP -> "Order-Book Liquidity Pain Path (W_p = ${String.format(Locale.US, "%.3f", modelState.weightP)})"
            else -> "Alpha Confluence & 50-Bin FRVP (W_α = ${String.format(Locale.US, "%.3f", modelState.weightAlpha)})"
        }

        val regimeSummary = if (regime.contains("Burst", ignoreCase = true)) {
            "High-volatility kurtosis burst (K=${String.format(Locale.US, "%.2f", kurtosis)} vs 85p cutoff ${String.format(Locale.US, "%.2f", kurtosisCutoff)}). Directional momentum expansion active."
        } else {
            "Consolidated mean-reverting regime (K=${String.format(Locale.US, "%.2f", kurtosis)} < cutoff ${String.format(Locale.US, "%.2f", kurtosisCutoff)}). POC gravitational anchor dominates."
        }

        val obBias = if (netImbalanceUsdt >= 0) {
            "Net bid-side depth absorption (${String.format(Locale.US, "%.1f", bidRatioPct)}% Bid). Resting liquidity offers buffer against downside liquidations."
        } else {
            "Ask-side skew (${String.format(Locale.US, "%.1f", 100 - bidRatioPct)}% Ask). Upward liquidity voids may trigger rapid slippage traversal."
        }

        val vwapPocDist = if (spotPrice >= vwap) {
            "Spot trading ${( (spotPrice - vwap) / vwap * 100 ).let { String.format(Locale.US, "+%.2f%%", it) }} above session VWAP (${Formatters.formatPrice5Dec(vwap)})."
        } else {
            "Spot discount ${( (spotPrice - vwap) / vwap * 100 ).let { String.format(Locale.US, "%.2f%%", it) }} below session VWAP (${Formatters.formatPrice5Dec(vwap)})."
        }

        val vaSummary = if (rotationRuleTriggered && rotationTarget != null) {
            "80% Auction Market Value Area Rotation ACTIVE: Target opposite boundary ${Formatters.formatPrice5Dec(rotationTarget)} (VAH: ${Formatters.formatPrice5Dec(vah)}, VAL: ${Formatters.formatPrice5Dec(valPrice)})."
        } else if (vah > 0.0 && valPrice > 0.0) {
            val vaPos = when {
                spotPrice > vah -> "Trading above VAH (${Formatters.formatPrice5Dec(vah)}) - Bullish auction extension"
                spotPrice < valPrice -> "Trading below VAL (${Formatters.formatPrice5Dec(valPrice)}) - Bearish auction discount"
                else -> "Acceptance inside 70% Value Area [${Formatters.formatPrice5Dec(valPrice)} - ${Formatters.formatPrice5Dec(vah)}]"
            }
            "Value Area Positioning: $vaPos. POC anchored at ${Formatters.formatPrice5Dec(poc)}."
        } else {
            "POC anchored at ${Formatters.formatPrice5Dec(poc)}."
        }

        val hitRateStr = if (coneHitRatePct != null) {
            "Ensemble 1σ cone tracking precision: ${String.format(Locale.US, "%.1f%%", coneHitRatePct)}."
        } else {
            "Ensemble self-calibrating via live online SGD."
        }

        return "[$cleanSymbol QUANT COGNITIVE BRIEF]\n" +
                "• Dominant Engine: $dominantFactor.\n" +
                "• Regime Diagnosis: $regimeSummary\n" +
                "• Microstructure L2: $obBias\n" +
                "• Auction Dynamics: $vwapPocDist\n" +
                "• Auction Structure: $vaSummary RSI(14) at ${String.format(Locale.US, "%.1f", rsi)} ($macdSignal).\n" +
                "• Validation Telemetry: $hitRateStr"
    }
}
