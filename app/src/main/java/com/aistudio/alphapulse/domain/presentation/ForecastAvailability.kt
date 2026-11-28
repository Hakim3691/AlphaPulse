package com.aistudio.alphapulse.domain.presentation

import java.util.Locale
import kotlin.math.abs

/**
 * Whether the forecast surface may quote a price for the inspected coin.
 *
 * Why this exists
 * ---------------
 * A cycle with no usable spot price (the OFFLINE SEED state, a failed ticker and an empty
 * kline series) still ran the ensemble math with `currentSpot = 0.0`. The horizon cards
 * then rendered `$0.00000` and `▲NaN%`, and the five forward chart points all carried a
 * zero, so the trajectory chart fell back to its `1.0..2.0` axis and drew every line off
 * screen. A forecast is only meaningful against a positive, finite price; otherwise the
 * surface must say it is withheld rather than print a synthetic zero.
 *
 * Spec Ref: §18 Probabilistic Forecasting, C-65
 */
data class ForecastAvailability(
    val available: Boolean,
    val reason: String? = null
)

object ForecastAvailabilityPolicy {

    /** The sentence the ensemble and trajectory panels show instead of a zero forecast. */
    const val NO_LIVE_PRICE_REASON =
        "No live price for this coin — forecasts and cones are withheld until the venue feed returns."

    fun assess(spotPrice: Double): ForecastAvailability =
        if (spotPrice.isFinite() && spotPrice > 0.0) {
            ForecastAvailability(available = true)
        } else {
            ForecastAvailability(available = false, reason = NO_LIVE_PRICE_REASON)
        }

    /**
     * A delta pill can only show a number. A non-finite percentage (the old `0/0`) is a
     * withheld reading, never `NaN%`.
     */
    fun deltaLabel(deltaPct: Double, isBullish: Boolean): String {
        if (!deltaPct.isFinite()) return "—"
        val arrow = if (isBullish) "\u25B2" else "\u25BC"
        return "$arrow${String.format(Locale.US, "%.2f", abs(deltaPct))}%"
    }
}
