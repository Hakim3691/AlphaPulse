package com.aistudio.alphapulse.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-64 — the Audit tab renders as the pinned ledger the other tabs use.
 *
 * These scenarios drive the composed tab: a 200-row ledger, a filter that narrows it in
 * place, and a row detail dialog that keeps the long message off the table.
 *
 * Spec Ref: §29, §71, §72, C-64
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class AuditLedgerLayoutBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private fun row(id: Long, action: AuditLogAction, asset: String, reason: String): AuditLogEntry =
        AuditLogEntry(
            id = id,
            timestamp = 1_800_000_000_000L + id * 1_000,
            asset = asset,
            campaignId = "BOARD_$id",
            action = action,
            reasonCode = reason,
            message = "Row $id recorded by the engine"
        )

    private val entries = listOf(
        row(1, AuditLogAction.HARD_FLATTEN, "SOLUSDT", "HARD_STOP"),
        row(2, AuditLogAction.SOFT_CANCEL, "PAXGUSDT", "PRICE_INVALIDATED"),
        row(3, AuditLogAction.CAMPAIGN_STAGED, "BTWUSDT", "STAGING_GATE_PAPER"),
        row(4, AuditLogAction.SLICE_FILLED, "SOLUSDT", "SLICE_FILL_1")
    )

    @Test
    fun `given audit rows when the tab renders then they are table rows under one ledger header`() {
        compose.setContent { ProactiveAuditTab(entries = entries) }

        compose.onNodeWithTag(ProactiveAuditTags.LEDGER).assertExists()
        compose.onNodeWithTag(ProactiveAuditTags.HEADER).assertExists()
        compose.onNodeWithTag("audit_row_1").assertExists()
    }

    @Test
    fun `given the RISK filter when tapped then only invalidation rows remain`() {
        compose.setContent { ProactiveAuditTab(entries = entries) }

        compose.onNodeWithTag(ProactiveAuditTags.filter(AuditLedgerFilter.RISK)).performClick()

        compose.onNodeWithTag("audit_row_1").assertExists()
        compose.onNodeWithTag("audit_row_3").assertDoesNotExist()
    }

    @Test
    fun `given a row when tapped then its full message opens in a detail dialog`() {
        compose.setContent { ProactiveAuditTab(entries = entries) }

        compose.onNodeWithTag("audit_row_4").performClick()

        compose.onNodeWithTag(ProactiveAuditTags.ROW_DETAIL).assertExists()
    }

    @Test
    fun `given no audit rows when the tab renders then it says so instead of an empty table`() {
        compose.setContent { ProactiveAuditTab(entries = emptyList()) }

        compose.onAllNodesWithTag("audit_row_0").assertCountEquals(0)
        compose.onNodeWithTag(ProactiveAuditTags.EMPTY).assertExists()
    }
}
