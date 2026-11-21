package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBearish
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.TextPrimary
import com.aistudio.alphapulse.ui.theme.TextSecondary

/**
 * The settled-rows switch the three ledgers share.
 *
 * The ledgers are working lists: what still awaits its settlement is the actionable
 * part, so settled rows are hidden by default and this button is the only way to
 * review them. It is one composable rather than three copies so the price, pattern
 * and trade-decision ledgers cannot drift apart in wording or behaviour.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §73 Pattern Ledger, Directive §1 Dual Ledger
 */
@Composable
fun LedgerSettledToggle(
    showSettled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (showSettled) "SHOW PENDING" else "SHOW ALL",
            color = if (showSettled) BrandBullish else TextSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * One of the three identical ledger actions: LEDGER (export) or TRASH (clear).
 *
 * Keeping the icon and the word in one composable is what makes the three ledgers
 * share a header instead of three near-copies that drift.
 */
@Composable
fun LedgerActionButton(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = label,
                color = tint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * The header the three ledgers share: title, `Pend:`/`Settled:` counters, a
 * filter-scoped summary line and exactly three actions (`SHOW ALL`, `LEDGER`,
 * `TRASH`).
 *
 * Spec Ref: §21 Prediction Audit Ledger, C-29, Directive §2 Ledger Homogeneity
 */
@Composable
fun LedgerHeader(
    title: String,
    pendingCount: Int,
    settledCount: Int,
    summary: String,
    summaryColor: Color,
    showSettled: Boolean,
    onToggleSettled: () -> Unit,
    onExportClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 12.dp, end = 12.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = BrandBullish,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Pend: $pendingCount",
                    color = BrandAmber,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Settled: $settledCount",
                    color = BrandBullish,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = summary,
                color = summaryColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            LedgerSettledToggle(showSettled = showSettled, onToggle = onToggleSettled)
            LedgerActionButton("LEDGER", Icons.Default.Share, BrandAccent, onExportClick)
            LedgerActionButton("TRASH", Icons.Default.Delete, BrandBearish, onClearClick)
        }
    }
}
