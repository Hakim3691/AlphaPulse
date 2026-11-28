package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry

/**
 * C-64 — the Audit tab's ledger grammar.
 *
 * The tab drew up to 200 audit rows as individual cards. It now reads like the Alpha
 * Pulse ledgers: one pinned table, one filter row with live counts, and a row that opens
 * its full message. The classification and counting are pure functions here so the
 * layout cannot disagree with the filter chips it renders.
 *
 * The scopes follow the decision lifecycle the operator reasons about — what was staged,
 * what filled, what was cancelled, and what the risk layer did — rather than mirroring
 * enum names. Engine housekeeping rows (`SYS_`/`MODE_` campaign ids) are `CAMPAIGN_STAGED`
 * actions and therefore appear under `STAGED` with the rest of the staging decisions.
 *
 * Spec Ref: §29, §71, §72, C-64
 */
enum class AuditLedgerFilter(val label: String) {
    ALL("ALL"),
    STAGED("STAGED"),
    FILLS("FILLS"),
    CANCELS("CANCELS"),
    RISK("RISK");

    /** Whether [entry] belongs to this scope. `ALL` admits every row. */
    fun admits(entry: AuditLogEntry): Boolean = when (this) {
        ALL -> true
        STAGED -> entry.action == AuditLogAction.CAMPAIGN_STAGED ||
            entry.action == AuditLogAction.BOARD_PROMOTED
        FILLS -> entry.action == AuditLogAction.SLICE_FILLED
        CANCELS -> entry.action == AuditLogAction.SOFT_CANCEL ||
            entry.action == AuditLogAction.DEFENSIVE_SCALED
        RISK -> entry.action == AuditLogAction.HARD_FLATTEN ||
            entry.action == AuditLogAction.EMERGENCY_KILL ||
            entry.action == AuditLogAction.ORPHANED_ALERT ||
            entry.action == AuditLogAction.MANUAL_OVERRIDE
    }
}

data class AuditLedgerCounts(val total: Int, val shown: Int)

data class AuditLedgerFilterCount(val filter: AuditLedgerFilter, val count: Int)

object AuditLedgerPresentation {

    /** The columns the table renders, matching the Alpha Pulse ledger grammar. */
    val COLUMNS: List<String> =
        listOf("TIME", "ASSET", "ACTION", "BOARD", "REASON", "MESSAGE")

    /**
     * The one non-`ALL` scope a row belongs to. Kept total: an action added later still
     * classifies (as `RISK`, the conservative default) instead of crashing or vanishing.
     */
    fun group(entry: AuditLogEntry): AuditLedgerFilter =
        AuditLedgerFilter.entries.firstOrNull { it != AuditLedgerFilter.ALL && it.admits(entry) }
            ?: AuditLedgerFilter.RISK

    /** The asset column; a portfolio-wide row has no coin and says so. */
    fun displayAsset(entry: AuditLogEntry): String =
        entry.asset.takeIf { it.isNotBlank() } ?: "PORTFOLIO"

    /** The board/campaign column, parsed here rather than in the composable. */
    fun displayBoard(entry: AuditLogEntry): String =
        entry.campaignId.takeIf { it.isNotBlank() } ?: "—"

    /** The rows a scope shows, in the ledger's own order (newest first). */
    fun visible(entries: List<AuditLogEntry>, filter: AuditLedgerFilter): List<AuditLogEntry> =
        entries.filter { filter.admits(it) }

    fun counts(entries: List<AuditLogEntry>, filter: AuditLedgerFilter): AuditLedgerCounts =
        AuditLedgerCounts(total = entries.size, shown = visible(entries, filter).size)

    /** Every scope with the number of rows it holds, `ALL` first. */
    fun filters(entries: List<AuditLogEntry>): List<AuditLedgerFilterCount> =
        AuditLedgerFilter.entries.map { filter ->
            AuditLedgerFilterCount(filter, visible(entries, filter).size)
        }
}

/** Test tags the audit ledger specs address instead of matching label strings. */
object ProactiveAuditTags {
    const val LEDGER = "audit_ledger"
    const val HEADER = "audit_ledger_header"
    const val EMPTY = "audit_ledger_empty"
    const val ROW_DETAIL = "audit_row_detail_dialog"

    fun filter(filter: AuditLedgerFilter): String =
        "audit_ledger_filter_${filter.name.lowercase()}"

    fun row(id: Long): String = "audit_row_$id"
}
