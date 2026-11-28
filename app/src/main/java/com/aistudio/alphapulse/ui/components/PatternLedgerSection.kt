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
import androidx.compose.material.icons.filled.Share
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
import com.aistudio.alphapulse.domain.catalogue.CatalogueLedger
import com.aistudio.alphapulse.domain.catalogue.CatalogueLedgerInfo
import com.aistudio.alphapulse.domain.catalogue.TradeIdea
import com.aistudio.alphapulse.domain.catalogue.TradeIdeasCatalog
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEngine
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEntry
import com.aistudio.alphapulse.domain.presentation.SgdActionTag
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
    onExportClick: () -> Unit,
    onClearClick: () -> Unit = {},
    /** Settled rows are hidden by default; the toggle reveals them. */
    initiallyShowSettled: Boolean = false,
    /** C-66: the catalogue the pattern ledger grades, shown from its info action. */
    catalogueIdeas: List<TradeIdea> = emptyList()
) {
    var showSettled by remember { mutableStateOf(initiallyShowSettled) }
    var catalogueOpen by remember { mutableStateOf(false) }
    val catalogue = catalogueIdeas.ifEmpty { TradeIdeasCatalog.all }

    val filteredList = if (horizonFilter == "ALL") {
        entries
    } else {
        entries.filter { it.horizon == horizonFilter }
    }
    // The ledger is a working list: settled rows are already graded, so they only
    // appear when the user asks for them.
    val visibleList = filteredList.filter { showSettled || !it.settled }
    val pendingCount = filteredList.count { !it.settled }
    val settledCount = filteredList.count { it.settled }
    // A hit is a settled row whose realized geometry matched the prediction. The
    // ledger's `inCone` flag is not persisted, so reading it here reported 0% forever.
    val hitCount = filteredList.count { it.settled && it.realizedPattern == it.predictedPatternName }
    // C-57: the rate is measured over settled rows only. Dividing by every issued row
    // mixed ungraded predictions into the denominator and reported a hit rate no settled
    // sample could produce.
    val settledRows = filteredList.count { it.settled }
    val hitRateText = if (settledRows == 0)
        "Hit: -- (0/0)"
    else {
        val rate = (hitCount.toDouble() / settledRows) * 100.0
        "Hit: ${String.format(Locale.US, "%.1f", rate)}% ($hitCount/$settledRows)"
    }

    val hitRateColor = if (settledRows == 0) TextTertiary else if (
        (hitCount.toDouble() / settledRows) >= 0.85
    ) BrandBullish else BrandAmber

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val scrollState = rememberScrollState()

    if (catalogueOpen) {
        CatalogueInfoDialog(
            isOpen = true,
            ledger = CatalogueLedger.PATTERN,
            ideas = CatalogueLedgerInfo.ideasFor(CatalogueLedger.PATTERN, catalogue),
            onDismiss = { catalogueOpen = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
    ) {
        LedgerHeader(
            title = "PATTERN PREDICTION",
            onInfoClick = { catalogueOpen = true },
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
                            .background(if (isSelected) BrandNeural.copy(alpha = 0.2f) else BrandCard)
                            .border(1.dp, if (isSelected) BrandNeural else BrandBorder, RoundedCornerShape(4.dp))
                            .clickable { onFilterChange(h) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = h,
                            color = if (isSelected) BrandNeural else TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = BrandBorder.copy(alpha = 0.6f))

        // What the engine concluded before the current frame. The verdicts are per
        // snapshot, so they are shown above the horizon filter's
        // table rather than inside it.

        // Responsive table: ASSET and HORIZON stay pinned, the measurement and
        // learning columns scroll together, so headers cannot clip at 360dp.
        val tableRows = visibleList.map { entry ->
            LedgerPinnedRow(
                pinned = {
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
                },
                scroll = {
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
                        // D1/§3: operational tags instead of raw debug prose.
                        text = SgdActionTag.of(entry.sgdAction, entry.realizedPattern),
                        color = when {
                            entry.sgdAction.contains("+w_reinforce") -> BrandBullish
                            entry.sgdAction.contains("w_penalty") -> BrandBearish
                            else -> TextTertiary
                        },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(175.dp)
                    )
                    Text(
                        text = forecastText(entry),
                        color = when (entry.tsfmAligned) {
                            true -> BrandBullish
                            false -> BrandBearish
                            null -> TextTertiary
                        },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(150.dp)
                    )
                }
            )
        }

        PinnedLedgerTable(
            pinnedWidth = 148.dp,
            pinnedHeader = {
                LedgerHeaderCell("ASSET", 55.dp)
                LedgerHeaderCell("HORIZON", 65.dp)
            },
            scrollHeader = {
                LedgerHeaderCell("TARGET TIME", 75.dp)
                LedgerHeaderCell("PREDICTED PATTERN", 160.dp)
                LedgerHeaderCell("REALIZED PATTERN", 150.dp)
                LedgerHeaderCell("FIT / LOSS", 75.dp)
                LedgerHeaderCell("STATUS", 90.dp)
                LedgerHeaderCell("SGD LEARNING ACTION", 175.dp)
                LedgerHeaderCell("TSFM ENSEMBLE", 150.dp)
            },
            rows = tableRows,
            emptyContent = {
                Text(
                    text = if (filteredList.isEmpty()) {
                        "No pattern predictions logged yet. Rows settle at the horizon " +
                            "boundary and train the online SGD head."
                    } else {
                        "No pending pattern rows for this filter. $settledCount settled " +
                            "row(s) hidden \u2014 SHOW ALL to review them."
                    },
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        )
    }
}

/**
 * The TSFM evidence column: which mounted models spoke, their weighted move and whether
 * they confirmed or contradicted the formation. No model means no claim is made.
 */
private fun forecastText(entry: PatternLedgerEntry): String {
    if (entry.tsfmProvider.isEmpty()) return "no model"
    val drift = String.format(Locale.US, "%+.2f%%", entry.tsfmDriftPct)
    val verdict = when (entry.tsfmAligned) {
        true -> "✓ aligned"
        false -> "✗ opposed"
        null -> "— no opinion"
    }
    return "${entry.tsfmProvider} $drift $verdict"
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
