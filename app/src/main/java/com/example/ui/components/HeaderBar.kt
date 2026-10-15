package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun HeaderBar(
    sessionElapsedSeconds: Long,
    isLTFReady: Boolean,
    isMTFReady: Boolean,
    isHTFReady: Boolean,
    pipelineRunning: Boolean,
    pipelineSecondsRemaining: Int,
    onRefreshClick: () -> Unit
) {
    val hrs = sessionElapsedSeconds / 3600
    val mins = (sessionElapsedSeconds % 3600) / 60
    val secs = sessionElapsedSeconds % 60
    val timerStr = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandSurface)
            .border(width = 1.dp, color = BrandBorder)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand Title & Pro Quant Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandAccent.copy(alpha = 0.15f))
                        .border(1.dp, BrandAccent.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "AlphaPulse Logo",
                        tint = BrandAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "AlphaPulse",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            letterSpacing = (-0.3).sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BrandAccent.copy(alpha = 0.15f))
                                .border(1.dp, BrandAccent.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PRO QUANT V14.3",
                                color = BrandAccent,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Status Pill
            if (pipelineRunning) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(BrandAmber.copy(alpha = 0.15f))
                        .border(1.dp, BrandAmber.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(BrandAmber)
                        )
                        Text(
                            text = "Screening (${pipelineSecondsRemaining}s)",
                            color = BrandAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(BrandBullish.copy(alpha = 0.15f))
                        .border(1.dp, BrandBullish.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(BrandBullish)
                        )
                        Text(
                            text = "Ready",
                            color = BrandBullish,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Session Timer, Readiness Badges & Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Session Timer Box
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrandCard)
                    .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Session Timer",
                        tint = BrandAccent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "SESSION:",
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = timerStr,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Readiness Badges
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ReadinessBadge(label = "LTF Ready", isReady = isLTFReady)
                ReadinessBadge(label = "MTF Ready", isReady = isMTFReady)
                ReadinessBadge(label = "HTF Ready", isReady = isHTFReady)
            }

            // Refresh Button
            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrandCard)
                    .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Manual Refresh",
                    tint = BrandAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ReadinessBadge(label: String, isReady: Boolean) {
    val bg = if (isReady) BrandBullish.copy(alpha = 0.2f) else BrandCard
    val border = if (isReady) BrandBullish.copy(alpha = 0.5f) else BrandBorder
    val text = if (isReady) BrandBullish else TextTertiary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = text,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
