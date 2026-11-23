package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity

/**
 * §21 — one row per verdict, not one row per evaluation cycle.
 *
 * Why this exists
 * ---------------
 * The engine evaluates every ten seconds, and the §29 router answers `NEUTRAL` whenever its
 * inputs do not clear the confidence floor — which is most of the time on a quiet coin. Each
 * of those cycles wrote its own row, so the trade-decisions ledger filled with hundreds of
 * identical `NEUTRAL` verdicts a few seconds apart and the call that actually changed the
 * state was buried in the scroll. Nothing about the operator's question ("what did the
 * router decide, and when did that change?") needs the repeats.
 *
 * So the newest row is read first: a cycle that reproduces the same verdict for the same
 * coin and reference horizon, answered by the same router, is not a new decision and is not
 * recorded again. A changed state, a changed horizon, a different router or another coin is
 * a new verdict and always recorded. The earlier row keeps its timestamp, which is when the
 * state began, and its confidence, which is the number the router had when it said it.
 *
 * Coalescing is not deletion: nothing already recorded is rewritten or removed, so the
 * audit trail still shows every change of mind in order.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, C-29
 */
object DecisionCoalescing {

    /**
     * True when [next] repeats [previous] and must not become its own ledger row.
     *
     * Confidence is deliberately not compared: the calibrated number drifts a few
     * hundredths between cycles without the verdict changing, and comparing it would
     * restore the one-row-per-cycle log this removes.
     */
    fun supersedes(previous: DecisionRecordEntity?, next: DecisionRecordEntity): Boolean {
        if (previous == null) return false
        return previous.symbol == next.symbol &&
            previous.referenceHorizon == next.referenceHorizon &&
            previous.state == next.state &&
            previous.source == next.source
    }
}
