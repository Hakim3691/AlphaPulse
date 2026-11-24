package com.aistudio.alphapulse.ui.components

/**
 * The trade decisions ledger: every §29 verdict the router recorded, graded.
 *
 * It used to be a second copy of the five newest verdicts inside the price ledger and
 * it never settled anything. Now it is its own board with the same header, horizon
 * filter and row grammar as the price and pattern ledgers, it renders only what is
 * still pending by default (settled rows are one toggle away), and each row reports
 * whether the call it made was right once the forecast it drove settled.
 *
 * One row is one verdict (C-29), so the newest pending verdict per horizon is the call
 * *still in force*: it says `IN FORCE` and shows the time its state began ("since
 * HH:mm:ss") instead of the same `PENDING` a stale row used to show. Any older row is
 * history — the verdict it recorded was already replaced.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, C-10, C-29
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.core.Formatters
import com.aistudio.alphapulse.data.repository.DecisionLedgerRow
import com.aistudio.alphapulse.domain.presentation.TokenizedReason
import com.aistudio.alphapulse.domain.presentation.VerdictInForce
import com.aistudio.alphapulse.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HORIZONS = listOf("ALL", "1m", "5m", "15m", "30m", "1h")

@Composable
fun DecisionsLedgerSection(
    rows: List<DecisionLedgerRow>,
    onClearClick: () -> Unit,
    horizonFilter: String = "ALL",
    onFilterChange: (String) -> Unit = {},
    onExportClick: () -> Unit = {},
    /** Settled verdicts are hidden by default; the toggle reveals them. */
    initiallyShowSettled: Boolean = false
) {
    var showSettled by remember { mutableStateOf(initiallyShowSettled) }

    val filteredRows = if (horizonFilter == "ALL") {
        rows
    } else {
        rows.filter { it.record.referenceHorizon == horizonFilter }
    }

    val pendingCount = filteredRows.count { !it.settled }
    val settledCount = filteredRows.count { it.settled }
    val hits = filteredRows.count { it.hit == true }
    val graded = filteredRows.count { it.settled }
    val visible = filteredRows.filter { showSettled || !it.settled }
    // The one verdict per horizon that is still the live call; every other row is the
    // history of a call that was replaced or already graded.
    val inForceIds = VerdictInForce.ids(filteredRows)

    val summary = if (graded == 0) "Graded: -- (0/0)" else
        "Graded: ${String.format(Locale.US, "%.1f", hits * 100.0 / graded)}% ($hits/$graded)"
    val summaryColor = if (graded == 0) TextTertiary else BrandBullish

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
    ) {
        LedgerHeader(
            title = "TRADE DECISIONS",
            pendingCount = pendingCount,
            settledCount = settledCount,
            summary = summary,
            summaryColor = summaryColor,
            showSettled = showSettled,
            onToggleSettled = { showSettled = !showSettled },
            onExportClick = onExportClick,
            onClearClick = onClearClick
        )

        HorizontalDivider(color = BrandBorder)

        // Horizon Filter row — the same six buttons, order and styling as the two
        // ledgers above, so the three tables move together.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HORIZON FILTER:",
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HORIZONS.forEach { h ->
                    val isSelected = horizonFilter == h
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) BrandAccent.copy(alpha = 0.2f) else BrandCard)
                            .border(
                                1.dp,
                                if (isSelected) BrandAccent else BrandBorder,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onFilterChange(h) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = h,
                            color = if (isSelected) BrandAccent else TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = BrandBorder.copy(alpha = 0.6f))

        // Responsive table: ASSET and HORIZON stay pinned, the verdict columns scroll
        // together on one shared state (C-35).
        val tableRows = visible.map { row ->
            val record = row.record
            val inForce = record.id in inForceIds
            LedgerPinnedRow(
                pinned = {
                    Text(
                        text = record.symbol.replace("USDT", ""),
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(60.dp)
                    )
                    Text(
                        text = record.referenceHorizon,
                        color = BrandNeural,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(65.dp)
                    )
                },
                scroll = {
                    Text(
                        text = DecisionLogText.stateLabel(record.state),
                        color = DecisionLogText.tintOf(record.state),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(96.dp)
                    )
                    Text(
                        text = DecisionLogText.confidenceLabel(record.confidence),
                        color = if (record.confidence > 0.0) BrandAccent else TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(60.dp)
                    )
                    Text(
                        text = DecisionLogText.sourceLabel(record.source),
                        color = if (DecisionLogText.answeredOnDevice(record.source)) BrandBullish else BrandAmber,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(80.dp)
                    )
                    // D1: one fixed-width token instead of the router's raw sentence,
                    // so model, action, score, gate and fallback cannot clip at 360dp.
                    Text(
                        text = TokenizedReason.of(record.source, record.state, record.confidence),
                        color = TextSecondary,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(240.dp)
                    )
                    Box(modifier = Modifier.width(90.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when (row.hit) {
                                        true -> GreenGlow
                                        false -> RedGlow
                                        null -> BrandCard
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = DecisionLogText.resultLabel(row, inForce),
                                color = when (row.hit) {
                                    true -> BrandBullish
                                    false -> BrandBearish
                                    null -> BrandAmber
                                },
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = row.realizedPrice?.let { Formatters.formatPrice5Dec(it) } ?: "--",
                        color = TextPrimary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(90.dp)
                    )
                    Text(
                        text = DecisionLogText.timeLabel(record.issuedTimestamp, inForce),
                        color = if (inForce) BrandAmber else TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(96.dp)
                    )
                }
            )
        }

        PinnedLedgerTable(
            pinnedWidth = 153.dp,
            pinnedHeader = {
                LedgerHeaderCell("ASSET", 60.dp)
                LedgerHeaderCell("HORIZON", 65.dp)
            },
            scrollHeader = {
                LedgerHeaderCell("STATE", 96.dp)
                LedgerHeaderCell("CONFIDENCE", 60.dp)
                LedgerHeaderCell("SOURCE", 80.dp)
                LedgerHeaderCell("REASON", 240.dp)
                LedgerHeaderCell("RESULT", 90.dp)
                LedgerHeaderCell("REALIZED", 90.dp)
                LedgerHeaderCell("TIME", 96.dp)
            },
            rows = tableRows,
            emptyContent = {
                Text(
                    text = if (filteredRows.isEmpty()) {
                        DecisionLogText.EMPTY
                    } else {
                        "No pending verdicts for this filter. $settledCount settled row(s) " +
                            "hidden — SHOW ALL to review them."
                    },
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        )
    }
}

internal object DecisionLogText {

    const val EMPTY: String = "No decisions recorded yet for this asset."

    fun stateLabel(state: String): String = state.replace('_', ' ')

    fun confidenceLabel(confidence: Double): String =
        if (confidence > 0.0) String.format(Locale.US, "%.2f", confidence) else "UNSCORED"

    fun sourceLabel(source: String): String = when (source) {
        "NEEDLE3" -> "NEEDLE 3"
        "DETERMINISTIC" -> "RULES"
        else -> source.ifBlank { "--" }
    }

    fun answeredOnDevice(source: String): Boolean = source == "NEEDLE3"

    /** What a still-pending verdict reads: live, or waiting behind a newer call. */
    const val IN_FORCE: String = "IN FORCE"

    /**
     * The graded outcome: the verdict's own hit, the fact that it is the call still in
     * force, or the fact that it is still waiting.
     */
    fun resultLabel(row: DecisionLedgerRow, inForce: Boolean = false): String = when (row.hit) {
        true -> "✓ HIT"
        false -> "✗ MISS"
        null -> if (inForce) IN_FORCE else "PENDING"
    }

    fun countLabel(total: Int, shown: Int): String =
        if (total == 0) "NONE RECORDED" else "NEWEST $shown OF $total"

    /**
     * A coalesced verdict keeps the timestamp its state began, so the live row says
     * `since` rather than presenting that start time as the row's age.
     */
    fun timeLabel(issuedTimestamp: Long, inForce: Boolean = false): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(issuedTimestamp))
        return if (inForce) "since $clock" else clock
    }

    fun reasonLabel(reason: String): String =
        reason.takeIf { it.isNotBlank() } ?: "No reason recorded."

    fun tintOf(state: String): Color = when (state) {
        "STRONG_LONG", "LONG" -> BrandBullish
        "STRONG_SHORT", "SHORT" -> BrandBearish
        "NEUTRAL" -> BrandAmber
        "NO_TRADE" -> TextSecondary
        else -> TextTertiary
    }
}
