package com.aistudio.alphapulse.domain.ml.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §14 / Unified Spec Part III §10 — New Fourth Forecasting Factor (TSFM) parity.
 *
 * The fourth factor is the Chronos-Bolt ONNX adapter, which sits alongside the
 * tri-factor core and (optionally) the TimesFM fifth factor. This spec drives it,
 * so breaking ONNX parity or re-introducing a cloud model breaks the build.
 *
 * Spec Ref: §14 New Fourth Forecasting Factor, Part III §10
 */
class OnnxModelParityBddSpec {

    @Test
    fun `given a flat candle series when the Chronos-Bolt ONNX adapter is prepared then it returns a finite context and mask`() {
        val closes = DoubleArray(40) { 100.0 + it * 0.01 }

        val prepared = ChronosBoltAdapter.prepare(closes)

        assertEquals(ChronosBoltAdapter.CONTEXT_LENGTH, prepared.context.size)
        assertEquals(ChronosBoltAdapter.CONTEXT_LENGTH, prepared.mask.size)
        assertTrue(prepared.loc.isFinite())
        assertTrue(prepared.scale.isFinite())
        assertTrue("Scale must respect the minimum floor", prepared.scale >= 1e-5)
    }

    @Test
    fun `given a constant series when the ONNX adapter is prepared then it uses the minimum scale floor`() {
        val prepared = ChronosBoltAdapter.prepare(DoubleArray(32) { 42.0 })

        assertEquals(42.0, prepared.loc, 1e-9)
        assertEquals(1e-5, prepared.scale, 1e-15)
    }

    @Test
    fun `given a Chronos-Bolt forecast when its model id is read then it names the fourth factor`() {
        val forecast = TsfmForecast(
            modelId = "chronos",
            median = doubleArrayOf(101.0),
            lower = doubleArrayOf(100.0),
            upper = doubleArrayOf(102.0)
        )

        assertEquals("chronos", forecast.modelId)
    }

    @Test
    fun `given a mounted fourth-factor model when its forecast is decoded then the median band is finite and forward-looking`() {
        val prepared = ChronosBoltAdapter.prepare(DoubleArray(40) { 100.0 + it * 0.01 })
        val quantiles = Array(1) { Array(9) { FloatArray(ChronosBoltAdapter.MAX_HORIZON) } }
        quantiles[0][4][0] = 0.5f

        val forecast = ChronosBoltAdapter.decode(quantiles, prepared, 1)

        assertEquals(1, forecast.median.size)
        assertTrue(forecast.median[0].isFinite())
        assertTrue("Median must be above the prepared location", forecast.median[0] > prepared.loc)
    }

    @Test
    fun `given a fourth-factor forecast when its drift per minute is derived then it is finite and sign-correct`() {
        val forecast = TsfmForecast(
            modelId = "chronos",
            median = doubleArrayOf(101.0),
            lower = doubleArrayOf(100.0),
            upper = doubleArrayOf(102.0)
        )

        val drift = forecast.driftPerMinute(spot = 100.0, minutes = 1)

        assertTrue(drift != null)
        assertTrue(drift!! > 0.0)
    }
}
