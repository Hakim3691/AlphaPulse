package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.ForecastTarget

/**
 * AlphaPulse's per-horizon forecast, reduced to the two numbers a campaign board
 * needs: where the tactical (shortest) horizon points and where the strategic
 * (longest) horizon points.
 *
 * Crypto Trader Pro originally derived its own multi-horizon bias from 1h/4h
 * candles ([IndicatorMath.structuralBias]). AlphaPulse already forecasts the same
 * move per horizon, so re-deriving it from candles is redundant work that can
 * disagree with the forecast the rest of the app displays. This type is the seam
 * between the two: the campaign engine consumes AlphaPulse's prediction instead of
 * recomputing one.
 *
 * Bias is expressed on the same -100..+100 scale as [com.aistudio.alphapulse.data.model.CryptoAsset.tacticalBias]
 * so it drops into the existing §15 bias bands unchanged.
 */
data class ForecastBias(
    /** Shortest-horizon predicted move, in percent. */
    val tacticalPct: Double,
    /** Longest-horizon predicted move, in percent. */
    val strategicPct: Double,
    /** The full forecast ladder, shortest horizon first. */
    val targets: List<ForecastTarget>
) {
    /** Blended directional bias, -100 (strong bearish) .. +100 (strong bullish). */
    val score: Int
        get() {
            // The tactical horizon leads: a board is staged for the move that can
            // actually reach it first. The strategic horizon confirms or vetoes.
            val blended = (tacticalPct * 0.65) + (strategicPct * 0.35)
            return (blended * PERCENT_TO_BIAS).coerceIn(-100.0, 100.0).toInt()
        }

    /** The strategic (longest-horizon) direction on the same -100..+100 scale. */
    val strategicScore: Int
        get() = (strategicPct * PERCENT_TO_BIAS).coerceIn(-100.0, 100.0).toInt()

    val isBullish: Boolean get() = score >= 0

    /**
     * How much this forecast agrees with one side of the book, in priority points.
     * Favoured side gains, opposed side loses; flat forecasts move nothing.
     */
    fun agreement(isLong: Boolean): Double {
        val directional = if (isLong) score else -score
        return (directional / 100.0) * MAX_AGREEMENT_POINTS
    }

    /**
     * The forecast price ladder for one side, nearest horizon first. A long board
     * takes the upside predictions, a short board the downside ones, so a target is
     * never priced against the direction the forecast disagrees with.
     */
    fun targetsFor(isLong: Boolean): List<ForecastTarget> =
        targets
            .filter { if (isLong) it.predictedPrice > 0.0 && it.deltaPct > 0.0 else it.predictedPrice > 0.0 && it.deltaPct < 0.0 }
            .sortedBy { it.minutes }

    companion object {
        /** A 12.5% predicted move saturates the bias scale. */
        private const val PERCENT_TO_BIAS = 8.0
        private const val MAX_AGREEMENT_POINTS = 12.0

        /**
         * Builds the bias ladder from AlphaPulse's forecast targets. Returns null when
         * there is no usable prediction, so the engine keeps its candle-based bias.
         */
        fun from(targets: List<ForecastTarget>): ForecastBias? {
            val usable = targets
                .filter { it.predictedPrice.isFinite() && it.predictedPrice > 0.0 && it.deltaPct.isFinite() }
                .sortedBy { it.minutes }
            if (usable.isEmpty()) return null
            return ForecastBias(
                tacticalPct = usable.first().deltaPct,
                strategicPct = usable.last().deltaPct,
                targets = usable
            )
        }
    }
}
