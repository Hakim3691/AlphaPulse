package com.aistudio.alphapulse.domain.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of model selection by measurement, specified as Given/When/Then.
 *
 * §48 asks which model deserves the device. The tests that matter are the refusals:
 * a fast model with three calls must not win, and a model that fails often must not
 * be recommended however quick it is.
 *
 * Spec Ref: §48 Model Selection Benchmark
 */
class ModelBenchmarkBddSpec {

    /** A model with [count] identical successful calls. */
    private fun steady(modelId: String, latencyMs: Double, count: Int = 12) =
        (0 until count).map { BenchmarkSample(modelId, latencyMs) }

    // ------------------------------------------------------------- ranking

    @Test
    fun `given two measured models when ranked then the faster p95 comes first`() {
        val results = ModelBenchmark.evaluate(
            steady("chronos", latencyMs = 90.0) + steady("timesfm", latencyMs = 400.0)
        )

        assertEquals(listOf("chronos", "timesfm"), results.map { it.modelId })
        assertTrue("Both have enough samples", results.all { it.eligible })
    }

    @Test
    fun `given enough samples when the best model is requested then the fastest eligible one is returned`() {
        val best = ModelBenchmark.selectBest(
            steady("chronos", latencyMs = 90.0) + steady("timesfm", latencyMs = 400.0)
        )

        assertEquals("chronos", best?.modelId)
    }

    // ------------------------------------------------------------ refusals

    @Test
    fun `given a model with sample few calls when evaluated then it is not eligible however fast`() {
        val results = ModelBenchmark.evaluate(steady("chronos", latencyMs = 5.0, count = 3))

        val result = results.single()
        assertFalse("Three calls are noise, not evidence", result.eligible)
        assertTrue(
            "The status must say how many samples are missing: ${result.status}",
            result.status.contains("3 of ${ModelBenchmark.MIN_SAMPLES}")
        )
    }

    @Test
    fun `given exactly the minimum number of samples when evaluated then the model is eligible`() {
        val result = ModelBenchmark.evaluate(steady("chronos", latencyMs = 100.0, count = ModelBenchmark.MIN_SAMPLES)).single()

        assertTrue("The threshold is inclusive", result.eligible)
    }

    @Test
    fun `given a fast model that fails often when evaluated then it is refused on reliability`() {
        val samples = (0 until 12).map { index ->
            BenchmarkSample("flaky", latencyMs = 20.0, succeeded = index % 2 == 0)
        }

        val result = ModelBenchmark.evaluate(samples).single()

        assertFalse("A 50% failure rate must never be recommended", result.eligible)
        assertTrue(result.status.contains("failed"))
        assertEquals(0.5, result.errorRate, 1e-9)
    }

    @Test
    fun `given a model with only failures when evaluated then it scores zero instead of dividing by zero`() {
        val samples = (0 until 12).map { BenchmarkSample("broken", latencyMs = 0.0, succeeded = false) }

        val result = ModelBenchmark.evaluate(samples).single()

        assertEquals(0.0, result.meanLatencyMs, 1e-9)
        assertEquals(0.0, result.p95LatencyMs, 1e-9)
        assertEquals(0.0, result.score, 1e-9)
        assertFalse(result.eligible)
        assertTrue(result.status.contains("every observed call failed"))
    }

    @Test
    fun `given no eligible model when the best is requested then null is returned rather than a bad pick`() {
        val best = ModelBenchmark.selectBest(steady("chronos", latencyMs = 5.0, count = 2))

        assertNull("With no evidence the honest answer is 'none'", best)
    }

    // ----------------------------------------------------------- the numbers

    @Test
    fun `given latencies with a slow tail when evaluated then the p95 reports the tail not the average`() {
        // Nine fast calls and three slow ones: the average hides the tail.
        val samples = steady("chronos", latencyMs = 50.0, count = 9) +
            (0 until 3).map { BenchmarkSample("chronos", latencyMs = 900.0) }

        val result = ModelBenchmark.evaluate(samples).single()

        // (9 x 50 + 3 x 900) / 12 = 262.5 - the average is dragged up, and it still
        // hides the fact that three calls took 18x longer than the rest.
        assertEquals(262.5, result.meanLatencyMs, 1e-6)
        assertEquals("The 95th percentile is the worst observed here", 900.0, result.p95LatencyMs, 1e-9)
        assertTrue("Score reflects the tail: ${result.score}", result.score < 0.6)
    }

    @Test
    fun `given a model that processes more work per call when evaluated then throughput is higher`() {
        val samples = (0 until 12).map {
            BenchmarkSample("batch", latencyMs = 100.0, unitsProcessed = 10)
        }

        val result = ModelBenchmark.evaluate(samples).single()

        assertEquals("10 units per 100 ms is 100 units per second", 100.0, result.throughputPerSecond, 1e-9)
    }

    @Test
    fun `given calls from several models when evaluated then each model is measured separately`() {
        val results = ModelBenchmark.evaluate(
            steady("chronos", latencyMs = 60.0) +
                steady("timesfm", latencyMs = 200.0) +
                steady("needle3", latencyMs = 30.0)
        )

        assertEquals(3, results.size)
        assertEquals(setOf("chronos", "timesfm", "needle3"), results.map { it.modelId }.toSet())
        assertEquals("The quickest model leads the ranking", "needle3", results.first().modelId)
    }
}
