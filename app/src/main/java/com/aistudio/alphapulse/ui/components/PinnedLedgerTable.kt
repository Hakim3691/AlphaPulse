package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.TextTertiary

/**
 * Phase 1 §2: the responsive ledger table.
 *
 * The price and pattern ledgers clipped their headers on a standard Android
 * viewport ("TED PATTERN", "SGD LEARNIN") because every column lived in one
 * horizontal scroll. The asset/horizon columns are now pinned and the remaining
 * columns scroll together: one shared [androidx.compose.foundation.ScrollState] drives
 * every row, so the table never desynchronises and the identity of a row is always
 * visible.
 *
 * Spec Ref: Phase 1 §2 Price & Pattern Ledgers (Horizontal Clipping)
 */

/** One body row: the pinned cells and the horizontally scrolling cells. */
data class LedgerPinnedRow(
    val pinned: @Composable RowScope.() -> Unit,
    val scroll: @Composable RowScope.() -> Unit
)

/** A fixed-width table header label, matching the body text style. */
@Composable
fun LedgerHeaderCell(text: String, width: Dp) {
    Text(
        text = text,
        color = TextTertiary,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(width)
    )
}

@Composable
fun PinnedLedgerTable(
    pinnedWidth: Dp,
    pinnedHeader: @Composable RowScope.() -> Unit,
    scrollHeader: @Composable RowScope.() -> Unit,
    rows: List<LedgerPinnedRow>,
    emptyContent: @Composable () -> Unit
) {
    val horizontal = rememberScrollState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.background(BrandCard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .width(pinnedWidth)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = pinnedHeader
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontal)
                    .padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = scrollHeader
            )
        }

        HorizontalDivider(color = BrandBorder)

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                emptyContent()
            }
        } else {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .width(pinnedWidth)
                            .padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = row.pinned
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontal)
                            .padding(end = 12.dp, top = 7.dp, bottom = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = row.scroll
                    )
                }
                HorizontalDivider(color = BrandBorder.copy(alpha = 0.3f))
            }
        }
    }
}
