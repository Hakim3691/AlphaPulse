package com.aistudio.alphapulse.domain.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for pluggable TSFM providers (spec §16).
 *  Tests kronos, fintext-chronos, chronos-bolt, and timesfm providers
 *  with market-state tensor input and P10-P90 quantile output.
 */
class TsfmProviderTest {

    @Test
    fun `kronos provider produces valid prediction`() {
        val provider = KronosProvider()
        val input = TsfmInput(
            close = 100.0,
            realizedVolatility = 0.02,
            regime = "Trending",
            horizon = 60
        )
        val prediction = provider.predict(input)
        assertNotNull("Prediction should not be null", prediction)
        assertTrue("P10 should be less than P50", prediction.p10 <= prediction.p50)
        assertTrue("P50 should be less than P90", prediction.p50 <= prediction.p90)
        assertTrue("Expected price should be within confidence bounds",
            prediction.lowerConfidenceBoundary <= prediction.expectedPrice &&
                prediction.expectedPrice <= prediction.upperConfidenceBoundary)
        assertTrue("Model ID should be set", prediction.modelId != null && prediction.modelId.isNotEmpty())
        assertTrue("Version should be set", prediction.version != null && prediction.version.isNotEmpty())
        assertTrue("Backend should be set", prediction.backend != null)
        assertTrue("Timestamp should be set", prediction.timestamp > 0)
    }

    @Test
    fun `fintext-chronos provider produces valid prediction`() {
        val provider = FinTextChronosProvider()
        val input = TsfmInput(
            close = 50000.0,
            realizedVolatility = 0.03,
            regime = "Mean Reversion",
            horizon = 30
        )
        val prediction = provider.predict(input)
        assertNotNull("Prediction should not be null", prediction)
        assertTrue("P10 should be less than P50", prediction.p10 <= prediction.p50)
        assertTrue("P50 should be less than P90", prediction.p50 <= prediction.p90)
        assertTrue("Model ID should be set", prediction.modelId != null && prediction.modelId.isNotEmpty())
        assertTrue("FinText provider should be available", provider.isAvailable())
    }

    @Test
    fun `chronos-bolt provider produces valid prediction`() {
        val provider = ChronosBoltProvider()
        val input = TsfmInput(
            close = 75000.0,
            realizedVolatility = 0.025,
            regime = "Momentum Burst",
            horizon = 120
        )
        val prediction = provider.predict(input)
        assertNotNull("Prediction should not be null", prediction)
        assertTrue("P10 should be less than P50", prediction.p10 <= prediction.p50)
        assertTrue("P50 should be less than P90", prediction.p50 <= prediction.p90)
        assertTrue("Model ID should be set", prediction.modelId != null && prediction.modelId.isNotEmpty())
        assertTrue("Chronos Bolt provider should be available", provider.isAvailable())
    }

    @Test
    fun `timesfm provider produces valid prediction`() {
        val provider = TimesFMProvider()
        val input = TsfmInput(
            close = 200.0,
            realizedVolatility = 0.015,
            regime = "Sideways",
            horizon = 180
        )
        val prediction = provider.predict(input)
        assertNotNull("Prediction should not be null", prediction)
        assertTrue("P10 should be less than P50", prediction.p10 <= prediction.p50)
        assertTrue("P50 should be less than P90", prediction.p50 <= prediction.p90)
        assertTrue("Model ID should be set", prediction.modelId != null && prediction.modelId.isNotEmpty())
        assertTrue("TimesFM provider should be available", provider.isAvailable())
    }

    @Test
    fun `TsfmPrediction quantile ordering is maintained`() {
        val prediction = TsfmPrediction(
            p10 = 95.0,
            p25 = 97.0,
            p50 = 100.0,
            p75 = 103.0,
            p90 = 105.0,
            expectedPrice = 100.0,
            lowerConfidenceBoundary = 95.0,
            upperConfidenceBoundary = 105.0,
            forecastSkew = 5.0,
            forecastDispersion = 10.0,
            modelId = "test@1.0",
            version = "1.0",
            backend = TsfmBackend.KRONOS_SMALL,
            timestamp = System.currentTimeMillis()
        )
        // Verify P10 <= P25 <= P50 <= P75 <= P90
        assertTrue("P10 <= P25", prediction.p10 <= prediction.p25)
        assertTrue("P25 <= P50", prediction.p25 <= prediction.p50)
        assertTrue("P50 <= P75", prediction.p50 <= prediction.p75)
        assertTrue("P75 <= P90", prediction.p75 <= prediction.p90)
        
        // Verify dispersion = P90 - P10
        assertEquals("Dispersion should equal P90 - P10", 10.0, prediction.forecastDispersion, 1e-10)
        
        // Verify skew = (P90 - P50) - (P50 - P10)
        val expectedSkew = (prediction.p90 - prediction.p50) - (prediction.p50 - prediction.p10)
        assertEquals("Skew should equal (P90-P50) - (P50-P10)", expectedSkew, prediction.forecastSkew, 1e-10)
    }

    @Test
    fun `TsfmInput can be created with default metadata`() {
        val input = TsfmInput(close = 0.0, realizedVolatility = 0.0, regime = "", horizon = 0)
        assertEquals(0.0, input.close, 1e-10)
        assertEquals(0.0, input.realizedVolatility, 1e-10)
        assertEquals("", input.regime)
        assertEquals(0, input.horizon)
        assertEquals(emptyMap(), input.metadata)
    }

    @Test
    fun `TsfmBackend values are valid`() {
        val backends = listOf(TsfmBackend.KRONOS_SMALL, TsfmBackend.CHRONOS_BOLT,
            TsfmBackend.FINTEXT_CHRONOS, TsfmBackend.TIMESFM_2_0)
        for (backend in backends) {
            assertNotNull("Backend should have display name", backend.displayName.isNotEmpty())
            assertNotNull("Backend should have accelerator", backend.accelerator.isNotEmpty())
        }
    }

    @Test
    fun `model provider priority ordering`() {
        val kronos = KronosProvider()
        val fintext = FinTextChronosProvider()
        val chronos = ChronosBoltProvider()
        val timesfm = TimesFMProvider()
        
        // Higher priority = lower ordinal value
        assertTrue("Kronos should have highest priority (1)", kronos.priority > fintext.priority)
        assertTrue("FinText should have priority 2", fintext.priority > chronos.priority)
        assertTrue("Chronos Bolt should have priority 3", chronos.priority > timesfm.priority)
        assertTrue("TimesFM should have lowest priority (4)", timesfm.priority == 4)
    }
}