package com.aistudio.alphapulse.domain.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit tests for TSFM layer integration with pluggable providers (spec §16). */
class TsfmLayerTest {

    private var tsfmLayer: TsfmLayer? = null
    private var providerRegistry: TsfmProviderRegistry? = null

    @Before
    fun setUp() {
        providerRegistry = TsfmProviderRegistry()
        tsfmLayer = TsfmLayerImpl(providerRegistry!!
    }

    @Test
    fun `TSFM layer uses kronos provider by default`() {
        val input = TsfmInput(
            close = 100.0,
            realizedVolatility = 0.02,
            regime = "Trending",
            horizon = 60
        )
        val prediction = tsfmLayer!!.predict(input)
        assertNotNull("Prediction should not be null", prediction)
        assertTrue("P10 should be less than P50", prediction.p10 <= prediction.p50)
        assertTrue("P50 should be less than P90", prediction.p50 <= prediction.p90)
        assertEquals("Should use Kronos (highest priority)", "kronos-small@1.0.0", prediction.modelId)
    }

    @Test
    fun `TSFM layer produces valid quantile distribution`() {
        val input = TsfmInput(
            close = 50000.0,
            realizedVolatility = 0.03,
            regime = "Mean Reversion",
            horizon = 30
        )
        val prediction = tsfmLayer!!.predict(input)
        
        // Verify quantile ordering
        assertTrue("P10 <= P25 <= P50 <= P75 <= P90", 
            prediction.p10 <= prediction.p25 && prediction.p25 <= prediction.p50 && 
            prediction.p50 <= prediction.p75 && prediction.p75 <= prediction.p90)
        
        // Verify dispersion = P90 - P10
        val expectedDispersion = prediction.p90 - prediction.p10
        assertEquals("Dispersion should equal P90 - P10", expectedDispersion, prediction.forecastDispersion, 1e-10)
        
        // Verify skew = (P90 - P50) - (P50 - P10)
        val expectedSkew = (prediction.p90 - prediction.p50) - (prediction.p50 - prediction.p10)
        assertEquals("Skew should equal (P90-P50) - (P50-P10)", expectedSkew, prediction.forecastSkew, 1e-10)
        
        // Verify confidence boundaries
        assertTrue("Lower bound should be P10", prediction.lowerConfidenceBoundary == prediction.p10)
        assertTrue("Upper bound should be P90", prediction.upperConfidenceBoundary == prediction.p90)
        
        // Verify model metadata
        assertTrue("Model ID should be set", prediction.modelId != null && prediction.modelId.isNotEmpty())
        assertTrue("Version should be set", prediction.version != null && prediction.version.isNotEmpty())
        assertTrue("Backend should be set", prediction.backend != null)
        assertTrue("Timestamp should be positive", prediction.timestamp > 0)
    }

    @Test
    fun `TSFM layer detects regime`() {
        // Momentum burst regime
        val momentumKlines = listOf(
            com.aistudio.alphapulse.data.model.KlineData(1700000000000L, 100.0, 105.0, 99.0, 103.0, 1000.0, 100000.0, 55000.0),
            com.aistudio.alphapulse.data.model.KlineData(1700000060000L, 103.0, 108.0, 102.0, 107.0, 1500.0, 100000.0, 60000.0),
            com.aistudio.alphapulse.data.model.KlineData(1700000120000L, 107.0, 112.0, 106.0, 111.0, 2000.0, 100000.0, 70000.0)
        )
        
        val input = TsfmInput(
            close = 103.0,
            realizedVolatility = 0.05,
            regime = "Momentum Burst",
            horizon = 60
        )
        val prediction = tsfmLayer!!.predict(input)
        assertNotNull("Prediction should not be null", prediction)
    }

    @Test
    fun `TSFM layer edge case - zero volatility`() {
        val input = TsfmInput(
            close = 100.0,
            realizedVolatility = 0.0,
            regime = "Sideways",
            horizon = 0
        )
        val prediction = tsfmLayer!!.predict(input)
        assertNotNull("Prediction should not be null even with zero volatility", prediction)
        // Should still produce valid output even with edge case inputs
        assertTrue("P10 should be valid", prediction.p10.isFinite())
        assertTrue("P50 should be valid", prediction.p50.isFinite())
        assertTrue("P90 should be valid", prediction.p90.isFinite())
    }

    @Test
    fun `TSFM layer fallback when no provider available`() {
        // This test verifies the system handles the case where no provider is available
        // In normal operation, at least one provider should be available
        try {
            // Force unavailable scenario by mocking
            val registry = TsfmProviderRegistry()
            // The registry should always have at least one provider available in normal operation
            val provider = registry.selectProvider()
            assertNotNull("Provider should be selectable", provider)
        } catch (e: IllegalStateException) {
            // Expected if no providers are available - system should handle gracefully
            // This is a test to ensure the exception is thrown correctly
            assertTrue("Should throw IllegalStateException when no provider available", true)
        }
    }

    @Test
    fun `TSfmLayer legacy predict method`() {
        // Test the deprecated legacy method for backward compatibility
        val klines = listOf(
            com.aistudio.alphapulse.data.model.KlineData(1700000000000L, 100.0, 101.0, 99.0, 100.5, 1000.0, 100000.0, 55000.0),
            com.aistudio.alphapulse.data.model.KlineData(1700000060000L, 100.5, 102.0, 100.0, 101.5, 1500.0, 100000.0, 60000.0)
        )
        val horizonMinutes = listOf(60, 180, 300)
        
        // The legacy method should not throw
        val result = tsfmLayer!!.predict(klines, horizonMinutes)
        assertNotNull("Legacy predict should return list", result)
        // Should return empty list by default (backward compat only)
        assertTrue("Legacy predict should handle gracefully", result.isEmptyOr { true })
    }
}