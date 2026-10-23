package com.aistudio.alphapulse.domain.ml.onnx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Behaviour of the TimesFM tensor adapter, specified as Given/When/Then.
 * TimesFM marks padding with 1 (the inverse of Chronos), normalizes the whole
 * padded window and exposes the mean head in channel 0.
 */
class TimesFmAdapterBddSpec {

    @Test
    fun `given a short history when prepared then it is right aligned and padding is masked`() {
        // Given 100 closes for a 512-point graph
        val closes = DoubleArray(100) { 100.0 + it }
        val padding = TimesFmAdapter.CONTEXT_LENGTH - closes.size

        // When prepared
        val prepared = TimesFmAdapter.prepare(closes)

        // Then the tail is right-aligned and the prefix is marked as padding
        assertEquals(TimesFmAdapter.CONTEXT_LENGTH, prepared.inputs.size)
        assertEquals(padding, prepared.masks.take(padding).count { it == 1f })
        assertEquals(0, prepared.masks.drop(padding).count { it != 0f })
        assertEquals(closes.last(), 0.0 + prepared.mean + prepared.inputs.last() * prepared.std, 1e-4)
    }

    @Test
    fun `given a padded window when normalized then statistics cover the padded length`() {
        val closes = DoubleArray(64) { 10.0 }
        val padded = DoubleArray(TimesFmAdapter.CONTEXT_LENGTH)
        closes.copyInto(padded, TimesFmAdapter.CONTEXT_LENGTH - closes.size)

        val prepared = TimesFmAdapter.prepare(closes)

        val expectedMean = padded.average()
        val expectedStd = sqrt(padded.sumOf { (it - expectedMean) * (it - expectedMean) } / (padded.size - 1))
        assertEquals(expectedMean, prepared.mean, 1e-9)
        assertEquals(expectedStd, prepared.std, 1e-9)
    }

    @Test
    fun `given interior gaps when interpolated then values follow the surrounding trend`() {
        val values = doubleArrayOf(10.0, Double.NaN, Double.NaN, 40.0)

        val filled = TimesFmAdapter.interpolate(values)

        assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 40.0), filled, 1e-9)
    }

    @Test
    fun `given leading and trailing gaps when interpolated then they are stripped or carried`() {
        val values = doubleArrayOf(Double.NaN, 5.0, 6.0, Double.NaN)

        val filled = TimesFmAdapter.interpolate(values)

        assertArrayEquals(doubleArrayOf(5.0, 6.0, 6.0), filled, 1e-9)
    }

    @Test
    fun `given a fully missing window when interpolated then nothing is returned`() {
        assertEquals(0, TimesFmAdapter.interpolate(doubleArrayOf(Double.NaN, Double.NaN)).size)
    }

    @Test
    fun `given a point tensor when decoded then channel 0 is the median and 1 and 9 are the bands`() {
        // Given a prepared window and a normalized output tensor
        val prepared = TimesFmAdapter.prepare(DoubleArray(40) { 50.0 + it })
        val point = Array(1) { Array(TimesFmAdapter.MAX_HORIZON) { FloatArray(10) } }
        point[0][0][0] = 0.25f
        point[0][0][1] = -0.75f
        point[0][0][9] = 1.5f

        // When decoded
        val forecast = TimesFmAdapter.decode(point, prepared, 3)

        // Then prices are restored with the same mean and std
        assertEquals(3, forecast.median.size)
        assertEquals(0.25 * prepared.std + prepared.mean, forecast.median[0], 1e-9)
        assertEquals(-0.75 * prepared.std + prepared.mean, forecast.lower[0], 1e-9)
        assertEquals(1.5 * prepared.std + prepared.mean, forecast.upper[0], 1e-9)
        assertTrue(forecast.median.all { it.isFinite() })
    }

    @Test
    fun `given a horizon beyond the graph when decoded then it is clamped`() {
        val prepared = TimesFmAdapter.prepare(DoubleArray(10) { 1.0 })
        val point = Array(1) { Array(TimesFmAdapter.MAX_HORIZON) { FloatArray(10) } }

        assertEquals(TimesFmAdapter.MAX_HORIZON, TimesFmAdapter.decode(point, prepared, 500).median.size)
    }
}
