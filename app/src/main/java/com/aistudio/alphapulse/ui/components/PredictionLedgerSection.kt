package com.aistudio.alphapulse.ui.components

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
    onExportUniverseClick: () -> Unit = {}
) {
    val pendingCount = predictions.count { !it.settled }
    val settledCount = predictions.count { it.settled }

    val filteredList = if (horizonFilter == "ALL") {
        predictions
    } else {
        predictions.filter { it.horizonId == horizonFilter }
    }

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
        // Top Toolbar (two-line header: title + counters, then filter-scoped cone hit rate)
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
                        text = "PREDICTION AUDIT LEDGER",
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

            // Action buttons row (second toolbar line of the two-line header)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter-scoped cone hit rate (uses the active horizon filter, settled rows only)
                val filteredSettled = filteredList.filter { it.settled }
                val filteredHit = filteredList.count { it.settled && it.inCone == true }
                val hitRateText = if (filteredSettled.isEmpty())
                    "Hit: -- (0/0)"
                else {
                    val rate = (filteredHit.toDouble() / filteredSettled.size) * 100.0
                    "Hit: ${String.format(Locale.US, "%.1f", rate)}% ($filteredHit/${filteredSettled.size})"
                }
                Text(
                    text = hitRateText,
                    color = if (filteredSettled.isEmpty()) TextTertiary else if (
                        (filteredHit.toDouble() / filteredSettled.size) >= 0.6
                    ) BrandBullish else BrandAmber,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // 4-Tab Universe CSV Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BrandCard)
                        .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
                        .clickable { onExportUniverseClick() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export 4-Tab Universe CSV",
                            tint = BrandBullish,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "UNIVERSE",
                            color = BrandBullish,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Prediction Audit CSV Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BrandCard)
                        .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
                        .clickable { onExportClick() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                        contentDescription = "Export Prediction Ledger CSV",
                        tint = BrandAccent,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "LEDGER",
                            color = BrandAccent,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Clear Ledger Button
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
                        contentDescription = "Clear Ledger",
                        tint = BrandBearish,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }

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

        HorizontalDivider(color = BrandBorder.copy(alpha = 0.6f))

        // Table Header & Rows in horizontal scroll
        val scrollState = rememberScrollState()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .background(BrandCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ASSET", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                Text("HORIZON", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(65.dp))
                Text("TARGET TIME", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(75.dp))
                Text("PREDICTED", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(75.dp))
                Text("REALIZED", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(75.dp))
                Text("DEVIATION", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(85.dp))
                Text("CONE STATUS", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                Text("SGD LEARNING ACTION", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
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
                        text = "No predictions logged yet. Predictions settle automatically via online SGD.",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                filteredList.forEach { item ->
                    val diff = max(0L, item.targetTimestamp - currentTime)
                    val m = (diff / 60000).toString().padStart(2, '0')
                    val s = ((diff % 60000) / 1000).toString().padStart(2, '0')

                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        // Deviation
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

                        // Cone Status
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
                                        text = if (hit) "✓ In Cone" else "✗ Breach",
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

                        // Learning action
                        Text(
                            text = item.learningAction,
                            color = TextTertiary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(130.dp)
                        )
                    }

                    HorizontalDivider(color = BrandBorder.copy(alpha = 0.3f))
                }
            }
        }
    }
}
