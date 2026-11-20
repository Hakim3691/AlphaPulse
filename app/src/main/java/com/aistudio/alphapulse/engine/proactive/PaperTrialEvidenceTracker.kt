package com.aistudio.alphapulse.engine.proactive

/**
 * §38 — the paper-trading evidence source the Scaled Live gate reads.
 *
 * Before this existed nothing in the app produced [PreLaunchEvidence], so
 * [PreLaunchAcceptanceGate] could only ever return locked. The tracker accumulates
 * the four proofs while the operator runs Paper Mode:
 *
 *  - completed paper trades, reset to zero by an unhandled exception so the 100
 *    trades really are *consecutive* and exception-free;
 *  - measured cancellation latencies, summarised as a p95 (no measurement is
 *    reported as [NO_LATENCY_SAMPLE], never as a passing zero);
 *  - app-minimize intercept events, counted only as warned-and-wiped when the
 *    intercept actually warned and wiped;
 *  - a base-view regression flag, set by whatever instrumentation observes one.
 *
 * The tracker is the single writer and [PreLaunchAcceptanceGate] remains the single
 * judge, so the evidence cannot be hand-edited into a passing verdict at the call
 * site.
 *
 * Spec Ref: Phase 2 §38
 */
class PaperTrialEvidenceTracker(
    private val gate: PreLaunchAcceptanceGate = PreLaunchAcceptanceGate()
) {

    private var consecutiveCompletedTrades = 0
    private var exceptionsInCurrentStreak = 0
    private val cancellationLatenciesMs = ArrayDeque<Long>()
    private var minimizeEventsTested = 0
    private var minimizeEventsWarnedAndWiped = 0
    private var baseViewRegressionDetected = false

    /** A paper position closed (TP, hard flatten or operator flatten). */
    @Synchronized
    fun recordCompletedPaperTrade() {
        // The first trade after an exception starts a fresh streak, so the exception
        // that ended the previous streak no longer counts against this one.
        if (consecutiveCompletedTrades == 0) exceptionsInCurrentStreak = 0
        consecutiveCompletedTrades++
    }

    /** An unhandled exception aborted a paper cycle: the 100-trade streak restarts. */
    @Synchronized
    fun recordUnhandledException() {
        exceptionsInCurrentStreak++
        consecutiveCompletedTrades = 0
    }

    /** Time from the evidence frame to the cancellation decision, measured in-process. */
    @Synchronized
    fun recordCancellationLatency(latencyMs: Long) {
        if (latencyMs < 0L) return
        cancellationLatenciesMs.addLast(latencyMs)
        while (cancellationLatenciesMs.size > MAX_LATENCY_SAMPLES) {
            cancellationLatenciesMs.removeFirst()
        }
    }

    /** One app-minimize event; [warnedAndWiped] is false when only the wipe ran. */
    @Synchronized
    fun recordMinimizeEvent(warnedAndWiped: Boolean) {
        minimizeEventsTested++
        if (warnedAndWiped) minimizeEventsWarnedAndWiped++
    }

    /** Set once instrumentation observes visual or performance degradation. */
    @Synchronized
    fun recordBaseViewRegression(detected: Boolean) {
        if (detected) baseViewRegressionDetected = true
    }

    @Synchronized
    fun evidence(): PreLaunchEvidence = PreLaunchEvidence(
        consecutivePaperTrades = consecutiveCompletedTrades,
        unhandledExceptions = exceptionsInCurrentStreak,
        cancellationLatencyMsP95 = cancellationLatencyP95Ms(),
        minimizeEventsTested = minimizeEventsTested,
        minimizeEventsWarnedAndWiped = minimizeEventsWarnedAndWiped,
        baseViewRegressionDetected = baseViewRegressionDetected
    )

    fun verdict(): PreLaunchVerdict = gate.evaluate(evidence())

    /** 95th-percentile sample, or [NO_LATENCY_SAMPLE] when nothing was measured. */
    @Synchronized
    fun cancellationLatencyP95Ms(): Long {
        if (cancellationLatenciesMs.isEmpty()) return NO_LATENCY_SAMPLE
        val sorted = cancellationLatenciesMs.sorted()
        val index = kotlin.math.ceil(sorted.size * P95).toInt() - 1
        return sorted[index.coerceIn(0, sorted.lastIndex)]
    }

    companion object {
        /** §38 requires a *measured* latency; this marks "never measured". */
        const val NO_LATENCY_SAMPLE = -1L

        /** Bounded sample window: p95 over the latest 200 cancellations. */
        const val MAX_LATENCY_SAMPLES = 200

        const val P95 = 0.95
    }
}
