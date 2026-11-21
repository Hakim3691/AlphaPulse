package com.aistudio.alphapulse.ui.components

/**
 * The top bar: product name, live status and symbol counts.
 *
 * It also shows whether each horizon has enough data to be trusted yet, and the two
 * TSFM quick actions (Chronos-Bolt and TimesFM-2.5) that replaced the bottom model
 * dock: green when the artifact is downloaded and verified, muted when it is not, and
 * a circular progress ring while the download runs.
 *
 * Spec Ref: §40 Primary Dashboard, §22 Per-Horizon Readiness, Directive §3 TSFM Quick Actions
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.data.model.ModelLoadStatus
import com.aistudio.alphapulse.ui.theme.*
import java.util.Locale

/** Green the quick action turns when its on-device artifact is verified and mounted. */
private val ModelReadyGreen = Color(0xFF00E676)

/** Muted grey for an artifact that is not on the device yet. */
private val ModelMissingGrey = Color(0xFF616161)

@Composable
fun HeaderBar(
    sessionElapsedSeconds: Long,
    isLTFReady: Boolean,
    isMTFReady: Boolean,
    isHTFReady: Boolean,
    pipelineRunning: Boolean,
    pipelineSecondsRemaining: Int,
    onRefreshClick: () -> Unit,
    modelStates: Map<String, ModelDownloadState> = emptyMap(),
    onDownloadModel: (String) -> Unit = {}
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
                    }
                }
            }

            // TSFM quick actions sit immediately left of the Ready badge.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HeaderModelIcon(
                    state = modelStates[ModelArtifacts.CHRONOS.modelId]
                        ?: ModelDownloadState(ModelArtifacts.CHRONOS.modelId),
                    icon = Icons.Rounded.Bolt,
                    label = "Chronos-Bolt",
                    tag = "chronos",
                    onDownload = { onDownloadModel(ModelArtifacts.CHRONOS.modelId) }
                )
                HeaderModelIcon(
                    state = modelStates[ModelArtifacts.TIMESFM.modelId]
                        ?: ModelDownloadState(ModelArtifacts.TIMESFM.modelId),
                    icon = Icons.Rounded.Psychology,
                    label = "TimesFM-2.5",
                    tag = "timesfm",
                    onDownload = { onDownloadModel(ModelArtifacts.TIMESFM.modelId) }
                )

                HeaderStatusPill(pipelineRunning, pipelineSecondsRemaining)
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

/**
 * One header quick action. Tapping a missing model starts its download into the
 * persistent files directory; a downloaded one is inert and green.
 */
@Composable
private fun HeaderModelIcon(
    state: ModelDownloadState,
    icon: ImageVector,
    label: String,
    tag: String,
    onDownload: () -> Unit
) {
    val ready = state.status == ModelLoadStatus.READY
    val downloading = state.status == ModelLoadStatus.DOWNLOADING
    val tint = when {
        ready -> ModelReadyGreen
        downloading -> BrandAccent
        else -> ModelMissingGrey
    }

    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BrandCard)
            .border(
                1.dp,
                if (ready) ModelReadyGreen.copy(alpha = 0.45f) else BrandBorder,
                RoundedCornerShape(6.dp)
            )
            .clickable(enabled = !ready && !downloading) { onDownload() }
            .testTag("header_model_$tag"),
        contentAlignment = Alignment.Center
    ) {
        if (downloading) {
            CircularProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(14.dp),
                color = BrandAccent,
                strokeWidth = 1.5.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = if (ready) "$label ready" else "Download $label",
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun HeaderStatusPill(pipelineRunning: Boolean, pipelineSecondsRemaining: Int) {
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
