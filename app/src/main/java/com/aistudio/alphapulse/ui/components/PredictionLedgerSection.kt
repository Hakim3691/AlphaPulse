package com.aistudio.alphapulse.ui.components

/**
 * The audit ledger of every prediction the app has made.
 *
 * Each row keeps the forecast as issued, so it can later be compared with what
 * really happened. Nothing is edited after the fact.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §38 Export System
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.domain.presentation.HorizonGroups
import com.aistudio.alphapulse.ui.theme.*
import com.aistudio.alphapulse.core.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

@Composable
fun PredictionLedgerSection(
    predictions: List<PredictionEntity>,
    horizonFilter: String,
    onFilterChange: (String) -> Unit,
    onClearClick: () -> Unit,
    onExportClick: () -> Unit,
    /** Settled rows are hidden by default; the toggle reveals them. */
    initiallyShowSettled: Boolean = false
) {
    var showSettled by remember { mutableStateOf(initiallyShowSettled) }

    val pendingCount = predictions.count { !it.settled }
    val settledCount = predictions.count { it.settled }

    val filteredList = if (horizonFilter == "ALL") {
        predictions
    } else {
        predictions.filter { it.horizonId == horizonFilter }
    }
    // The ledger is a working list: a settled row has already been graded, so it only
    // appears when the user asks for it.
    val visibleList = filteredList.filter { showSettled || !it.settled }

    val filteredSettled = filteredList.filter { it.settled }
    val filteredHit = filteredList.count { it.settled && it.inCone == true }
    val hitRateText = if (filteredSettled.isEmpty()) "Hit: -- (0/0)" else {
        val rate = (filteredHit.toDouble() / filteredSettled.size) * 100.0
        "Hit: ${String.format(Locale.US, "%.1f", rate)}% ($filteredHit/${filteredSettled.size})"
    }
    val hitRateColor = if (filteredSettled.isEmpty()) TextTertiary else if (
        (filteredHit.toDouble() / filteredSettled.size) >= 0.6
    ) BrandBullish else BrandAmber

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
    ) {
        LedgerHeader(
            title = "PRICE PREDICTION",
            pendingCount = pendingCount,
            settledCount = settledCount,
            summary = hitRateText,
            summaryColor = hitRateColor,
            showSettled = showSettled,
            onToggleSettled = { showSettled = !showSettled },
            onExportClick = onExportClick,
            onClearClick = onClearClick
        )

        HorizontalDivider(color = BrandBorder)

        // Horizon Filter row
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
                listOf("ALL", "1m", "5m", "15m", "30m", "1h").forEach { h ->
                    val isSelected = horizonFilter == h
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) BrandAccent.copy(alpha = 0.2f) else BrandCard)
                            .border(1.dp, if (isSelected) BrandAccent else BrandBorder, RoundedCornerShape(4.dp))
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

        // §3 addition: name what the two horizon camps are for, so the filter is
        // not a row of unexplained numbers.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf("TACTICAL", "STRATEGIC").forEach { group ->
                Text(
                    text = HorizonGroups.captionOf(group),
                    color = TextTertiary,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = BrandBorder.copy(alpha = 0.6f))

        // Responsive table: ASSET and HORIZON stay pinned, the remaining columns
        // scroll together on one shared state, so no header is clipped at 360dp.
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val tableRows = visibleList.map { item ->
            val diff = max(0L, item.targetTimestamp - currentTime)
            val m = (diff / 60000).toString().padStart(2, '0')
            val s = ((diff % 60000) / 1000).toString().padStart(2, '0')
            LedgerPinnedRow(
                pinned = {
                    Text(
                        text = item.symbol.replace("USDT", ""),
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(60.dp)
                    )
                    Text(
                        text = item.horizonLabel,
                        color = BrandNeural,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(65.dp)
                    )
                },
                scroll = {
                    Text(
                        text = timeFmt.format(Date(item.targetTimestamp)),
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(75.dp)
                    )
                    Text(
                        text = Formatters.formatPrice5Dec(item.predictedPrice),
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(75.dp)
                    )
                    Text(
                        text = item.realizedPrice?.let { Formatters.formatPrice5Dec(it) } ?: "--",
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(75.dp)
                    )
                    val devText = if (item.settled && item.errorPct != null) {
                        val sign = if (item.errorPct >= 0) "+" else ""
                        "$sign${String.format(Locale.US, "%.2f", item.errorPct)}%"
                    } else "--"
                    val devColor = if (item.settled && (item.errorPct ?: 0.0) >= 0) BrandBullish else BrandBearish
                    Text(
                        text = devText,
                        color = devColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(85.dp)
                    )
                    Box(modifier = Modifier.width(90.dp)) {
                        if (item.settled) {
                            val hit = item.inCone == true
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (hit) GreenGlow else RedGlow)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (hit) "\u2713 In Cone" else "\u2717 Breach",
                                    color = if (hit) BrandBullish else BrandBearish,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            val countdown = if (diff == 0L) "Validating..." else "In $m:$s"
                            Text(
                                text = countdown,
                                color = BrandAmber,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = item.learningAction,
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(130.dp)
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
                LedgerHeaderCell("TARGET TIME", 75.dp)
                LedgerHeaderCell("PREDICTED", 75.dp)
                LedgerHeaderCell("REALIZED", 75.dp)
                LedgerHeaderCell("DEVIATION", 85.dp)
                LedgerHeaderCell("CONE STATUS", 90.dp)
                LedgerHeaderCell("SGD LEARNING ACTION", 130.dp)
            },
            rows = tableRows,
            emptyContent = {
                Text(
                    text = if (filteredList.isEmpty()) {
                        "No predictions logged yet. Predictions settle automatically via online SGD."
                    } else {
                        "No pending predictions for this filter. $settledCount settled row(s) hidden \u2014 SHOW ALL to review them."
                    },
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        )
    }
}
