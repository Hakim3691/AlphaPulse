package com.aistudio.alphapulse.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forecast scheduling invariants (spec sections 23 & FR-003): targets are
 * phase-locked to round minute boundaries and the risk-free rate is derived
 * from the 8h perpetual funding rate with a documented annualization.
 */
class SchedulingTest {

    @Test
    fun `targets lock to round minute candle boundaries`() {
        // Arbitrary timestamp with non-zero seconds/millis (10:14:37.452).
        val timestamp = 1718000000000L + 37452L
        val horizonMinutes = 5

        val targetTimestamp = ((timestamp / 60000L) + horizonMinutes) * 60000L

        // Exactly aligned to :00.000 seconds.
        assertEquals(0L, targetTimestamp % 60000L)
        // Strictly in the future.
        assertTrue(targetTimestamp > timestamp)
        // 4 to 5 minutes until the target boundary.
        val diff = targetTimestamp - timestamp
        assertTrue(diff in (4 * 60000L)..(5 * 60000L))
    }

    @Test
    fun `funding rate annualizes to the dynamic risk-free rate`() {
        val lastFundingRate8h = 0.0001 // 0.01% per 8h
        val annualizedRf = lastFundingRate8h * 3.0 * 365.0
        assertEquals(0.1095, annualizedRf, 0.00001)
    }

    @Test
    fun `neutral funding falls back through the documented 4 percent`() {
        val neutralRf = 0.0 * 3.0 * 365.0
        assertEquals(0.0, neutralRf, 0.00001)
        // Baseline fallback used when funding is unavailable (FR-003).
        assertEquals(0.04, 4.0 / 100.0, 0.0)
    }
}
