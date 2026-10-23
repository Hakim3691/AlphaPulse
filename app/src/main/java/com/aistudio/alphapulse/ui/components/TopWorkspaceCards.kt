package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.domain.ensemble.EnsembleWeighting
import com.aistudio.alphapulse.data.model.ScreenedCoin
import com.aistudio.alphapulse.ui.theme.*
import com.aistudio.alphapulse.core.Formatters
import java.util.Locale

@Composable
fun TopWorkspaceCards(
    screenedCoins: List<ScreenedCoin>,
    selectedSymbol: String,
    under10Only: Boolean,
    onSelectCoin: (String) -> Unit,
    onToggleUnder10: (Boolean) -> Unit,
    spotPrice: Double,
    spotChange: Double,
    high24h: Double,
    low24h: Double,
    modelState: ModelState,
    totalSettled: Int,
    coneHitRate: Double?,
    mountedModelIds: List<String> = emptyList()
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Row 1: Screened Universe Selector & Spot Price
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ScreenedUniverseCard(
                modifier = Modifier.weight(1.3f),
                screenedCoins = screenedCoins,
                selectedSymbol = selectedSymbol,
                under10Only = under10Only,
                onSelectCoin = onSelectCoin,
                onToggleUnder10 = onToggleUnder10
            )

            SpotPriceCard(
                modifier = Modifier.weight(1f),
                spotPrice = spotPrice,
                spotChange = spotChange,
                high24h = high24h,
                low24h = low24h
            )
        }

        // Row 2: ML Ensemble Weights (Always Visible)
        MlEnsembleWeightsCard(
            modelState = modelState,
            totalSettled = totalSettled,
            coneHitRate = coneHitRate,
            mountedModelIds = mountedModelIds
        )
    }
}

@Composable
fun ScreenedUniverseCard(
    modifier: Modifier = Modifier,
    screenedCoins: List<ScreenedCoin>,
    selectedSymbol: String,
    under10Only: Boolean,
    onSelectCoin: (String) -> Unit,
    onToggleUnder10: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SCREENED UNIVERSE",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                // <= $10 toggle chip
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (under10Only) BrandAccent.copy(alpha = 0.2f) else BrandCard)
                        .border(1.dp, if (under10Only) BrandAccent.copy(alpha = 0.4f) else BrandBorder, RoundedCornerShape(4.dp))
                        .clickable { onToggleUnder10(!under10Only) }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (under10Only) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = BrandAccent,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Text(
                        text = "≤ $10 Only",
                        color = if (under10Only) BrandAccent else TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Dropdown trigger button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrandCard)
                    .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayLabel = if (selectedSymbol.isNotBlank()) {
                        val c = screenedCoins.find { it.symbol == selectedSymbol }
                        val p = c?.lastPrice ?: 0.0
                        "${selectedSymbol.replace("USDT", "")} (${formatPrice(p)})"
                    } else if (screenedCoins.isEmpty()) {
                        "Scanning Universe..."
                    } else {
                        "Select Coin..."
                    }

                    Text(
                        text = displayLabel,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Open Dropdown",
                        tint = TextTertiary
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(BrandSurface)
                        .border(1.dp, BrandBorder)
                ) {
                    screenedCoins.forEachIndexed { idx, coin ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${idx + 1}. ${coin.symbol.replace("USDT", "")} (${formatPrice(coin.lastPrice)})",
                                    color = if (coin.symbol == selectedSymbol) BrandAccent else TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            },
                            onClick = {
                                onSelectCoin(coin.symbol)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Calmar > 0 & Sharpe > 0",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${screenedCoins.size} Verified",
                    color = BrandAccent,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SpotPriceCard(
    modifier: Modifier = Modifier,
    spotPrice: Double,
    spotChange: Double,
    high24h: Double,
    low24h: Double
) {
    val isBull = spotChange >= 0
    val changeColor = if (isBull) BrandBullish else BrandBearish
    val changeBg = if (isBull) GreenGlow else RedGlow

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "SPOT PRICE",
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatPrice(spotPrice),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(changeBg)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${if (isBull) "+" else ""}${String.format(Locale.US, "%.2f", spotChange)}%",
                        color = changeColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("High", color = TextTertiary, fontSize = 9.sp)
                    Text(formatPrice(high24h), color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Column {
                    Text("Low", color = TextTertiary, fontSize = 9.sp)
                    Text(formatPrice(low24h), color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun MlEnsembleWeightsCard(
    modelState: ModelState,
    totalSettled: Int,
    coneHitRate: Double?,
    mountedModelIds: List<String> = emptyList()
) {
    val weightedMembers = EnsembleWeighting.weightVector(modelState, mountedModelIds)
    val totalWeight = weightedMembers.sumOf { it.weight }
    val modelCount = weightedMembers.size
    // 3-4 members flex to the card width; 5 members keep their width and scroll.
    val scrollable = modelCount > 4
    val memberColors = listOf(BrandAmber, BrandNeural, BrandAccent, Color(0xFF22D3EE), BrandBullish)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ML ENSEMBLE WEIGHTS",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BrandCard)
                        .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = modelState.mode,
                        color = if (modelState.mode.contains("SGD")) BrandAccent else TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                weightedMembers.forEachIndexed { index, member ->
                    EnsembleWeightChip(
                        modifier = if (scrollable) Modifier.width(66.dp) else Modifier.weight(1f),
                        weightSymbol = member.member.weightSymbol,
                        modelLabel = member.member.modelLabel,
                        weight = member.weight,
                        accent = memberColors[index % memberColors.size]
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MODELS $modelCount · \u03a3W " +
                        String.format(Locale.US, "%.1f%%", totalWeight * 100.0),
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "VALID $totalSettled",
                        color = TextTertiary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                    Text(
                        text = "CONE " + (
                            if (coneHitRate != null) String.format(Locale.US, "%.1f%%", coneHitRate) else "--"
                        ),
                        color = BrandBullish,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EnsembleWeightChip(
    modifier: Modifier,
    weightSymbol: String,
    modelLabel: String,
    weight: Double,
    accent: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(BrandCard)
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = weightSymbol,
            color = accent,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = modelLabel,
            color = TextTertiary,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = String.format(Locale.US, "%.3f", weight),
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

fun formatPrice(value: Double): String = Formatters.formatPrice5Dec(value)
