package com.example.ui.util

import java.util.Locale
import kotlin.math.abs

object Formatters {

    /**
     * Formats coin prices to display up to 5 decimal places with a dollar sign.
     * Guaranteed precision: "$12.34567", "$0.07731", "$64520.12345".
     */
    fun formatPrice5Dec(value: Double): String {
        if (value.isNaN() || value <= 0.0) return "$0.00000"
        return String.format(Locale.US, "$%.5f", value)
    }

    /**
     * Formats raw coin prices to display up to 5 decimal places without a dollar sign.
     */
    fun formatRawPrice5Dec(value: Double): String {
        if (value.isNaN() || value <= 0.0) return "0.00000"
        return String.format(Locale.US, "%.5f", value)
    }

    /**
     * Backward-compatible price formatter ensuring all prices adhere to the strict
     * up-to-5 decimal places institutional rule.
     */
    fun formatPrice(value: Double): String = formatPrice5Dec(value)

    /**
     * Formats large currency notional values (e.g. OFI, Volume).
     */
    fun formatLargeCurrency(value: Double): String {
        val absVal = abs(value)
        return when {
            absVal >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", absVal / 1_000_000_000.0)
            absVal >= 1_000_000 -> String.format(Locale.US, "%.2fM", absVal / 1_000_000.0)
            absVal >= 1_000 -> String.format(Locale.US, "%.1fk", absVal / 1_000.0)
            else -> String.format(Locale.US, "%.2f", absVal)
        }
    }

    /**
     * Formats percentages with explicit sign.
     */
    fun formatPercent(value: Double): String {
        val sign = if (value >= 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.2f", value)}%"
    }
}
