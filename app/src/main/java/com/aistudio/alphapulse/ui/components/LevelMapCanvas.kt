package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.ZoneType
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary
import com.aistudio.alphapulse.ui.theme.VioletAccent

@Composable
fun LevelMapCanvas(
    levelMap: LevelMap?,
    currentPrice: Double,
    activeCampaign: Campaign?,
    modifier: Modifier = Modifier
) {
    if (levelMap == null) return

    val minPrice = (levelMap.zones.minOfOrNull { it.priceLow } ?: (currentPrice * 0.94)) * 0.99
    val maxPrice = (levelMap.zones.maxOfOrNull { it.priceHigh } ?: (currentPrice * 1.06)) * 1.01
    val priceRange = maxOf(0.01, maxPrice - minPrice)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "INTERACTIVE LEVEL MAP (HTF + LTF)",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent,
                    letterSpacing = 0.5.sp
                )
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Last: $$currentPrice",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = SlateTextPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(Color(0xFF090E1D), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                fun priceToY(price: Double): Float {
                    val normalized = (maxPrice - price) / priceRange
                    return (normalized * canvasHeight).toFloat().coerceIn(4f, canvasHeight - 4f)
                }

                // Draw background grid lines
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                for (i in 1..4) {
                    val y = canvasHeight * (i / 5f)
                    drawLine(
                        color = Color(0xFF1A263D),
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        pathEffect = dashEffect,
                        strokeWidth = 1f
                    )
                }

                // Render Level Zones
                levelMap.zones.forEach { zone ->
                    val yTop = priceToY(zone.priceHigh)
                    val yBottom = priceToY(zone.priceLow)
                    val zoneHeight = maxOf(4f, yBottom - yTop)

                    val color = when (zone.zoneType) {
                        ZoneType.HTF_RESISTANCE -> CrimsonBear
                        ZoneType.HTF_SUPPORT -> EmeraldBull
                        ZoneType.RECLAIM_ZONE -> VioletAccent
                        ZoneType.LTF_PIVOT -> CyanAccent
                        ZoneType.BREAKDOWN_ZONE -> Color(0xFFFF8A80)
                        ZoneType.STOP_POOL -> Color(0xFFFFD54F)
                    }

                    // Zone fill band
                    drawRect(
                        color = color.copy(alpha = 0.18f),
                        topLeft = Offset(0f, yTop),
                        size = Size(canvasWidth * 0.75f, zoneHeight)
                    )
                    // Zone boundary line
                    drawLine(
                        color = color.copy(alpha = 0.7f),
                        start = Offset(0f, yTop),
                        end = Offset(canvasWidth * 0.75f, yTop),
                        strokeWidth = 1.5f
                    )
                }

                // Render Resting Ladder Slices for active campaign
                activeCampaign?.entryLadder?.forEach { slice ->
                    val ySlice = priceToY(slice.price)
                    val sliceColor = if (slice.isFilled) Color(0xFF00E676) else Color(0xFFFFD600)
                    drawLine(
                        color = sliceColor,
                        start = Offset(canvasWidth * 0.2f, ySlice),
                        end = Offset(canvasWidth * 0.85f, ySlice),
                        strokeWidth = 2f,
                        pathEffect = if (slice.isFilled) null else PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f)
                    )
                }

                // Render Current Price Line
                val yPrice = priceToY(currentPrice)
                drawLine(
                    color = Color.White,
                    start = Offset(0f, yPrice),
                    end = Offset(canvasWidth, yPrice),
                    strokeWidth = 2.5f
                )
                drawCircle(
                    color = CyanAccent,
                    radius = 4f,
                    center = Offset(canvasWidth - 12f, yPrice)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Level legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendIndicator(color = CrimsonBear, label = "Supply/Resist")
            Spacer(modifier = Modifier.width(8.dp))
            LegendIndicator(color = EmeraldBull, label = "Support Shelf")
            Spacer(modifier = Modifier.width(8.dp))
            LegendIndicator(color = VioletAccent, label = "Reclaim Zone")
            Spacer(modifier = Modifier.width(8.dp))
            LegendIndicator(color = Color(0xFFFFD600), label = "Resting Ladder")
        }
    }
}

@Composable
private fun LegendIndicator(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(3.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = SlateTextMuted,
                fontSize = 9.sp
            )
        )
    }
}
