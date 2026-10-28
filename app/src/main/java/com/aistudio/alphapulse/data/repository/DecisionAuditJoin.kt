package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import java.util.Locale

/**
 * Attaches the recorded §29 verdict to each prediction row in the audit exports.
 *
 * A verdict is recorded once per evaluated snapshot, while predictions are issued
 * per horizon, so the two cannot share a primary key: the decision is joined on
 * `symbol` + `issuedTimestamp`, the millisecond the cycle stamped on both.
 *
 * The rules here are the ones a reader of the export depends on, so they live in
 * one place and are asserted directly:
 *
 *  * A row with no recorded verdict gets **empty** cells, never another row's
 *    verdict and never an invented default.
 *  * A verdict with no calibrated number reads [UNSCORED], because `0.00` would
 *    claim a calibrated probability of zero when there simply is none.
 *  * Reasons are quoted and escaped, because they routinely contain commas
 *    ("Needle 3 has no mounted checkpoint, so ...") which would otherwise split
 *    one field into several and shift every later column.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, §38 Export System
 */
internal object DecisionAuditJoin {

    /** Written instead of a confidence when the router had no calibrated number. */
    const val UNSCORED: String = "UNSCORED"

    /** Four empty cells: the row keeps its column count without borrowing a verdict. */
    private const val EMPTY_CELLS: String = "\"\",\"\",\"\",\"\""

    /** Stable id for one symbol's verdict at one instant, so a re-evaluation replaces it. */
    fun idOf(symbol: String, issuedTimestamp: Long): String = "decision-$symbol-$issuedTimestamp"

    /** Join key: two symbols evaluated in the same millisecond must not collide. */
    fun keyOf(symbol: String, issuedTimestamp: Long): String = "$symbol@$issuedTimestamp"

    /** Index recorded verdicts by [keyOf] so an export is a lookup, not a scan per row. */
    fun indexOf(records: List<DecisionRecordEntity>): Map<String, DecisionRecordEntity> =
        records.associateBy { keyOf(it.symbol, it.issuedTimestamp) }

    /** The verdict recorded for this row, or null when that cycle recorded none. */
    fun lookup(
        index: Map<String, DecisionRecordEntity>,
        symbol: String,
        issuedTimestamp: Long
    ): DecisionRecordEntity? = index[keyOf(symbol, issuedTimestamp)]

    /** A calibrated confidence, or [UNSCORED] - never a flattering `0.00`. */
    fun confidenceCell(record: DecisionRecordEntity?): String {
        if (record == null) return ""
        return if (record.confidence > 0.0) {
            String.format(Locale.US, "%.2f", record.confidence)
        } else {
            UNSCORED
        }
    }

    /** The four decision columns, quoted so a comma in a reason cannot shift them. */
    fun csvCells(record: DecisionRecordEntity?): String {
        if (record == null) return EMPTY_CELLS
        return listOf(record.state, confidenceCell(record), record.source, record.reason)
            .joinToString(",") { cell(it) }
    }

    /** The reason on one line, for the line-per-record pseudo-Parquet export. */
    fun singleLineReason(record: DecisionRecordEntity?): String =
        record?.reason
            ?.replace(',', ';')
            ?.replace('\n', ' ')
            ?: ""

    /** RFC 4180: wrap in quotes and double any inner quote. */
    private fun cell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
