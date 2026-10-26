package com.aistudio.alphapulse.domain.ml

/**
 * ModelBenchmark - chooses which forecasting model earns a place on the device.
 *
 * §48 asks for model selection to be measured rather than assumed. The mounted
 * models (Kronos, Chronos-Bolt, TimesFM) do the same job at very different speeds,
 * so the question "which one should this phone run?" needs an answer built from
 * observed calls: how fast is it really, how often does it fail, and does it stay
 * fast when it is slow (the p95, not the average).
 *
 * One rule matters above all: **a model with too few samples is never selected.**
 * A single lucky fast call would otherwise win, which is how benchmarks turn into
 * marketing.
 *
 * Spec Ref: §48 Model Selection Benchmark, §26 Android Hardware Acceleration
 */

/**
 * One timed inference call.
 *
 * @property modelId which model was called
 * @property latencyMs wall-clock time for the call
 * @property succeeded false when the call failed or timed out
 * @property unitsProcessed how many items the call covered (used for throughput)
 */
data class BenchmarkSample(
    val modelId: String,
    val latencyMs: Double,
    val succeeded: Boolean = true,
    val unitsProcessed: Int = 1
)

/**
 * What a model's samples add up to.
 *
 * @property modelId the model measured
 * @property samples how many calls were observed
 * @property failures how many of them failed
 * @property meanLatencyMs average call time
 * @property p95LatencyMs the slow tail - what the user actually feels
 * @property throughputPerSecond items processed per second of wall clock
 * @property errorRate share of calls that failed (0..1)
 * @property score 0..1 speed score derived from p95; higher is better
 * @property eligible whether the evidence is strong enough to recommend it
 * @property status plain-English reason when it is not eligible
 */
data class ModelBenchmarkResult(
    val modelId: String,
    val samples: Int,
    val failures: Int,
    val meanLatencyMs: Double,
    val p95LatencyMs: Double,
    val throughputPerSecond: Double,
    val errorRate: Double,
    val score: Double,
    val eligible: Boolean,
    val status: String
)

object ModelBenchmark {

    /**
     * Fewer samples than this and the numbers are noise, not evidence.
     * Ten is the smallest count where a p95 still means something.
     */
    const val MIN_SAMPLES: Int = 10

    /** Above this failure rate a model is not recommended however fast it is. */
    const val MAX_ERROR_RATE: Double = 0.10

    /**
     * Measure one model.
     *
     * @param samples every call observed for that model (several models may be mixed)
     */
    fun evaluate(samples: List<BenchmarkSample>): List<ModelBenchmarkResult> =
        samples.groupBy { it.modelId }.map { (modelId, calls) -> evaluateOne(modelId, calls) }
            .sortedWith(compareByDescending<ModelBenchmarkResult> { it.eligible }
                .thenBy { it.p95LatencyMs }
                .thenBy { it.errorRate })

    /**
     * Rank models best-first and return the one worth mounting, or null when no
     * model has enough evidence.
     *
     * @param samples every call observed
     */
    fun selectBest(samples: List<BenchmarkSample>): ModelBenchmarkResult? =
        evaluate(samples).firstOrNull { it.eligible }

    private fun evaluateOne(modelId: String, calls: List<BenchmarkSample>): ModelBenchmarkResult {
        val successes = calls.filter { it.succeeded }
        val failures = calls.size - successes.size
        val errorRate = if (calls.isEmpty()) 1.0 else failures.toDouble() / calls.size

        if (successes.isEmpty()) {
            return ModelBenchmarkResult(
                modelId = modelId, samples = calls.size, failures = failures,
                meanLatencyMs = 0.0, p95LatencyMs = 0.0, throughputPerSecond = 0.0,
                errorRate = errorRate, score = 0.0, eligible = false,
                status = "Not eligible: every observed call failed."
            )
        }

        val latencies = successes.map { it.latencyMs }.sorted()
        val mean = latencies.average()
        val p95 = percentile(latencies, 0.95)
        val totalUnits = successes.sumOf { it.unitsProcessed }
        val totalSeconds = successes.sumOf { it.latencyMs } / 1000.0

        // 0..1 speed score: 50 ms scores ~0.95, 500 ms ~0.67, 5 s ~0.17.
        val score = 1.0 / (1.0 + p95 / 1000.0)

        val eligible = calls.size >= MIN_SAMPLES && errorRate <= MAX_ERROR_RATE
        val status = when {
            calls.size < MIN_SAMPLES ->
                "Not eligible: only ${calls.size} of $MIN_SAMPLES required samples."
            errorRate > MAX_ERROR_RATE ->
                "Not eligible: ${"%.0f".format(errorRate * 100)}% of calls failed " +
                    "(limit ${"%.0f".format(MAX_ERROR_RATE * 100)}%)."
            else -> "Eligible: p95 ${"%.0f".format(p95)} ms over ${calls.size} calls."
        }

        return ModelBenchmarkResult(
            modelId = modelId,
            samples = calls.size,
            failures = failures,
            meanLatencyMs = mean,
            p95LatencyMs = p95,
            throughputPerSecond = if (totalSeconds <= 0.0) 0.0 else totalUnits / totalSeconds,
            errorRate = errorRate,
            score = score,
            eligible = eligible,
            status = status
        )
    }

    /**
     * Nearest-rank percentile: the smallest value at or above which the given
     * share of samples sits. Documented because percentile definitions differ.
     */
    private fun percentile(sortedAscending: List<Double>, fraction: Double): Double {
        if (sortedAscending.isEmpty()) return 0.0
        val rank = kotlin.math.ceil(fraction * sortedAscending.size).toInt()
        val index = (rank - 1).coerceIn(0, sortedAscending.size - 1)
        return sortedAscending[index]
    }
}
