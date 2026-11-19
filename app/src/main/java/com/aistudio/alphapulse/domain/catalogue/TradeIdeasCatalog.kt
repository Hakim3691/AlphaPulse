package com.aistudio.alphapulse.domain.catalogue

import java.util.Locale
import kotlin.math.abs

/**
 * The Phase 1 Trade Ideas Catalog (D7).
 *
 * AlphaPulse used to evaluate nine hardcoded quant models and a handful of ad-hoc
 * chart formations. The catalogue is the full taxonomy the directive specifies,
 * split into the four categories the existing tabs already filter by, and each
 * entry carries the empirical baseline it must not silently exceed.
 *
 * The catalogue is static, deterministic data: a *catalogue* is a published
 * taxonomy, not a learned artefact. What learns is the per-setup weight, and that
 * lives in [CatalogueWeights] where the SGD penalty/reward is auditable.
 *
 * Spec Ref: Phase 1 §3.1 Comprehensive Trade Ideas & Pattern / Quant Catalog
 */
enum class CatalogueCategory { PATTERN, INDICATOR, QUANT }

enum class Regime { TRENDING_UP, TRENDING_DOWN, MEAN_REVERTING, HIGH_VOLATILITY, UNKNOWN }

data class TradeIdea(
    val id: String,
    val name: String,
    val category: CatalogueCategory,
    /** Empirical win rate from the catalogue, e.g. 0.64 for +64%. */
    val winRate: Double,
    /** Empirical average return per trade, e.g. 0.012 for +1.2%. */
    val avgReturn: Double,
    /** Regimes in which the setup is allowed to fire. */
    val regimes: Set<Regime>,
    /** `true` when the setup trades with the prevailing trend. */
    val trendFollowing: Boolean = false
)

object TradeIdeasCatalog {

    private fun idea(
        id: String,
        name: String,
        category: CatalogueCategory,
        winRate: Double,
        avgReturn: Double,
        regimes: Set<Regime>,
        trendFollowing: Boolean = false
    ) = TradeIdea(id, name, category, winRate, avgReturn, regimes, trendFollowing)

    /**
     * Candlestick reversals & advances. Benchmarks are the catalogue's published
     * bands (+64% to +73% win rate, +1.2% to +4.3% average return).
     */
    val candlestick: List<TradeIdea> = listOf(
        idea("hanging-man", "Hanging Man", CatalogueCategory.PATTERN, 0.66, 0.021, setOf(Regime.TRENDING_DOWN, Regime.MEAN_REVERTING)),
        idea("hammer", "Hammer", CatalogueCategory.PATTERN, 0.68, 0.024, setOf(Regime.TRENDING_UP, Regime.MEAN_REVERTING)),
        idea("bullish-engulfing", "Bullish Engulfing", CatalogueCategory.PATTERN, 0.71, 0.031, setOf(Regime.TRENDING_UP, Regime.MEAN_REVERTING)),
        idea("bearish-engulfing", "Bearish Engulfing", CatalogueCategory.PATTERN, 0.70, 0.029, setOf(Regime.TRENDING_DOWN, Regime.MEAN_REVERTING)),
        idea("three-outside-up", "Three Outside Up", CatalogueCategory.PATTERN, 0.72, 0.036, setOf(Regime.TRENDING_UP)),
        idea("three-outside-down", "Three Outside Down", CatalogueCategory.PATTERN, 0.71, 0.034, setOf(Regime.TRENDING_DOWN)),
        idea("shooting-star", "Shooting Star", CatalogueCategory.PATTERN, 0.67, 0.025, setOf(Regime.TRENDING_DOWN, Regime.HIGH_VOLATILITY)),
        idea("morning-star", "Morning Star", CatalogueCategory.PATTERN, 0.73, 0.043, setOf(Regime.TRENDING_UP, Regime.MEAN_REVERTING)),
        idea("evening-star", "Evening Star", CatalogueCategory.PATTERN, 0.72, 0.040, setOf(Regime.TRENDING_DOWN, Regime.MEAN_REVERTING)),
        idea("dark-cloud-cover", "Dark Cloud Cover", CatalogueCategory.PATTERN, 0.65, 0.019, setOf(Regime.TRENDING_DOWN)),
        idea("piercing-line", "Piercing Line", CatalogueCategory.PATTERN, 0.64, 0.018, setOf(Regime.TRENDING_UP)),
        idea("three-white-soldiers", "Three White Soldiers", CatalogueCategory.PATTERN, 0.69, 0.027, setOf(Regime.TRENDING_UP)),
        idea("three-black-crows", "Three Black Crows", CatalogueCategory.PATTERN, 0.68, 0.026, setOf(Regime.TRENDING_DOWN))
    )

