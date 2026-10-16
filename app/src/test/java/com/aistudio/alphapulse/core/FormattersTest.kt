package com.aistudio.alphapulse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict up-to-5-decimal price precision standard (user-confirmed requirement)
 * plus the institutional display formatters.
 */
class FormattersTest {

    @Test
    fun `5 decimal formatting covers high mid and microcap prices`() {
        // High-priced asset (Bitcoin)
        assertEquals("$64520.12345", Formatters.formatPrice5Dec(64520.12345))
        assertEquals("64520.12345", Formatters.formatRawPrice5Dec(64520.12345))
        // Medium-priced asset (Solana)
        assertEquals("$145.20000", Formatters.formatPrice5Dec(145.2))
        // Micro-cap asset
        assertEquals("$0.07731", Formatters.formatPrice5Dec(0.07731))
        assertEquals("0.07731", Formatters.formatRawPrice5Dec(0.07731))
    }

    @Test
    fun `negative zero and NaN collapse to neutral output`() {
        assertEquals("$0.00000", Formatters.formatPrice5Dec(0.0))
        assertEquals("$0.00000", Formatters.formatPrice5Dec(Double.NaN))
        assertEquals("0.00000", Formatters.formatRawPrice5Dec(-5.0))
        assertEquals("$0.00000", Formatters.formatPrice5Dec(-5.0))
    }

    @Test
    fun `formatPrice delegates to the 5-decimal standard`() {
        assertEquals(Formatters.formatPrice5Dec(22.24000), Formatters.formatPrice(22.24))
    }

    @Test
    fun `large notional currency is abbreviated`() {
        assertEquals("2.30B", Formatters.formatLargeCurrency(2_300_000_000.0))
        assertEquals("1.50M", Formatters.formatLargeCurrency(1_500_000.0))
        assertEquals("5.5k", Formatters.formatLargeCurrency(5_500.0))
        assertEquals("25.00", Formatters.formatLargeCurrency(25.0))
    }

    @Test
    fun `percent formatter carries explicit sign`() {
        assertEquals("+1.25%", Formatters.formatPercent(1.25))
        assertEquals("+0.00%", Formatters.formatPercent(0.0))
        assertEquals("-3.00%", Formatters.formatPercent(-3.0))
        assertTrue(Formatters.formatPercent(-0.0).startsWith("+"))
    }
}
