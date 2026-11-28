package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-64 — the audit ledger is a ledger, not a card feed.
 *
 * The Audit tab drew up to 200 audit rows as individual cards, each with its own border,
 * dot and three lines, so the operator had to scroll a wall of near-identical blocks. It
 * now reads like the Alpha Pulse ledgers: one pinned table, one filter row, and a row the
 * operator can open for the full message.
 *
 * The scopes follow the decision lifecycle — STAGED, FILLS, CANCELS, RISK — and the table
 * has the house columns TIME, ASSET, ACTION, BOARD, REASON, MESSAGE.
 *
 * Spec Ref: §29, §71, §72, C-22, C-64
 */
class AuditLedgerPresentationBddSpec {

    private fun row(
        id: Long,
        action: AuditLogAction,
        asset: String,
        reason: String,
        campaign: String = "BOARD_$id"
    ) = AuditLogEntry(
        id = id,
        timestamp = 1_800_000_000_000L + id * 1_000,
        asset = asset,
        campaignId = campaign,
        action = action,
        reasonCode = reason,
        message = "Row $id recorded by the engine"
    )

    private val entries = listOf(
        row(7, AuditLogAction.HARD_FLATTEN, "SOLUSDT", "HARD_STOP"),
        row(6, AuditLogAction.EMERGENCY_KILL, "PAXGUSDT", "KILL_SWITCH"),
        row(5, AuditLogAction.SOFT_CANCEL, "PAXGUSDT", "PRICE_INVALIDATED"),
        row(4, AuditLogAction.SLICE_FILLED, "SOLUSDT", "SLICE_FILL_1"),
        row(3, AuditLogAction.CAMPAIGN_STAGED, "BTWUSDT", "STAGING_GATE_PAPER"),
        row(2, AuditLogAction.MANUAL_OVERRIDE, "PAXGUSDT", "MANUAL_OVERRIDE"),
        row(1, AuditLogAction.CAMPAIGN_STAGED, "SOLUSDT", "SYSTEM_INITIALIZED", campaign = "SYS_BOOT")
    )

    @Test
    fun `given the audit rows when grouped then each row belongs to exactly one lifecycle scope`() {
        val groups = entries.map { AuditLedgerPresentation.group(it) }

        assertEquals(AuditLedgerFilter.RISK, groups[0])
        assertEquals(AuditLedgerFilter.RISK, groups[1])
        assertEquals(AuditLedgerFilter.CANCELS, groups[2])
        assertEquals(AuditLedgerFilter.FILLS, groups[3])
        assertEquals(AuditLedgerFilter.STAGED, groups[4])
        assertEquals(AuditLedgerFilter.RISK, groups[5])
        assertEquals(AuditLedgerFilter.STAGED, groups[6])
    }

    @Test
    fun `given the ALL scope when filtered then every row is shown newest first`() {
        val shown = AuditLedgerPresentation.visible(entries, AuditLedgerFilter.ALL)

        assertEquals(entries.map { it.id }, shown.map { it.id })
    }

    @Test
    fun `given a scope when filtered then only its rows are shown and the counts still describe the whole ledger`() {
        val riskOnly = AuditLedgerPresentation.visible(entries, AuditLedgerFilter.RISK)

        assertEquals(listOf(7L, 6L, 2L), riskOnly.map { it.id })
        val counts = AuditLedgerPresentation.counts(entries, AuditLedgerFilter.RISK)
        assertEquals(entries.size, counts.total)
        assertEquals(3, counts.shown)
    }

    @Test
    fun `given the filter row when built then every scope is offered with its own count, ALL first`() {
        val filters = AuditLedgerPresentation.filters(entries)

        assertEquals(AuditLedgerFilter.ALL, filters.first().filter)
        assertEquals(AuditLedgerFilter.entries.size, filters.size)
        assertEquals(entries.size, filters.first().count)
        // Each row is counted once by ALL and once by its own scope.
        assertEquals(entries.size * 2, filters.sumOf { it.count })
    }

    @Test
    fun `given the table when built then it has the house columns, with BOARD parsed outside the composable`() {
        assertEquals(
            listOf("TIME", "ASSET", "ACTION", "BOARD", "REASON", "MESSAGE"),
            AuditLedgerPresentation.COLUMNS
        )
        assertEquals("BOARD_4", AuditLedgerPresentation.displayBoard(entries[3]))
        val source = RepoFiles.of(
            "app/src/main/java/com/aistudio/alphapulse/ui/ProactiveAuditTab.kt"
        ).readText()
        assertTrue(
            "The audit tab must render a pinned table, not one card per row",
            source.contains("AuditLedgerTable(")
        )
        assertTrue(
            "Every ledger row keeps the detail behind a tap instead of three lines of card",
            source.contains("ProactiveAuditTags.ROW_DETAIL")
        )
    }

    @Test
    fun `given a row without an asset when displayed then it reads PORTFOLIO instead of an empty cell`() {
        val portfolio = row(9, AuditLogAction.EMERGENCY_KILL, "", "KILL_SWITCH")

        assertEquals("PORTFOLIO", AuditLedgerPresentation.displayAsset(portfolio))
        assertEquals("—", AuditLedgerPresentation.displayBoard(portfolio.copy(campaignId = "")))
    }
}
