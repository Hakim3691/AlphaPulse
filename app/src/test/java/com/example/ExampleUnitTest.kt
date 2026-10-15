package com.example

import com.example.domain.quant.KlineData
import com.example.domain.quant.QuantMath
import com.example.ui.util.Formatters
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun test5DecimalPriceFormatting() {
        // High-priced asset (e.g. Bitcoin)
        assertEquals("$64520.12345", Formatters.formatPrice5Dec(64520.12345))
        assertEquals("64520.12345", Formatters.formatRawPrice5Dec(64520.12345))

        // Medium-priced asset (e.g. Solana)
        assertEquals("$145.20000", Formatters.formatPrice5Dec(145.2))
        assertEquals("145.20000", Formatters.formatRawPrice5Dec(145.2))

        // Low-priced / micro-cap asset
        assertEquals("$0.07731", Formatters.formatPrice5Dec(0.07731))
        assertEquals("0.07731", Formatters.formatRawPrice5Dec(0.07731))

        // Edge case: zero / negative / NaN
        assertEquals("$0.00000", Formatters.formatPrice5Dec(0.0))
        assertEquals("0.00000", Formatters.formatRawPrice5Dec(-5.0))
        assertEquals("$0.00000", Formatters.formatPrice5Dec(Double.NaN))
    }

    @Test
    fun testVolumeProfileAndValueArea() {
        val dummyKlines = mutableListOf<KlineData>()
        val baseTime = 1700000000000L
        for (i in 0 until 30) {
            val p = 100.0 + (i % 5) * 2.0
            dummyKlines.add(
                KlineData(
                    openTime = baseTime + i * 60000L,
                    open = p,
                    high = p + 1.0,
                    low = p - 1.0,
                    close = p + 0.5,
                    volume = 1000.0,
                    quoteVolume = 100000.0,
                    takerBuyQuoteVolume = 55000.0
                )
            )
        }

        val vp = QuantMath.computeVolumeProfileAndVWAP(dummyKlines, 102.0, false)
        assertTrue("POC should be positive", vp.poc > 0.0)
        assertTrue("VAH should be >= VAL", vp.vah >= vp.valPrice)
        assertTrue("VWAP should be positive", vp.vwap > 0.0)
    }

    @Test
    fun test80PercentValueAreaRotationRule() {
        val dummyKlines = mutableListOf<KlineData>()
        val baseTime = 1700000000000L

        // Generate baseline klines around 100
        for (i in 0 until 20) {
            dummyKlines.add(
                KlineData(
                    openTime = baseTime + i * 60000L,
                    open = 98.0,
                    high = 102.0,
                    low = 98.0,
                    close = 100.0,
                    volume = 1000.0,
                    quoteVolume = 100000.0,
                    takerBuyQuoteVolume = 50000.0
                )
            )
        }

        val initialVp = QuantMath.computeVolumeProfileAndVWAP(dummyKlines, 100.0, false)
        val vah = initialVp.vah
        val valPrice = initialVp.valPrice

        // Add 3 candles: opened above VAH, then accepted inside VA for 2 consecutive periods
        val t = baseTime + 20 * 60000L
        dummyKlines.add(
            KlineData(t, vah + 2.0, vah + 3.0, vah + 1.0, vah + 2.0, 500.0, 50000.0, 25000.0)
        )
        dummyKlines.add(
            KlineData(t + 60000L, vah - 0.2, vah + 0.5, valPrice + 0.2, (vah + valPrice) / 2.0, 500.0, 50000.0, 25000.0)
        )
        dummyKlines.add(
            KlineData(t + 120000L, (vah + valPrice) / 2.0, vah, valPrice, (vah + valPrice) / 2.0, 500.0, 50000.0, 25000.0)
        )

        val rotationVp = QuantMath.computeVolumeProfileAndVWAP(dummyKlines, (vah + valPrice) / 2.0, false)
        assertTrue("Rotation rule should trigger after 2 consecutive acceptances inside VA from above", rotationVp.rotationRuleTriggered)
        assertNotNull(rotationVp.rotationTarget)
        assertEquals(rotationVp.valPrice, rotationVp.rotationTarget!!, 0.001)
    }

    @Test
    fun testPhaseLockedCandleBoundaryNTP() {
        // Given an arbitrary arbitrary timestamp with milliseconds and seconds (e.g. 10:14:37.452)
        val timestamp = 1718000000000L + 37452L
        val horizonMinutes = 5

        // Phase-locked round minute boundary: (:00.000s)
        val targetTimestamp = ((timestamp / 60000L) + horizonMinutes) * 60000L

        // Verify that targetTimestamp is exactly aligned to 00.000 seconds (remainder modulo 60000 is 0)
        assertEquals(0L, targetTimestamp % 60000L)
        // Verify it is strictly in the future
        assertTrue(targetTimestamp > timestamp)
        // Verify the offset is between 4 and 5 minutes (remaining time until the 5m target boundary)
        val diff = targetTimestamp - timestamp
        assertTrue(diff in (4 * 60000L)..(5 * 60000L))
    }

    @Test
    fun testDynamicFundingRateAnnualizedRiskFreeRate() {
        val lastFundingRate8h = 0.0001 // 0.01% per 8h
        val annualizedRf = lastFundingRate8h * 3.0 * 365.0
        assertEquals(0.1095, annualizedRf, 0.00001)

        val neutralFundingRate = 0.0
        val neutralRf = neutralFundingRate * 3.0 * 365.0
        assertEquals(0.0, neutralRf, 0.00001)
    }
}

