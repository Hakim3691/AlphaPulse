package com.aistudio.alphapulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.AuditLogAction
import com.aistudio.alphapulse.data.model.AuditLogEntry
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
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
 * The old host rendered this tab as one monospace string per row. It is now a ledger of
 * cards: the action, the coin, the machine-readable reason and the message the engine
 * recorded, so an operator can see why a board was staged, cancelled or flattened without
 * reading a wall of text. It reads the same persisted `AuditLogEntry` rows the engine
 * writes, so the tab cannot disagree with the ledger on disk.
 *
 * Spec Ref: Phase 2 §32, §71, §72, C-22
 */
@Composable
fun ProactiveAuditTab(entries: List<AuditLogEntry>, modifier: Modifier = Modifier) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
            .testTag("proactive_audit_tab"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PROACTIVE AUDIT LEDGER",
                            color = CyanAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Every staged, cancelled and flattened decision, newest first",
                            color = SlateTextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    Text(
                        text = "${entries.size} ROWS",
                        color = SlateTextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "No audit decisions recorded yet",
                        color = SlateTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Rows appear here as the engine stages, cancels or flattens a board.",
                        color = SlateTextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            items(entries, key = { entry -> entry.id }) { entry ->
                AuditRow(entry = entry, time = timeFormat.format(Date(entry.timestamp)))
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun AuditRow(entry: AuditLogEntry, time: String) {
    val tint = when (entry.action) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("audit_row_${entry.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(tint)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = entry.action.label.uppercase(),
                    color = tint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = time,
                color = SlateTextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.asset,
                color = SlateTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF0D1526))
                    .border(1.dp, CardBorder, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = entry.reasonCode,
                    color = SlateTextSecondary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Text(
            text = entry.message,
            color = SlateTextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}
