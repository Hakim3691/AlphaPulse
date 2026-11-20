package com.aistudio.alphapulse.engine.proactive

import kotlin.math.ceil

/**
 * A real producer for §38's "base AlphaPulse views demonstrate zero degradation"
 * dimension.
 *
 * `MainActivity` feeds every rendered frame's `FrameMetrics.TOTAL_DURATION` here while
 * the base analysis screen is visible. Once a full rolling window has been observed
 * the monitor reports a p95 frame time; a p95 above the 60 fps budget (24 ms, with
 * headroom) is a regression, and a clean window clears an earlier one. Before the
 * first full window there is no observation at all, so the gate stays fail-closed
 * instead of assuming the views are healthy because nothing measured them.
 *
 * Spec Ref: Phase 2 §38
 */
data class BaseViewObservation(
    val p95FrameMs: Double,
    val regressionDetected: Boolean
)

class BaseViewHealthMonitor(
    private val windowSamples: Int = DEFAULT_WINDOW_SAMPLES,
    private val emitEverySamples: Int = DEFAULT_EMIT_EVERY_SAMPLES,
    private val p95BudgetMs: Double = DEFAULT_P95_BUDGET_MS
) {
    init {
        require(windowSamples > 0) { "windowSamples must be positive" }
        require(emitEverySamples in 1..windowSamples) {
            "emitEverySamples must be between 1 and windowSamples"
        }
        require(p95BudgetMs > 0.0) { "p95BudgetMs must be positive" }
    }

    private val samples = ArrayDeque<Double>()
    private var samplesSinceReport = 0

    /** Null until a full window exists; afterwards a fresh observation per interval. */
    fun recordFrame(frameDurationMs: Double): BaseViewObservation? {
        if (!frameDurationMs.isFinite() || frameDurationMs <= 0.0) return null

        samples.addLast(frameDurationMs)
        while (samples.size > windowSamples) samples.removeFirst()
        samplesSinceReport++

        if (samples.size < windowSamples || samplesSinceReport < emitEverySamples) return null
        samplesSinceReport = 0

        val sorted = samples.sorted()
        val index = (ceil(sorted.size * P95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        val p95 = sorted[index]
        return BaseViewObservation(
            p95FrameMs = p95,
            regressionDetected = p95 > p95BudgetMs
        )
    }

    companion object {
        const val DEFAULT_WINDOW_SAMPLES = 120
        const val DEFAULT_EMIT_EVERY_SAMPLES = 60

        /** A 60 fps frame is 16.7 ms; 24 ms leaves jitter headroom before it is a regression. */
        const val DEFAULT_P95_BUDGET_MS = 24.0

        const val P95 = 0.95
    }
}