    /** Macro formations & consolidations. */
    val macro: List<TradeIdea> = listOf(
        idea("double-top", "Double Top", CatalogueCategory.PATTERN, 0.72, 0.038, setOf(Regime.TRENDING_DOWN, Regime.MEAN_REVERTING)),
        idea("double-bottom", "Double Bottom", CatalogueCategory.PATTERN, 0.73, 0.041, setOf(Regime.TRENDING_UP, Regime.MEAN_REVERTING)),
        idea("triple-top", "Triple Top", CatalogueCategory.PATTERN, 0.75, 0.045, setOf(Regime.TRENDING_DOWN)),
        idea("triple-bottom", "Triple Bottom", CatalogueCategory.PATTERN, 0.76, 0.047, setOf(Regime.TRENDING_UP)),
        idea("head-shoulders-top", "Head and Shoulders Top", CatalogueCategory.PATTERN, 0.74, 0.042, setOf(Regime.TRENDING_DOWN)),
        idea("head-shoulders-reverse", "Reverse Head and Shoulders", CatalogueCategory.PATTERN, 0.75, 0.044, setOf(Regime.TRENDING_UP)),
        idea("rising-wedge", "Rising Wedge", CatalogueCategory.PATTERN, 0.71, 0.033, setOf(Regime.TRENDING_DOWN, Regime.HIGH_VOLATILITY)),
        idea("falling-wedge", "Falling Wedge", CatalogueCategory.PATTERN, 0.72, 0.035, setOf(Regime.TRENDING_UP, Regime.HIGH_VOLATILITY)),
        idea("ascending-triangle", "Ascending Triangle", CatalogueCategory.PATTERN, 0.70, 0.030, setOf(Regime.TRENDING_UP)),
        idea("descending-triangle", "Descending Triangle", CatalogueCategory.PATTERN, 0.70, 0.030, setOf(Regime.TRENDING_DOWN)),
        idea("bull-flag", "Rising Flag", CatalogueCategory.PATTERN, 0.69, 0.028, setOf(Regime.TRENDING_UP)),
        idea("bear-flag", "Falling Flag", CatalogueCategory.PATTERN, 0.69, 0.028, setOf(Regime.TRENDING_DOWN)),
        idea("rising-three-methods", "Rising Three Methods", CatalogueCategory.PATTERN, 0.67, 0.022, setOf(Regime.TRENDING_UP)),
        idea("falling-three-methods", "Falling Three Methods", CatalogueCategory.PATTERN, 0.67, 0.022, setOf(Regime.TRENDING_DOWN))
    )

