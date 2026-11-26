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
 * Spec Ref: §29, §71, §72, C-64
 */
enum class AuditLedgerCategory { ORDERS, RISK, OPERATOR, SYSTEM }

/** The scopes the ledger can be filtered to; `ALL` keeps every row. */
enum class AuditLedgerFilter(val label: String, val category: AuditLedgerCategory?) {
    ALL("ALL", null),
    ORDERS("ORDERS", AuditLedgerCategory.ORDERS),
    RISK("RISK", AuditLedgerCategory.RISK),
    OPERATOR("OPERATOR", AuditLedgerCategory.OPERATOR),
    SYSTEM("SYSTEM", AuditLedgerCategory.SYSTEM)
}

data class AuditLedgerCounts(val total: Int, val shown: Int)

data class AuditLedgerFilterCount(val filter: AuditLedgerFilter, val count: Int)

object AuditLedgerPresentation {

    /** The columns the table renders, matching the Alpha Pulse ledger grammar. */
    val COLUMNS: List<String> = listOf("TIME", "ACTION", "SYMBOL", "REASON", "MESSAGE")

    /**
     * Which scope a row belongs to.
     *
     * Engine and host housekeeping (boot, mode switch, pre-flight diagnostics, symbol
     * routing) writes `CAMPAIGN_STAGED` just like a real board, so it is classified by
     * its `SYS_`/`MODE_`/`ACTIVE_` campaign id first rather than pretending to be a
     * trade decision.
     */
    fun category(entry: AuditLogEntry): AuditLedgerCategory {
        if (entry.asset == "PORTFOLIO" || entry.campaignId.startsWith("SYS_") ||
            entry.campaignId.startsWith("MODE_") || entry.campaignId.startsWith("ACTIVE_")
        ) {
            return AuditLedgerCategory.SYSTEM
        }
        return when (entry.action) {
            AuditLogAction.CAMPAIGN_STAGED,
            AuditLogAction.SLICE_FILLED,
            AuditLogAction.BOARD_PROMOTED,
            AuditLogAction.DEFENSIVE_SCALED -> AuditLedgerCategory.ORDERS

            AuditLogAction.SOFT_CANCEL,
            AuditLogAction.HARD_FLATTEN -> AuditLedgerCategory.RISK

            AuditLogAction.EMERGENCY_KILL,
            AuditLogAction.MANUAL_OVERRIDE,
            AuditLogAction.ORPHANED_ALERT -> AuditLedgerCategory.OPERATOR
        }
    }

    /** The rows a scope shows, in the ledger's own order (newest first). */
    fun visible(entries: List<AuditLogEntry>, filter: AuditLedgerFilter): List<AuditLogEntry> {
        val category = filter.category ?: return entries
        return entries.filter { category(it) == category }
    }

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
