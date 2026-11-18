package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.db.PredictionEntity

/**
 * One verdict from the §29 router, graded against the forecast that was live when it
 * was issued.
 *
 * Why this exists
 * ---------------
 * The decisions ledger recorded a state, a confidence, a source and a reason, and
 * nothing ever graded it — every row stayed "recorded" forever. A verdict is not
 * settled by itself, though: it is a call about where price goes, and the price
 * ledger already stores exactly that call for the verdict's reference horizon, with
 * its realized price and cone outcome. Grading the verdict against the forecast it
 * drove keeps one clock and one source of truth (C-10/C-29) instead of adding a
 * second settlement path that could disagree with the price ledger.
 *
 * Match rule: for each verdict, the forecast for the same symbol and the verdict's
 * reference horizon whose window `[issuedTimestamp, targetTimestamp]` contains the
 * verdict's timestamp; the most recent such forecast wins when several overlap.
 * A verdict with no covering forecast stays unsettled — it is never credited.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, C-10, C-29
 */
data class DecisionLedgerRow(
    val record: DecisionRecordEntity,
    /** True once the forecast that covered this verdict has settled. */
    val settled: Boolean,
    /** Did the verdict call the move correctly? Null while unsettled. */
    val hit: Boolean? = null,
    /** Price at the verdict's target time, once settled. */
    val realizedPrice: Double? = null,
    /** Percent move from the forecast's base price to [realizedPrice]. */
    val realizedPct: Double? = null
)

object DecisionLedgerJoin {

    /** Grades every verdict against the price ledger; records keep their order. */
    fun settle(
        records: List<DecisionRecordEntity>,
        predictions: List<PredictionEntity>
    ): List<DecisionLedgerRow> = records.map { record ->
        val covering = predictions
            .filter { it.symbol == record.symbol && it.horizonId == record.referenceHorizon }
            .filter {
                it.issuedTimestamp <= record.issuedTimestamp &&
                    record.issuedTimestamp <= it.targetTimestamp
            }
            .maxByOrNull { it.issuedTimestamp }

        val realized = covering?.takeIf { it.settled }?.realizedPrice
        if (covering == null || realized == null) {
            DecisionLedgerRow(record = record, settled = false)
        } else {
            val pct = if (covering.basePrice > 0.0) {
                (realized - covering.basePrice) / covering.basePrice * 100.0
            } else {
                0.0
            }
            DecisionLedgerRow(
                record = record,
                settled = true,
                hit = hit(record.state, pct, covering.inCone == true),
                realizedPrice = realized,
                realizedPct = pct
            )
        }
    }

    /**
     * A directional call wins when price moved its way; a neutral / no-trade call wins
     * when price stayed inside the predicted cone, which is the only claim it made.
     */
    fun hit(state: String, realizedPct: Double, stayedInCone: Boolean): Boolean = when (state) {
        "STRONG_LONG", "LONG" -> realizedPct > 0.0
        "STRONG_SHORT", "SHORT" -> realizedPct < 0.0
        else -> stayedInCone
    }
}
