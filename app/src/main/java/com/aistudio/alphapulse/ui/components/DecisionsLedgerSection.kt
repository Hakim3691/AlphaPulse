package com.aistudio.alphapulse.ui.components

/**
 * The decisions ledger: every §29 verdict the router recorded, graded.
 *
 * It used to be a second copy of the five newest verdicts inside the price ledger and
 * it never settled anything. Now it is its own board, it renders only what is still
 * pending by default (settled rows are one toggle away), and each row reports whether
 * the call it made was right once the forecast it drove settled.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, C-10, C-29
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.core.Formatters
import com.aistudio.alphapulse.data.repository.DecisionLedgerRow
import com.aistudio.alphapulse.domain.presentation.TokenizedReason
import com.aistudio.alphapulse.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DecisionsLedgerSection(
    rows: List<DecisionLedgerRow>,
    onClearClick: () -> Unit,
    /** Settled verdicts are hidden by default; the toggle reveals them. */
    initiallyShowSettled: Boolean = false
) {
    var showSettled by remember { mutableStateOf(initiallyShowSettled) }

    val pendingCount = rows.count { !it.settled }
    val settledCount = rows.count { it.settled }
    val hits = rows.count { it.hit == true }
    val graded = rows.count { it.settled }
    val visible = rows.filter { showSettled || !it.settled }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
    ) {
        // Two-line header, formatted like the price and pattern ledgers: title + counters,
        // then the actions line.
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 12.dp, end = 12.dp, bottom = 4.dp)) {
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
                        text = "DECISIONS LEDGER",
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
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (graded == 0) "Graded: -- (0/0)" else
                        "Graded: ${String.format(Locale.US, "%.1f", hits * 100.0 / graded)}% ($hits/$graded)",
                    color = if (graded == 0) TextTertiary else BrandBullish,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                LedgerSettledToggle(showSettled = showSettled, onToggle = { showSettled = !showSettled })

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BrandCard)
                        .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
                        .clickable(onClick = onClearClick)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Decisions Ledger",
                            tint = BrandBearish,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "CLEAR LEDGER",
                            color = BrandBearish,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = BrandBorder)

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) {
            Row(
                modifier = Modifier
                    .background(BrandCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ASSET", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                Text("STATE", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                Text("CONFIDENCE", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                Text("SOURCE", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                Text("REASON", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(240.dp))
                Text("RESULT", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                Text("REALIZED", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                Text("TIME", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
            }

            HorizontalDivider(color = BrandBorder)

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = DecisionLogText.EMPTY,
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No pending verdicts. $settledCount settled row(s) hidden — SHOW SETTLED to review them.",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                visible.forEach { row ->
                    val record = row.record
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = record.symbol.replace("USDT", ""),
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(60.dp)
                        )
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
                        // D1: one fixed-width token instead of the router's raw
                        // sentence, so model, action, score, gate and fallback
                        // cannot clip at 360dp.
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
                                    text = DecisionLogText.resultLabel(row),
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
                            text = timeFmt.format(Date(record.issuedTimestamp)),
                            color = TextTertiary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(70.dp)
                        )
                    }

                    HorizontalDivider(color = BrandBorder.copy(alpha = 0.3f))
                }
            }
        }
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

    /** The graded outcome: the verdict's own hit, or the fact that it is still waiting. */
    fun resultLabel(row: DecisionLedgerRow): String = when (row.hit) {
        true -> "✓ HIT"
        false -> "✗ MISS"
        null -> "PENDING"
    }

    fun countLabel(total: Int, shown: Int): String =
        if (total == 0) "NONE RECORDED" else "NEWEST $shown OF $total"

    fun timeLabel(issuedTimestamp: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(issuedTimestamp))

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
