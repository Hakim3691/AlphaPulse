package com.aistudio.alphapulse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import com.aistudio.alphapulse.profiling.DeviceProfile
import com.aistudio.alphapulse.profiling.HardwareGatekeeper
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.data.model.ModelLoadStatus
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandNeural
import com.aistudio.alphapulse.ui.theme.TextPrimary
import com.aistudio.alphapulse.ui.theme.TextTertiary

// Docked strip palette (#0B0E14 / #111622) with the cyan action accent.
private val DockDeep = Color(0xFF0B0E14)
private val DockSurface = Color(0xFF111622)
private val DockCyan = Color(0xFF22D3EE)
private val DockReady = Color(0xFF34D399)
// Amber "do not pass" colour for hardware-blocked downloads.
private val DockBlocked = Color(0xFFF59E0B)

private data class ModelDockSpec(
    val id: String,
    val microcopy: String,
    val sizeMb: Int,
    val icon: ImageVector,
    val tint: Color
)

// Specs come from the published artifact manifest: icon, micro-copy and real size.
// Needle 3 sits alongside the two TSFMs; the HardwareGatekeeper still decides
// whether the device in hand is allowed to fetch it (mid-tier line: 3.5 GB / 4 cores).
private val DOCK_MODELS = listOf(
    ModelDockSpec(
        id = ModelArtifacts.CHRONOS.modelId,
        microcopy = ModelArtifacts.CHRONOS.microcopy,
        sizeMb = ModelArtifacts.CHRONOS.sizeMb,
        icon = Icons.Default.ShowChart,
        tint = DockCyan
    ),
    ModelDockSpec(
        id = ModelArtifacts.TIMESFM.modelId,
        microcopy = ModelArtifacts.TIMESFM.microcopy,
        sizeMb = ModelArtifacts.TIMESFM.sizeMb,
        icon = Icons.Default.Schedule,
        tint = BrandNeural
    ),
    ModelDockSpec(
        id = ModelArtifacts.NEEDLE3.modelId,
        microcopy = ModelArtifacts.NEEDLE3.microcopy,
        sizeMb = ModelArtifacts.NEEDLE3.sizeMb,
        icon = Icons.Default.Star,
        tint = DockReady
    )
)

/**
 * Exactly the models shown as downloadable tiles.
 *
 * Only on-device artifacts belong here: the Cactus engine is compiled in, so the
 * dock offers the three weight files and nothing else (§30, §47).
 */
val MODEL_DOCK_IDS: List<String> = DOCK_MODELS.map { it.id }

/**
 * Low-profile TSFM dock: one line of state per model, no model titles.
 * READY models glow green and are reflected in the ensemble cards below.
 */
@Composable
fun ModelDownloadSection(
    states: Map<String, ModelDownloadState>,
    onDownload: (String) -> Unit,
    // Tests pass a fixed phone; production passes null and reads the real device.
    deviceProfile: DeviceProfile? = null
) {
    val readyCount = DOCK_MODELS.count { states[it.id]?.status == ModelLoadStatus.READY }
    val allReady = readyCount == DOCK_MODELS.size

    // Ask the real phone how much RAM and how many cores it has, once per recompose.
    // The answer decides whether the download buttons below are clickable.
    val context = LocalContext.current
    val profile = remember(context, deviceProfile) {
        deviceProfile ?: HardwareGatekeeper.fromDevice(context)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tsfmModelDock")
            .clip(RoundedCornerShape(10.dp))
            .background(DockDeep)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TSFM MODEL DOCK",
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (allReady) DockReady else TextTertiary)
                )
                Text(
                    text = "$readyCount/${DOCK_MODELS.size} READY",
                    color = if (allReady) DockReady else TextTertiary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DOCK_MODELS.forEach { spec ->
                // The bouncer's verdict for this model on this phone.
                val gate = HardwareGatekeeper.decide(spec.id, profile)
                ModelDockTile(
                    modifier = Modifier.weight(1f),
                    spec = spec,
                    state = states[spec.id] ?: ModelDownloadState(spec.id),
                    blockedReason = if (gate.allowed) null else gate.reason,
                    onDownload = onDownload
                )
            }
        }
    }
}

@Composable
private fun ModelDockTile(
    modifier: Modifier,
    spec: ModelDockSpec,
    state: ModelDownloadState,
    blockedReason: String? = null,
    onDownload: (String) -> Unit
) {
    val downloading = state.status == ModelLoadStatus.DOWNLOADING
    val ready = state.status == ModelLoadStatus.READY
    // Below the mid-tier line the tile shows a lock instead of the download button.
    val blocked = blockedReason != null && !ready

    val shimmerAlpha = if (downloading) {
        rememberInfiniteTransition(label = "ModelDockShimmer").animateFloat(
            initialValue = 0.30f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "ModelDockShimmerAlpha"
        ).value
    } else 1f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(DockSurface)
            .border(
                width = 1.dp,
                color = when {
                    ready -> DockReady.copy(alpha = 0.35f)
                    downloading -> DockCyan.copy(alpha = 0.30f)
                    else -> BrandBorder
                },
                shape = RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(spec.tint.copy(alpha = if (ready) 0.18f else 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = spec.tint.copy(alpha = if (ready) 1f else 0.70f),
                    modifier = Modifier.size(13.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            when {
                ready -> ReadyBadge()
                blocked -> BlockedBadge()
                downloading -> Text(
                    text = "${(state.progress * 100).toInt()}%",
                    color = DockCyan,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                else -> DownloadTrigger { onDownload(spec.id) }
            }
        }

        // Function micro-copy spans the full tile width.
        Text(
            text = spec.microcopy,
            modifier = Modifier.fillMaxWidth(),
            color = if (ready) TextPrimary else TextTertiary,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        when {
            downloading -> LinearProgressIndicator(
                progress = state.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(50))
                    .alpha(shimmerAlpha),
                color = DockCyan,
                trackColor = BrandBorder
            )
            ready -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = DockReady,
                    modifier = Modifier.size(9.dp)
                )
                Text(
                    text = "IN ENSEMBLE",
                    color = DockReady,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            else -> Text(
                text = blockedReason?.let { "LOCKED · ${spec.sizeMb} MB" } ?: "${spec.sizeMb} MB",
                color = if (blocked) DockBlocked else TextTertiary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // When the gate says no, show why in one short line so the user is not
        // left guessing why the button disappeared.
        if (blocked) {
            Text(
                text = blockedReason.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
                color = DockBlocked,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BlockedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(DockBlocked.copy(alpha = 0.15f))
            .border(1.dp, DockBlocked.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = "BLOCKED",
            color = DockBlocked,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ReadyBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(DockReady.copy(alpha = 0.15f))
            .border(1.dp, DockReady.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = "READY",
            color = DockReady,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DownloadTrigger(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(DockCyan.copy(alpha = 0.08f))
            .border(1.dp, DockCyan.copy(alpha = 0.25f), RoundedCornerShape(5.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Download,
            contentDescription = "Download model",
            tint = DockCyan,
            modifier = Modifier.size(13.dp)
        )
    }
}
