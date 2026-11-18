package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color

@Composable
fun DecisionsLedgerSection(
    decisions: List<DecisionRecordEntity>,
    onClearClick: () -> Unit
) {
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    // Header with title and count
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
    ) {
        // Top Toolbar: title + count + clear button
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
                        text = "${decisions.size} RECORDED",
                        color = BrandAmber,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Clear Ledger Button (full width, replaces old inline clear)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(BrandCard)
                    .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
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

        HorizontalDivider(color = BrandBorder)

        // Table Header & Rows in horizontal scroll
        val scrollState = rememberScrollState()
        val timeFmt2 = SimpleDateFormat("HH:mm:ss", Locale.US)

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
                Text("STATE", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                Text("CONFIDENCE", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                Text("SOURCE", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                Text("REASON", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(180.dp))
                Text("TIME", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
            }

            HorizontalDivider(color = BrandBorder)

            if (decisions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No decisions recorded yet for this asset.",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                decisions.forEach { record ->
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
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = DecisionLogText.reasonLabel(record.reason),
                            color = TextSecondary,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Text(
                            text = timeFmt2.format(Date(record.issuedTimestamp)),
                            color = TextTertiary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
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