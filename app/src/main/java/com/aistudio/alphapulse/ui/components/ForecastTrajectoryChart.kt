package com.aistudio.alphapulse.ui.components

/**
 * Draws the forecast cone: expected price plus its probability band.
 *
 * The middle line is the expected price and the shaded band is the forecast
 * range, so a user can see at a glance how uncertain each horizon is.
 *
 * Spec Ref: §18 Probabilistic Forecasting, §40 Primary Dashboard
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.ChartPoint
import com.aistudio.alphapulse.ui.theme.*
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Composable
fun ForecastTrajectoryChart(
    chartPoints: List<ChartPoint>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        if (chartPoints.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Awaiting candle tape & telemetry...",
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            return@Box
        }

        // Top Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = BrandAccent, label = "Realized 1m", dashed = false)
            LegendItem(color = BrandNeural, label = "Tri-Factor Path", dashed = true)
            LegendItem(color = BrandNeural.copy(alpha = 0.5f), label = "+1σ Upper Cone", dashed = true)
            LegendItem(color = BrandBearish.copy(alpha = 0.5f), label = "-1σ Lower Cone", dashed = true)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 22.dp, bottom = 18.dp, start = 4.dp, end = 52.dp)
        ) {
            val width = size.width
            val height = size.height

            // Calculate min/max bounds across all points
            var minP = Double.POSITIVE_INFINITY
            var maxP = Double.NEGATIVE_INFINITY

            for (p in chartPoints) {
                p.realizedPrice?.let {
                    if (it < minP) minP = it
                    if (it > maxP) maxP = it
                }
                p.forecastPrice?.let {
                    if (it < minP) minP = it
                    if (it > maxP) maxP = it
                }
                p.upperCone?.let {
                    if (it < minP) minP = it
                    if (it > maxP) maxP = it
                }
                p.lowerCone?.let {
                    if (it < minP) minP = it
                    if (it > maxP) maxP = it
                }
            }

            if (minP.isInfinite() || maxP.isInfinite() || minP == maxP) {
                minP = 1.0
                maxP = 2.0
            }

            val priceRange = max(1e-6, maxP - minP)
            val paddingBuffer = priceRange * 0.08
            val plotMin = minP - paddingBuffer
            val plotMax = maxP + paddingBuffer
            val plotRange = plotMax - plotMin

            // Draw horizontal grid lines (4 intervals)
            val gridLines = 4
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#64748B")
                textSize = 24f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            for (i in 0..gridLines) {
                val y = height * (i.toFloat() / gridLines)
                val priceAtY = plotMax - (i.toFloat() / gridLines) * plotRange

                drawLine(
                    color = BrandBorder.copy(alpha = 0.6f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )

                // Price label on right margin (strictly up to 5 decimals)
                val label = com.aistudio.alphapulse.core.Formatters.formatRawPrice5Dec(priceAtY)

                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    width + 8f,
                    y + 8f,
                    textPaint
                )
            }

            val n = chartPoints.size
            fun getX(idx: Int): Float = (idx.toFloat() / (n - 1).coerceAtLeast(1)) * width
            fun getY(price: Double): Float = height - ((price - plotMin) / plotRange).toFloat() * height

            // 1. Draw Realized Line (solid blue)
            val realizedPoints = chartPoints.mapIndexedNotNull { idx, pt ->
                pt.realizedPrice?.let { Offset(getX(idx), getY(it)) }
            }
            if (realizedPoints.size > 1) {
                val path = Path().apply {
                    moveTo(realizedPoints[0].x, realizedPoints[0].y)
                    for (i in 1 until realizedPoints.size) {
                        lineTo(realizedPoints[i].x, realizedPoints[i].y)
                    }
                }
                drawPath(path, BrandAccent, style = Stroke(width = 3.5f))
            }

            // 2. Draw Upper Cone (dashed purple)
            val upperPoints = chartPoints.mapIndexedNotNull { idx, pt ->
                pt.upperCone?.let { Offset(getX(idx), getY(it)) }
            }
            if (upperPoints.size > 1) {
                val path = Path().apply {
                    moveTo(upperPoints[0].x, upperPoints[0].y)
                    for (i in 1 until upperPoints.size) {
                        lineTo(upperPoints[i].x, upperPoints[i].y)
                    }
                }
                drawPath(
                    path,
                    BrandNeural.copy(alpha = 0.5f),
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    )
                )
            }

            // 3. Draw Lower Cone (dashed red)
            val lowerPoints = chartPoints.mapIndexedNotNull { idx, pt ->
                pt.lowerCone?.let { Offset(getX(idx), getY(it)) }
            }
            if (lowerPoints.size > 1) {
                val path = Path().apply {
                    moveTo(lowerPoints[0].x, lowerPoints[0].y)
                    for (i in 1 until lowerPoints.size) {
                        lineTo(lowerPoints[i].x, lowerPoints[i].y)
                    }
                }
                drawPath(
                    path,
                    BrandBearish.copy(alpha = 0.5f),
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    )
                )
            }

            // 4. Draw Tri-Factor Forecast Path (dashed neural with circle dots)
            val forecastPoints = chartPoints.mapIndexedNotNull { idx, pt ->
                pt.forecastPrice?.let { Offset(getX(idx), getY(it)) }
            }
            if (forecastPoints.size > 1) {
                val path = Path().apply {
                    moveTo(forecastPoints[0].x, forecastPoints[0].y)
                    for (i in 1 until forecastPoints.size) {
                        lineTo(forecastPoints[i].x, forecastPoints[i].y)
                    }
                }
                drawPath(
                    path,
                    BrandNeural,
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                )

                // Draw circles on forward steps
                for (pt in forecastPoints) {
                    drawCircle(BrandNeural, radius = 4f, center = pt)
                }
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String, dashed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(if (dashed) 2.dp else 3.dp)
                .background(color)
        )
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
