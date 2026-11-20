package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.ExecutionMode

/**
 * §38 — the mandatory production gate before Scaled Live unlocks.
 *
 * Scaled Live is the only mode that risks full configured sizing, so the spec makes
 * four proofs mandatory: 100 consecutive paper trades with zero unhandled
 * exceptions, cancellation latency under 180 ms, the foreground-session intercept
 * warning and wiping RAM on 100% of tested app-minimize events, and zero visual or
 * performance regression in the base AlphaPulse forecast views.
 *
 * The gate is evidence-driven: every number comes from the paper-trade ledger and
 * the intercept tests, so it cannot be flipped on by a preference. A missing
 * measurement is a failure, not a pass.
 *
 * Spec Ref: Phase 2 §38
 */
data class PreLaunchEvidence(
    val consecutivePaperTrades: Int,
    val unhandledExceptions: Int,
    val cancellationLatencyMsP95: Long,
    val minimizeEventsTested: Int,
    val minimizeEventsWarnedAndWiped: Int,
    val baseViewRegressionDetected: Boolean
)

data class PreLaunchVerdict(
    val unlocked: Boolean,
    val failures: List<String>
) {
    val summary: String
        get() = if (unlocked) {
            "All §38 pre-launch criteria met; Scaled Live may unlock"
        } else {
            "Scaled Live locked: " + failures.joinToString("; ")
        }

    /** Scaled Live is the only mode this gate guards. */
    fun unlocks(mode: ExecutionMode): Boolean = mode != ExecutionMode.SCALED_LIVE || unlocked
}

class PreLaunchAcceptanceGate {

    fun evaluate(evidence: PreLaunchEvidence): PreLaunchVerdict {
        val failures = mutableListOf<String>()

        if (evidence.consecutivePaperTrades < REQUIRED_CONSECUTIVE_PAPER_TRADES) {
            failures += "paper trades ${evidence.consecutivePaperTrades}/" +
                REQUIRED_CONSECUTIVE_PAPER_TRADES
        }
        if (evidence.unhandledExceptions > 0) {
            failures += "unhandled exceptions ${evidence.unhandledExceptions}"
        }
        if (evidence.cancellationLatencyMsP95 < 0L) {
            failures += "no cancellation latency measured"
        } else if (evidence.cancellationLatencyMsP95 >= MAX_CANCELLATION_LATENCY_MS) {
            failures += "cancellation latency ${evidence.cancellationLatencyMsP95}ms " +
                "not under ${MAX_CANCELLATION_LATENCY_MS}ms"
        }
        if (evidence.minimizeEventsTested <= 0) {
            failures += "no app-minimize events tested"
        } else if (evidence.minimizeEventsWarnedAndWiped != evidence.minimizeEventsTested) {
            failures += "foreground intercept warned and wiped " +
                "${evidence.minimizeEventsWarnedAndWiped}/${evidence.minimizeEventsTested} " +
                "minimize events"
        }
        if (evidence.baseViewRegressionDetected) {
            failures += "base AlphaPulse view regression detected"
        }

        return PreLaunchVerdict(unlocked = failures.isEmpty(), failures = failures)
    }

    companion object {
        const val REQUIRED_CONSECUTIVE_PAPER_TRADES = 100
        const val MAX_CANCELLATION_LATENCY_MS = 180L
    }
}
