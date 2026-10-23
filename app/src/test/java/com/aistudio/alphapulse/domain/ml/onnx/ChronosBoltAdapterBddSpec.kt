package com.aistudio.alphapulse.domain.ml.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Behaviour of the Chronos-Bolt tensor adapter, specified as Given/When/Then.
 * The graph contract is: normalize with loc/scale over observed points, mask
 * observed points, then restore prices from the normalized quantiles.
 */
class ChronosBoltAdapterBddSpec {

    @Test
    fun `given a full history when prepared then observed points are masked and normalized`() {
        // Given ten consecutive closes
        val closes = DoubleArray(10) { 100.0 + it }
        val mean = closes.average()
        val scale = sqrt(closes.sumOf { (it - mean) * (it - mean) } / closes.size)

        // When the context tensor is prepared
        val prepared = ChronosBoltAdapter.prepare(closes)

        // Then the window is left-padded, masked on the observed tail and scaled
        assertEquals(ChronosBoltAdapter.CONTEXT_LENGTH, prepared.context.size)
        assertEquals(mean, prepared.loc, 1e-9)
        assertEquals(scale, prepared.scale, 1e-9)
        assertEquals(0f, prepared.context[0])
        assertEquals(0f, prepared.mask[0])
        assertEquals(10f, prepared.mask.sum())
        assertEquals((closes.last() - mean) / scale, prepared.context.last().toDouble(), 1e-6)
    }

    @Test
    fun `given missing observations when prepared then they stay zero and unmasked`() {
        // Given a series with a hole
        val closes = doubleArrayOf(100.0, Double.NaN, 104.0)

        // When prepared
        val prepared = ChronosBoltAdapter.prepare(closes)

        // Then only the two real observations count
        assertEquals(2f, prepared.mask.sum())
        assertEquals(0f, prepared.context[ChronosBoltAdapter.CONTEXT_LENGTH - 2])
        assertEquals(102.0, prepared.loc, 1e-9)
    }

    @Test
    fun `given a constant series when prepared then scale uses the minimum floor`() {
        val prepared = ChronosBoltAdapter.prepare(DoubleArray(32) { 42.0 })

        assertEquals(42.0, prepared.loc, 1e-9)
        assertEquals(1e-5, prepared.scale, 1e-15)
    }

    @Test
    fun `given normalized quantiles when decoded then prices use the same loc and scale`() {
        // Given a prepared window and q10/q50/q90 tensors for 64 steps
        val closes = DoubleArray(50) { 100.0 + it * 0.5 }
        val prepared = ChronosBoltAdapter.prepare(closes)
        val quantiles = Array(1) { Array(9) { FloatArray(ChronosBoltAdapter.MAX_HORIZON) } }
        quantiles[0][0][0] = -1f
        quantiles[0][4][0] = 0.5f
        quantiles[0][8][0] = 2f

        // When decoded for five minutes
        val forecast = ChronosBoltAdapter.decode(quantiles, prepared, 5)

        // Then the bands come back in price space
        assertEquals(5, forecast.median.size)
        assertEquals(0.5 * prepared.scale + prepared.loc, forecast.median[0], 1e-9)
        assertEquals(-1.0 * prepared.scale + prepared.loc, forecast.lower[0], 1e-9)
        assertEquals(2.0 * prepared.scale + prepared.loc, forecast.upper[0], 1e-9)
        assertTrue(forecast.median.all { it.isFinite() })
    }

    @Test
    fun `given a short horizon request when decoded then it is clamped to the graph horizon`() {
        val prepared = ChronosBoltAdapter.prepare(DoubleArray(16) { 100.0 })
        val quantiles = Array(1) { Array(9) { FloatArray(ChronosBoltAdapter.MAX_HORIZON) } }

        val forecast = ChronosBoltAdapter.decode(quantiles, prepared, 500)

        assertEquals(ChronosBoltAdapter.MAX_HORIZON, forecast.median.size)
    }

    @Test
    fun `given a forecast when drift is derived then it is the log return per minute`() {
        val forecast = TsfmForecast(
            modelId = "chronos",
            median = doubleArrayOf(110.0, 105.0),
            lower = doubleArrayOf(100.0, 100.0),
            upper = doubleArrayOf(120.0, 110.0)
        )

        val drift = forecast.driftPerMinute(100.0, 2)

        assertNotNull(drift)
        assertEquals(ln(1.05) / 2, drift!!, 1e-12)
        assertTrue(abs(drift) < 0.05)
    }

    @Test
    fun `given an unusable spot price when drift is derived then no signal is returned`() {
        val forecast = TsfmForecast(
            modelId = "chronos",
            median = doubleArrayOf(110.0),
            lower = doubleArrayOf(100.0),
            upper = doubleArrayOf(120.0)
        )

        assertEquals(null, forecast.driftPerMinute(0.0, 1))
        assertEquals(null, forecast.driftPerMinute(100.0, 0))
    }
}
