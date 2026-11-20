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
 *  - measured cancellation latencies, summarised as a p95 plus the sample count (no
 *    measurement is reported as [NO_LATENCY_SAMPLE], never as a passing zero);
 *  - app-minimize intercept events, counted only as warned-and-wiped when the
 *    intercept actually warned and wiped;
 *  - an observed base-view health window. Absent observation is *unmet*, so a gate
 *    can never endorse Scaled Live on a dimension nobody measured.
 *
 * When a [PaperTrialEvidenceStore] is supplied every mutation is persisted and the
 * constructor reloads it, so a campaign spanning many app launches continues from
 * where it left off. The persisted payload is validated by
 * [PaperTrialEvidenceCodec]; corrupt, truncated, tampered or stale-format state is
 * discarded and the tracker starts locked rather than unlocking or crashing.
 *
 * Spec Ref: Phase 2 §38
 */
class PaperTrialEvidenceTracker(
    private val gate: PreLaunchAcceptanceGate = PreLaunchAcceptanceGate(),
    private val store: PaperTrialEvidenceStore? = null
) {

    private var consecutiveCompletedTrades = 0
    private var exceptionsInCurrentStreak = 0
    private val cancellationLatenciesMs = ArrayDeque<Long>()
    private var minimizeEventsTested = 0
    private var minimizeEventsWarnedAndWiped = 0
    private var baseViewObserved = false
    private var baseViewRegressionDetected = false

    init {
        loadPersisted()
    }

    /** A paper position closed (TP, hard flatten or operator flatten). */
    @Synchronized
    fun recordCompletedPaperTrade() {
        // The first trade after an exception starts a fresh streak, so the exception
        // that ended the previous streak no longer counts against this one.
        if (consecutiveCompletedTrades == 0) exceptionsInCurrentStreak = 0
        consecutiveCompletedTrades++
        persist()
    }

    /** An unhandled exception aborted a paper cycle: the 100-trade streak restarts. */
    @Synchronized
    fun recordUnhandledException() {
        exceptionsInCurrentStreak++
        consecutiveCompletedTrades = 0
        persist()
    }

    /** Time from the evidence frame to the cancellation decision, measured in-process. */
    @Synchronized
    fun recordCancellationLatency(latencyMs: Long) {
        if (latencyMs < 0L) return
        cancellationLatenciesMs.addLast(latencyMs)
        while (cancellationLatenciesMs.size > PaperTrialEvidenceCodec.MAX_LATENCY_SAMPLES) {
            cancellationLatenciesMs.removeFirst()
        }
        persist()
    }

    /** One app-minimize event; [warnedAndWiped] is false when only the wipe ran. */
    @Synchronized
    fun recordMinimizeEvent(warnedAndWiped: Boolean) {
        minimizeEventsTested++
        if (warnedAndWiped) minimizeEventsWarnedAndWiped++
        persist()
    }

    /**
     * A base-view frame-health window was observed. The latest window wins: a clean
     * window clears an earlier regression so transient startup jank does not lock the
     * gate forever, while the absence of any observation stays unmet.
     */
    @Synchronized
    fun recordBaseViewObservation(regressionDetected: Boolean) {
        baseViewObserved = true
        baseViewRegressionDetected = regressionDetected
        persist()
    }

    @Synchronized
    fun evidence(): PreLaunchEvidence = PreLaunchEvidence(
        consecutivePaperTrades = consecutiveCompletedTrades,
        unhandledExceptions = exceptionsInCurrentStreak,
        cancellationLatencyMsP95 = cancellationLatencyP95Ms(),
        cancellationSamples = cancellationLatenciesMs.size,
        minimizeEventsTested = minimizeEventsTested,
        minimizeEventsWarnedAndWiped = minimizeEventsWarnedAndWiped,
        baseViewObserved = baseViewObserved,
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

    // ------------------------------------------------------------- persistence

    private fun snapshot() = PaperTrialEvidenceCodec.Snapshot(
        consecutiveCompletedTrades = consecutiveCompletedTrades,
        exceptionsInCurrentStreak = exceptionsInCurrentStreak,
        latenciesMs = cancellationLatenciesMs.toList(),
        minimizeEventsTested = minimizeEventsTested,
        minimizeEventsWarnedAndWiped = minimizeEventsWarnedAndWiped,
        baseViewObserved = baseViewObserved,
        baseViewRegressionDetected = baseViewRegressionDetected
    )

    private fun loadPersisted() {
        val payload = try {
            store?.read()
        } catch (_: Throwable) {
            // A store that cannot be read is treated as absent evidence, never a pass.
            null
        } ?: return

        val snapshot = PaperTrialEvidenceCodec.decode(payload)
        if (snapshot == null) {
            // Corrupt, stale-format or partially written: discard it. The tracker
            // remains locked and a fresh, valid payload replaces it on the next event.
            try {
                store?.clear()
            } catch (_: Throwable) {
                // Nothing else to do; the in-memory tracker is already empty.
            }
            return
        }

        consecutiveCompletedTrades = snapshot.consecutiveCompletedTrades
        exceptionsInCurrentStreak = snapshot.exceptionsInCurrentStreak
        cancellationLatenciesMs.clear()
        cancellationLatenciesMs.addAll(snapshot.latenciesMs)
        minimizeEventsTested = snapshot.minimizeEventsTested
        minimizeEventsWarnedAndWiped = snapshot.minimizeEventsWarnedAndWiped
        baseViewObserved = snapshot.baseViewObserved
        baseViewRegressionDetected = snapshot.baseViewRegressionDetected
    }

    private fun persist() {
        val target = store ?: return
        try {
            target.write(PaperTrialEvidenceCodec.encode(snapshot()))
        } catch (_: Throwable) {
            // Persistence is best-effort: a failed write must not crash the cycle or
            // lose the in-memory evidence for this session.
        }
    }

    companion object {
        /** §38 requires a *measured* latency; this marks "never measured". */
        const val NO_LATENCY_SAMPLE = -1L

        const val P95 = 0.95
    }
}
