package com.aistudio.alphapulse.domain.presentation

import com.aistudio.alphapulse.data.repository.DecisionLedgerRow

/**
 * §21 / C-29 — which verdict the ledger should read as still in force.
 *
 * Why this exists
 * ---------------
 * Coalescing ([com.aistudio.alphapulse.data.repository.DecisionCoalescing]) writes one
 * row per verdict, not one per evaluation cycle, so the row a quiet coin shows is the
 * `NEUTRAL` that is *still* its call — it just looked identical to a stale row from
 * hours ago, because nothing on the row said so. The newest verdict per coin and
 * reference horizon is that live call, and it stays in force only while it is still
 * pending: once the forecast under it settles the verdict has been graded and there is
 * nothing left in force for that horizon. The marker is therefore a pure projection over
 * the rows already on screen — no schema change, no second table, nothing to keep in
 * sync with the audit trail.
 *
 * Grouping is per horizon on purpose: a 1h verdict issued an hour ago is still the
 * call for its horizon even after the 5m verdict has moved on.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Decision Router, C-29
 */
object VerdictInForce {

    /**
     * Ids of the verdicts still in force: the newest row of each (symbol, reference
     * horizon) group, when that newest row is still pending.
     */
    fun ids(rows: List<DecisionLedgerRow>): Set<String> = rows
        .groupBy { it.record.symbol to it.record.referenceHorizon }
        .values
        .mapNotNull { group ->
            // Ties on the timestamp resolve by id so the marker never flickers between
            // two rows written in the same millisecond.
            group.maxWithOrNull(
                compareBy({ it.record.issuedTimestamp }, { it.record.id })
            )
        }
        .filter { !it.settled }
        .map { it.record.id }
        .toSet()
}
