package com.aistudio.alphapulse.ui.components

/**
 * The progress banner shown while the universe scanner is running.
 *
 * It tells the user how many contracts have been screened so far, so a long
 * scan never looks like a frozen app.
 *
 * Spec Ref: §40 Primary Dashboard
 */

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.ui.theme.*

@Composable
fun ScannerProgressBanner(
    secondsRemaining: Int,
    stepTitle: String,
    stepSubtitle: String,
    progress: Float
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0.01f, 1.0f),
        animationSpec = tween(durationMillis = 400),
        label = "ScannerProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandCard)
            .border(width = 1.dp, color = BrandBorder)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Countdown circle
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(BrandAccent.copy(alpha = 0.2f))
                    .border(1.dp, BrandAccent.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$secondsRemaining",
                    color = BrandAccent,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stepTitle,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stepSubtitle,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = BrandAccent,
            trackColor = BrandSurface
        )
    }
}
