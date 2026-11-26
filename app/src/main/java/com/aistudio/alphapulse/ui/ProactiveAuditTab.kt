package com.aistudio.alphapulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ProactiveMS Audit — the §29/C-22 decision ledger, newest first.
 *
 * It used to draw up to 200 rows as individual cards: a border, a coloured dot and three
 * lines each, so the tab was a wall of near-identical blocks that had to be scrolled
 * through. It now uses the Alpha Pulse ledger grammar instead (C-64): one header with a
 * live row count, one filter row with per-scope counts, and a pinned table — `TIME` and
 * `ACTION` stay put while `SYMBOL`, `REASON` and `MESSAGE` scroll together. A tap opens
 * the row's full message, so the table stays compact without hiding anything.
 *
 * The rows are the same persisted `AuditLogEntry` records the engine writes, so the tab
 * cannot disagree with the ledger on disk.
 *
 * Spec Ref: Phase 2 §29, §32, §71, §72, C-22, C-64
 */
@Composable
fun ProactiveAuditTab(entries: List<AuditLogEntry>, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf(AuditLedgerFilter.ALL) }
    var detailed by remember { mutableStateOf<AuditLogEntry?>(null) }

    val rows = AuditLedgerPresentation.visible(entries, filter)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(top = 8.dp)
            .testTag("proactive_audit_tab")
    ) {
        AuditLedgerSummary(entries = entries, filter = filter)
        AuditLedgerFilters(entries = entries, filter = filter, onSelect = { filter = it })

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .background(BrandCard, RoundedCornerShape(10.dp))
                    .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
                    .padding(16.dp)
                    .testTag(ProactiveAuditTags.EMPTY),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (entries.isEmpty()) {
                        "No audit decisions recorded yet — rows appear as the engine stages, cancels or flattens a board."
                    } else {
                        "No ${filter.label.lowercase()} rows in the ledger."
                    },
                    color = SlateTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            return@Column
        }

        AuditLedgerTable(
            rows = rows,
            onOpenDetail = { entry -> detailed = entry },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(ProactiveAuditTags.LEDGER)
        )
    }

    detailed?.let { entry ->
        AuditRowDetailDialog(entry = entry, onDismiss = { detailed = null })
    }
}

@Composable
private fun AuditLedgerSummary(entries: List<AuditLogEntry>, filter: AuditLedgerFilter) {
    val counts = AuditLedgerPresentation.counts(entries, filter)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
            .testTag(ProactiveAuditTags.HEADER),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "TRADE DECISIONS LEDGER",
                color = CyanAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Every staged, cancelled and flattened decision, newest first",
                color = SlateTextMuted,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = "${counts.shown}/${counts.total} ROWS",
            color = SlateTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AuditLedgerFilters(
    entries: List<AuditLogEntry>,
    filter: AuditLedgerFilter,
    onSelect: (AuditLedgerFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AuditLedgerPresentation.filters(entries).forEach { option ->
            val selected = option.filter == filter
            val tone = if (selected) CyanAccent else SlateTextSecondary
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) CyanAccent.copy(alpha = 0.12f) else BrandCard)
                    .border(1.dp, tone.copy(alpha = if (selected) 0.7f else 0.35f), RoundedCornerShape(4.dp))
                    .clickable { onSelect(option.filter) }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .testTag(ProactiveAuditTags.filter(option.filter))
            ) {
                Text(
                    text = "${option.filter.label} ${option.count}",
                    color = tone,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * The pinned table: identity columns stay put, the narrative columns share one
 * horizontal scroll so every row stays aligned with its header.
 */
@Composable
private fun AuditLedgerTable(
    rows: List<AuditLogEntry>,
    onOpenDetail: (AuditLogEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val scroll = rememberScrollState()

    Column(modifier = modifier.background(Color.Transparent)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandCard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .width(PINNED_WIDTH)
                    .padding(start = 14.dp, top = 7.dp, bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuditHeaderCell("TIME", 54.dp)
                AuditHeaderCell("ACTION", 104.dp)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scroll)
                    .padding(end = 14.dp, top = 7.dp, bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuditHeaderCell("SYMBOL", 84.dp)
                AuditHeaderCell("REASON", 168.dp)
                AuditHeaderCell("MESSAGE", 260.dp)
            }
        }

        HorizontalDivider(color = BrandBorder)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { entry ->
                val tint = entry.tone()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDetail(entry) }
                        .padding(vertical = 6.dp)
                        .testTag(ProactiveAuditTags.row(entry.id)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .width(PINNED_WIDTH)
                            .padding(start = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AuditCell(
                            text = timeFormat.format(Date(entry.timestamp)),
                            color = SlateTextMuted,
                            width = 54.dp
                        )
                        AuditCell(
                            text = entry.action.label.uppercase(),
                            color = tint,
                            width = 104.dp,
                            bold = true,
                            maxLines = 2
                        )
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(scroll)
                            .padding(end = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AuditCell(
                            text = entry.asset,
                            color = SlateTextPrimary,
                            width = 84.dp,
                            bold = true
                        )
                        AuditCell(
                            text = entry.reasonCode,
                            color = SlateTextSecondary,
                            width = 168.dp
                        )
                        AuditCell(
                            text = entry.message,
                            color = SlateTextMuted,
                            width = 260.dp
                        )
                    }
                }
                HorizontalDivider(color = BrandBorder.copy(alpha = 0.25f))
            }
        }
    }
}

@Composable
private fun AuditRowDetailDialog(entry: AuditLogEntry, onDismiss: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ProactiveAuditTags.ROW_DETAIL),
        containerColor = BrandCard,
        title = {
            Text(
                text = "${entry.action.label.uppercase()} · ${entry.asset}",
                color = entry.tone(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AuditDetailLine("TIME", timeFormat.format(Date(entry.timestamp)))
                AuditDetailLine("CAMPAIGN", entry.campaignId)
                AuditDetailLine("REASON", entry.reasonCode)
                Text(
                    text = entry.message,
                    color = SlateTextPrimary,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CLOSE", color = CyanAccent, fontSize = 11.sp)
            }
        }
    )
}

@Composable
private fun AuditDetailLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            color = SlateTextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(74.dp)
        )
        Text(
            text = value,
            color = SlateTextSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AuditHeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        color = SlateTextMuted,
        fontSize = 8.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(width)
    )
}

@Composable
private fun RowScope.AuditCell(
    text: String,
    color: Color,
    width: androidx.compose.ui.unit.Dp,
    bold: Boolean = false,
    maxLines: Int = 1
) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width)
    )
}

private val PINNED_WIDTH = 186.dp

/** Row tint: what the action means, not what it happened to. */
private fun AuditLogEntry.tone(): Color = when (action) {
    AuditLogAction.EMERGENCY_KILL,
    AuditLogAction.HARD_FLATTEN,
    AuditLogAction.ORPHANED_ALERT -> CrimsonBear

    AuditLogAction.SOFT_CANCEL,
    AuditLogAction.MANUAL_OVERRIDE,
    AuditLogAction.DEFENSIVE_SCALED -> AmberWarning

    AuditLogAction.CAMPAIGN_STAGED,
    AuditLogAction.SLICE_FILLED,
    AuditLogAction.BOARD_PROMOTED -> EmeraldBull
}
