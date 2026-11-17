package com.aistudio.alphapulse.ui.components

/**
 * The secondary Pattern Prediction & Learning Ledger (§73).
 *
 * Mounted directly below the price ledger, sharing its asset scope and its horizon
 * filter — the two tables count down to the same phase-locked boundaries. Each row is
 * frozen when it is issued and only ever gains a realized pattern, a cross-entropy loss
 * and an SGD callback once its bar closes.
 *
 * Spec Ref: §73 Pattern Prediction & Learning Ledger, Directive §1 Dual Ledger
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEngine
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEntry
import com.aistudio.alphapulse.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PatternLedgerSection(
    entries: List<PatternLedgerEntry>,
    horizonFilter: String,
    onFilterChange: (String) -> Unit,
    onClearClick: () -> Unit
) {
    val filteredList = if (horizonFilter == "ALL") {
        entries
    } else {
        entries.filter { it.horizon == horizonFilter }
    }
    val pendingCount = filteredList.count { !it.settled }
    val settledCount = filteredList.count { it.settled }

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
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
                        tint = BrandNeural,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "PATTERN PREDICTION & LEARNING LEDGER",
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
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BrandCard)
                            .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
                            .clickable { onClearClick() }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Pattern Ledger",
                            tint = BrandBearish,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }

            // The same filter as the price ledger above: one selection drives both clocks.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HORIZON FILTER (SYNCED):",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("ALL", "1m", "5m", "15m", "30m", "1h").forEach { horizon ->
                        val isSelected = horizonFilter == horizon
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) BrandNeural.copy(alpha = 0.2f) else BrandCard)
                                .border(
                                    1.dp,
                                    if (isSelected) BrandNeural else BrandBorder,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onFilterChange(horizon) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = horizon,
                                color = if (isSelected) BrandNeural else TextSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = BrandBorder)

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
                header("ASSET", 55.dp)
                header("HORIZON", 65.dp)
                header("TARGET TIME", 75.dp)
                header("PREDICTED PATTERN", 160.dp)
                header("REALIZED PATTERN", 150.dp)
                header("FIT / LOSS", 75.dp)
                header("STATUS", 90.dp)
                header("SGD LEARNING ACTION", 175.dp)
            }

            HorizontalDivider(color = BrandBorder)

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No pattern predictions logged yet. Rows settle at the horizon " +
                            "boundary and train the online SGD head.",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                filteredList.forEach { entry ->
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.asset,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(55.dp)
                        )
                        Text(
                            text = entry.horizon,
                            color = BrandNeural,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(65.dp)
                        )
                        Text(
                            text = timeFmt.format(Date(entry.targetTime)),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(75.dp)
                        )
                        Text(
                            text = "${entry.predictedPatternName} " +
                                "(${String.format(Locale.US, "%.0f", entry.confidence * 100)}%)",
                            color = categoryColor(entry.predictedCategory),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(160.dp)
                        )
                        Text(
                            text = entry.realizedPattern ?: "Pending",
                            color = if (entry.settled) TextPrimary else TextTertiary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(150.dp)
                        )
                        Text(
                            text = entry.fitLoss?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                            color = when {
                                entry.fitLoss == null -> TextTertiary
                                (entry.fitLoss ?: 0.0) <= 0.70 -> BrandBullish
                                else -> BrandBearish
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(75.dp)
                        )
                        Text(
                            text = PatternLedgerEngine.statusLabel(entry, currentTime),
                            color = when {
                                entry.settled -> BrandBullish
                                currentTime >= entry.targetTime -> BrandNeural
                                else -> BrandAmber
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            text = entry.sgdAction,
                            color = when {
                                entry.sgdAction.contains("+w_reinforce") -> BrandBullish
                                entry.sgdAction.contains("- w_penalty") -> BrandBearish
                                else -> TextTertiary
                            },
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(175.dp)
                        )
                    }

                    HorizontalDivider(color = BrandBorder.copy(alpha = 0.3f))
                }
            }
        }
    }
}

/** Colour a formation by the direction it implies, not by its label. */
private fun categoryColor(category: String) = when (category) {
    "Bullish Reversal" -> BrandBullish
    "Bearish Reversal" -> BrandBearish
    else -> BrandAccent
}

@Composable
private fun header(label: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = label,
        color = TextTertiary,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(width)
    )
}