    /** Indicator combinations. */
    val indicators: List<TradeIdea> = listOf(
        idea("sma-5-20-cross", "SMA 5/20 Cross", CatalogueCategory.INDICATOR, 0.61, 0.014, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("ema-9-21-cross", "EMA 9/21 Cross", CatalogueCategory.INDICATOR, 0.63, 0.017, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("ma-5-10-20-alignment", "MA 5/10/20 Alignment", CatalogueCategory.INDICATOR, 0.66, 0.021, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("macd-zero-cross", "MACD Zero-Axis Cross", CatalogueCategory.INDICATOR, 0.60, 0.013, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("rsi-oversold-rebound", "RSI Oversold Rebounds", CatalogueCategory.INDICATOR, 0.64, 0.019, setOf(Regime.MEAN_REVERTING)),
        idea("rsi-overbought-rebound", "RSI Overbought Rebounds", CatalogueCategory.INDICATOR, 0.63, 0.018, setOf(Regime.MEAN_REVERTING)),
        idea("kdj-extreme-cross", "KDJ Extreme Cross", CatalogueCategory.INDICATOR, 0.59, 0.012, setOf(Regime.MEAN_REVERTING)),
        idea("bollinger-reversion", "Bollinger Mean-Reversion Bounce", CatalogueCategory.INDICATOR, 0.65, 0.020, setOf(Regime.MEAN_REVERTING, Regime.HIGH_VOLATILITY))
    )

    /** Quantitative rules. */
    val quant: List<TradeIdea> = listOf(
        idea("turtle-breakout", "Turtle Breakout (20-bar)", CatalogueCategory.QUANT, 0.68, 0.027, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("dual-thrust", "Dual Thrust", CatalogueCategory.QUANT, 0.62, 0.016, setOf(Regime.HIGH_VOLATILITY)),
        idea("orb", "Opening Range Breakout", CatalogueCategory.QUANT, 0.64, 0.019, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("super-trend", "SuperTrend ATR Channels", CatalogueCategory.QUANT, 0.67, 0.024, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("donchian", "Donchian Channel Breakout", CatalogueCategory.QUANT, 0.66, 0.023, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true),
        idea("connors-rsi", "Connors RSI", CatalogueCategory.QUANT, 0.63, 0.017, setOf(Regime.MEAN_REVERTING)),
        idea("vwap-reversion", "VWAP Reversion", CatalogueCategory.QUANT, 0.64, 0.018, setOf(Regime.MEAN_REVERTING)),
        idea("cmf", "Chaikin Money Flow", CatalogueCategory.QUANT, 0.61, 0.015, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN)),
        idea("triple-screen", "Triple Screen", CatalogueCategory.QUANT, 0.69, 0.026, setOf(Regime.TRENDING_UP, Regime.TRENDING_DOWN), trendFollowing = true)
    )

    val all: List<TradeIdea> = candlestick + macro + indicators + quant

    fun byId(id: String): TradeIdea? = all.firstOrNull { it.id == id }

    /**
     * The candidates Needle 3 may evaluate for an asset in [regime]: only setups the
     * catalogue permits there, ranked by empirical edge.
     */
    fun candidatesFor(regime: Regime): List<TradeIdea> =
        all.filter { regime in it.regimes }.sortedByDescending { it.winRate * it.avgReturn }

    fun forCategory(category: CatalogueCategory): List<TradeIdea> =
        all.filter { it.category == category }
}

/**
 * Per-setup learned weight, so the catalogue's published baseline is not confused
 * with AlphaPulse's own live record.
 *
 * A setup that produces an impulse inside [IMPULSE_CANDLES] is reinforced; one that
 * does not is penalised and decays. The weight is clamped so a losing streak can
 * never retire a setup permanently.
 */
class CatalogueWeights(
    private val weights: MutableMap<String, Double> = mutableMapOf(),
    private val learningRate: Double = 0.05
) {
    fun weightOf(id: String): Double = weights[id] ?: 1.0

    /** One online step. Returns the new weight for the audit trail. */
    fun record(id: String, producedImpulseWithinCandles: Boolean): Double {
        val prior = weightOf(id)
        val next = if (producedImpulseWithinCandles) {
            prior + learningRate * prior
        } else {
            prior - learningRate * prior
        }.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        weights[id] = next
        return next
    }

    fun snapshot(): Map<String, Double> = weights.toMap()

    companion object {
        const val IMPULSE_CANDLES: Int = 3
        const val MIN_WEIGHT: Double = 0.10
        const val MAX_WEIGHT: Double = 1.50
    }
}

/** Renders a catalogue baseline for the UI: `+64% WR · +1.2% AR`. */
fun TradeIdea.baselineLabel(): String = String.format(
    Locale.US,
    "+%.0f%% WR \u00b7 +%.1f%% AR",
    winRate * 100.0,
    avgReturn * 100.0
)

/** Absolute distance from price to a zone centre, in ATR units (Phase 1 §3). */
fun atrDistance(priceLast: Double, zoneCenter: Double, atr: Double): Double =
    if (atr <= 0.0) Double.NaN else abs(priceLast - zoneCenter) / atr
