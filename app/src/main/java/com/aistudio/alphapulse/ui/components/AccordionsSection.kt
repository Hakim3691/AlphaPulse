package com.aistudio.alphapulse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.ChartPoint
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.domain.quant.MarkovRegimeResult
import com.aistudio.alphapulse.data.model.ScreenedCoin
import com.aistudio.alphapulse.ui.theme.*
import com.aistudio.alphapulse.core.Formatters
import java.util.Locale
import kotlin.math.abs

@Composable
fun AccordionsSection(
    selectedCoin: ScreenedCoin?,
    rsi: Double,
    macdSignal: String,
    markovRegime: MarkovRegimeResult?,
    netImbalanceUsdt: Double,
    bidRatioPct: Double,
    heatmapSource: String,
    forecastTargets: List<ForecastTarget>,
    chartPoints: List<ChartPoint>,
    executiveBrief: String
) {
    var openRiskAdjusted by remember { mutableStateOf(false) }
    var openMicrostructure by remember { mutableStateOf(false) }
    var openInstitutionalFilters by remember { mutableStateOf(false) }
    var openForecasts by remember { mutableStateOf(true) } // Open by default
    var openChart by remember { mutableStateOf(true) } // Open by default
    var openExecutiveBrief by remember { mutableStateOf(true) } // Open by default

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ACCORDION 1: 1-Year Risk Adjusted Metrics
        AccordionCard(
            title = "RISK ADJUSTED (1Y)",
            icon = Icons.Default.EmojiEvents,
            iconTint = BrandBullish,
            isOpen = openRiskAdjusted,
            onToggle = { openRiskAdjusted = !openRiskAdjusted }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Calmar",
                    value = selectedCoin?.calmarRatio?.let { String.format(Locale.US, "%.3f", it) } ?: "--",
                    color = BrandBullish
                )
                MetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Sharpe (Rf=4%)",
                    value = selectedCoin?.sharpeRatio?.let { String.format(Locale.US, "%.3f", it) } ?: "--",
                    color = BrandAccent
                )
                MetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Max DD",
                    value = selectedCoin?.maxDrawdown?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: "--",
                    color = TextPrimary
                )
                MetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Ann. Vol",
                    value = selectedCoin?.annualizedVol?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "--",
                    color = TextPrimary
                )
            }
        }

        // ACCORDION 2: Technical & Microstructure Diagnostics
        AccordionCard(
            title = "TECHNICAL & MICROSTRUCTURE DIAGNOSTICS",
            icon = Icons.Default.Memory,
            iconTint = BrandAccent,
            isOpen = openMicrostructure,
            onToggle = { openMicrostructure = !openMicrostructure }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticBox(
                        modifier = Modifier.weight(1f),
                        title = "RSI MOMENTUM",
                        value = String.format(Locale.US, "%.1f", rsi),
                        subtext = "14-period",
                        valueColor = if (rsi > 70) BrandBearish else if (rsi < 30) BrandBullish else TextPrimary
                    )
                    DiagnosticBox(
                        modifier = Modifier.weight(1f),
                        title = "MACD SIGNAL",
                        value = macdSignal,
                        subtext = "9/21 cross",
                        valueColor = if (macdSignal.contains("Bullish")) BrandBullish else BrandBearish
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticBox(
                        modifier = Modifier.weight(1f),
                        title = "MARKOV REGIME",
                        value = markovRegime?.regime ?: "Evaluating...",
                        subtext = markovRegime?.let { "Kurt: ${String.format(Locale.US, "%.2f", it.kurtosis)} (85p: ${String.format(Locale.US, "%.2f", it.cutoff)})" } ?: "Adaptive Kurtosis",
                        valueColor = if (markovRegime?.regime?.contains("Burst") == true) BrandBearish else BrandBullish
                    )
                    val formattedOFI = formatLargeCurrency(netImbalanceUsdt)
                    DiagnosticBox(
                        modifier = Modifier.weight(1f),
                        title = "L2 BOOK IMBALANCE",
                        value = if (netImbalanceUsdt >= 0) "+$$formattedOFI" else "-$$formattedOFI",
                        subtext = "${String.format(Locale.US, "%.1f", bidRatioPct)}% Bid vs ${String.format(Locale.US, "%.1f", 100 - bidRatioPct)}% Ask",
                        valueColor = if (netImbalanceUsdt >= 0) BrandBullish else BrandBearish
                    )
                }
            }
        }

        // ACCORDION 3: 4 Institutional Quality Filters
        AccordionCard(
            title = "INSTITUTIONAL QUALITY FILTERS",
            icon = Icons.Default.Verified,
            iconTint = BrandBullish,
            tag = "VERIFIED FLOW",
            isOpen = openInstitutionalFilters,
            onToggle = { openInstitutionalFilters = !openInstitutionalFilters }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Strict gate: Passes if ALL 4 filters align today OR passed at least once over the last 10 days",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterItemCard(
                        modifier = Modifier.weight(1f),
                        index = "1",
                        label = "$5M+ Volume",
                        value = selectedCoin?.filter1?.valueStr ?: "--",
                        passed = selectedCoin?.filter1?.passed == true
                    )
                    FilterItemCard(
                        modifier = Modifier.weight(1f),
                        index = "2",
                        label = "Taker Buy ≥50%",
                        value = selectedCoin?.filter2?.valueStr ?: "--",
                        passed = selectedCoin?.filter2?.passed == true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterItemCard(
                        modifier = Modifier.weight(1f),
                        index = "3",
                        label = "Vol > OI",
                        value = selectedCoin?.filter3?.valueStr ?: "--",
                        passed = selectedCoin?.filter3?.passed == true
                    )
                    FilterItemCard(
                        modifier = Modifier.weight(1f),
                        index = "4",
                        label = "Range Disp ≥40%",
                        value = selectedCoin?.filter4?.valueStr ?: "--",
                        passed = selectedCoin?.filter4?.passed == true
                    )
                }
            }
        }

        // ACCORDION 4: Tri-Factor Ensemble Target Forecasts
        AccordionCard(
            title = "TRI-FACTOR ENSEMBLE TARGET FORECASTS",
            icon = Icons.Default.GpsFixed,
            iconTint = BrandAccent,
            tag = heatmapSource,
            tagColor = if (heatmapSource == "API") BrandNeural else TextTertiary,
            isOpen = openForecasts,
            onToggle = { openForecasts = !openForecasts }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ML Integration: W_k (Kinematic) + W_p (LOB Pain) + W_α (FRVP 50-Bin / VWAP / UQ-LOB)",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                // 5 horizon cards in responsive grid or columns
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    forecastTargets.take(3).forEach { target ->
                        ForecastCard(modifier = Modifier.weight(1f), target = target)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    forecastTargets.drop(3).forEach { target ->
                        ForecastCard(modifier = Modifier.weight(1f), target = target)
                    }
                    if (forecastTargets.size < 5) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ACCORDION 5: Forecast Trajectory & Confidence Envelope
        AccordionCard(
            title = "FORECAST TRAJECTORY & CONFIDENCE ENVELOPE",
            icon = Icons.Default.Timeline,
            iconTint = BrandNeural,
            isOpen = openChart,
            onToggle = { openChart = !openChart }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Tri-factor mathematical diffusion, adaptive liquidity half-life traversal, and Auction Market POC alignment",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                ForecastTrajectoryChart(chartPoints = chartPoints)
            }
        }

        // ACCORDION 6: Quant Cognitive Brief (Needle Framework Institutional Brief)
        AccordionCard(
            title = "QUANT COGNITIVE BRIEF (NEEDLE AGENT)",
            icon = Icons.Default.AutoAwesome,
            iconTint = BrandNeural,
            tag = "AI SYNTHESIS",
            tagColor = BrandNeural,
            isOpen = openExecutiveBrief,
            onToggle = { openExecutiveBrief = !openExecutiveBrief }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrandCard)
                    .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = executiveBrief.ifBlank { "Initializing institutional execution synthesis..." },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun AccordionCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    tag: String? = null,
    tagColor: Color = BrandBullish,
    isOpen: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(targetValue = if (isOpen) 180f else 0f, label = "ChevronRotation")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (tag != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(tagColor.copy(alpha = 0.15f))
                            .border(1.dp, tagColor.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = tag,
                            color = tagColor,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Expand",
                tint = TextTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation)
            )
        }

        AnimatedVisibility(visible = isOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun MetricBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column {
            Text(text = label, color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(
                text = value,
                color = color,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DiagnosticBox(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtext: String,
    valueColor: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(
                text = value,
                color = valueColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(text = subtext, color = TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun FilterItemCard(
    modifier: Modifier = Modifier,
    index: String,
    label: String,
    value: String,
    passed: Boolean
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandBullish.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(index, color = BrandBullish, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (passed) BrandBullish.copy(alpha = 0.2f) else BrandBearish.copy(alpha = 0.2f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (passed) "PASS" else "FAIL",
                        color = if (passed) BrandBullish else BrandBearish,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = value,
                color = TextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ForecastCard(
    modifier: Modifier = Modifier,
    target: ForecastTarget
) {
    val deltaColor = if (target.isBullish) BrandBullish else BrandBearish
    val deltaBg = if (target.isBullish) GreenGlow else RedGlow

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = target.label,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(deltaBg)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${if (target.isBullish) "▲" else "▼"}${String.format(Locale.US, "%.2f", abs(target.deltaPct))}%",
                        color = deltaColor,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = target.modelType,
                color = BrandNeural,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = Formatters.formatPrice5Dec(target.predictedPrice),
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("+1σ: ${Formatters.formatPrice5Dec(target.upperPrice)}", color = TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text("-1σ: ${Formatters.formatPrice5Dec(target.lowerPrice)}", color = TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

fun formatLargeCurrency(value: Double): String {
    val absVal = abs(value)
    return when {
        absVal >= 1000000 -> String.format(Locale.US, "%.2fM", absVal / 1000000.0)
        absVal >= 1000 -> String.format(Locale.US, "%.1fk", absVal / 1000.0)
        else -> String.format(Locale.US, "%.0f", absVal)
    }
}
