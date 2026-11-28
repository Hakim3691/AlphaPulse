package com.aistudio.alphapulse.ui.components

/**
 * The one header AlphaPulse and the ProactiveMS workspace share.
 *
 * Why this exists
 * ---------------
 * The app used to draw two different headers on two different bars: `HeaderBar` owned
 * the AlphaPulse analysis screen, and `TopBar` owned the ProactiveMS workspace. That
 * meant the brand, the session timer, the readiness badges and the TSFM quick actions
 * disappeared the moment an operator opened the VIP workspace, and the workspace's live
 * data / mode / kill-switch / stop-out / hardware controls were invisible on the analysis
 * screen. Those are not two products with two identities; they are one app.
 *
 * There is now **one** header composable and `TopBar` is deleted. The AlphaPulse chrome
 * (brand, screening status, session timer, readiness badges, refresh) is always present.
 * The VIP block is passed as [VipHeaderState] only by the host that actually mounts the
 * campaign engine (`TraderHostActivity`); the base app passes `null`, so installing or
 * unlocking the feature still cannot add furniture to the analysis screen (C-33).
 *
 * The block is four compact rows rather than one, because the workspace controls do not
 * fit beside the brand on a 360dp phone (~379dp usable) and squeezing them made every
 * label truncate. Each row groups controls that are read together.
 *
 * The Chronos-Bolt / TimesFM quick actions moved out of this header into the
 * `ML ENSEMBLE WEIGHTS` card, where the weights they produce are already shown.
 *
 * Spec Ref: §40 Primary Dashboard, §22 Per-Horizon Readiness, Directive §3, C-57
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.data.model.ModelLoadStatus
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.ui.theme.*
import java.util.Locale

/**
 * The VIP chrome the ProactiveMS host adds to the shared header.
 *
 * It is a value object rather than eight more parameters so the base app can pass
 * `null` and get the unchanged AlphaPulse header. Every field is the same engine state
 * the workspace already renders; nothing here is owned by this composable.
 */
class VipHeaderState(
    val executionMode: ExecutionMode,
    val riskEnvelope: RiskEnvelope,
    val venueHealth: VenueHealth,
    val isSessionActive: Boolean,
    val isLiveUniverse: Boolean,
    val onModeChange: (ExecutionMode) -> Unit,
    val onToggleKillSwitch: () -> Unit,
    val onOpenOrphanWarning: () -> Unit
)

@Composable
fun AlphaPulseHeader(
    sessionElapsedSeconds: Long,
    isLTFReady: Boolean,
    isMTFReady: Boolean,
    isHTFReady: Boolean,
    pipelineRunning: Boolean,
    pipelineSecondsRemaining: Int,
    onRefreshClick: () -> Unit,
    vip: VipHeaderState? = null
) {
    val hrs = sessionElapsedSeconds / 3600
    val mins = (sessionElapsedSeconds % 3600) / 60
    val secs = sessionElapsedSeconds % 60
    val timerStr = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .border(width = 1.dp, color = BrandBorder)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("alpha_pulse_header"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Row 1 — identity, honesty chip, pipeline state.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(BrandAccent.copy(alpha = 0.15f))
                        .border(1.dp, BrandAccent.copy(alpha = 0.3f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "AlphaPulse Logo",
                        tint = BrandAccent,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    text = "AlphaPulse",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = (-0.3).sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                vip?.let { DataSourceChip(isLiveUniverse = it.isLiveUniverse) }
                HeaderStatusPill(pipelineRunning, pipelineSecondsRemaining)
            }
        }

        // Row 2 — session clock, per-horizon readiness, manual refresh.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrandCard)
                    .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Session Timer",
                        tint = BrandAccent,
                        modifier = Modifier.size(12.dp)
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

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ReadinessBadge(label = "LTF Ready", isReady = isLTFReady)
                ReadinessBadge(label = "MTF Ready", isReady = isMTFReady)
                ReadinessBadge(label = "HTF Ready", isReady = isHTFReady)
            }

            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier
                    .testTag("manual_refresh_button")
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrandCard)
                    .border(1.dp, BrandBorder, RoundedCornerShape(6.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Manual Refresh",
                    tint = BrandAccent,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        if (vip != null) {
            // Row 3 — execution mode and the published worst-case stop-out.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeSelectorPill(
                    executionMode = vip.executionMode,
                    onModeChange = vip.onModeChange
                )
                Text(
                    text = "Stop-out ${vip.riskEnvelope.worstCaseStopOutR}R",
                    style = MaterialThemeLabel,
                    color = if (vip.riskEnvelope.worstCaseStopOutR > 1.2) CrimsonBear else CyanAccent,
                    fontWeight = FontWeight.Bold
                )
            }

            // Row 4 — venue health and session security.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ping: ${vip.venueHealth.binancePingMs}ms",
                    color = if (vip.venueHealth.isLatencySpike) CrimsonBear else EmeraldBull,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "RAM: ${vip.venueHealth.availableRamMb}MB",
                    color = if (vip.venueHealth.isRamConstrained) AmberWarning else SlateTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (vip.isSessionActive) "LIVE SESSION" else "NO SESSION",
                        color = if (vip.isSessionActive) EmeraldBull else SlateTextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (vip.isSessionActive) Icons.Default.Lock else Icons.Default.Security,
                        contentDescription = "API Session Security Status",
                        tint = if (vip.isSessionActive) EmeraldBull else SlateTextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Row 5 — the two emergency controls, labelled so their function is legible at
            // a glance. Unlabelled 28dp icon buttons were indistinguishable from decoration,
            // and the kill switch had no visible state.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KillSwitchButton(
                    engaged = vip.riskEnvelope.isKillSwitchEngaged,
                    onClick = vip.onToggleKillSwitch,
                    modifier = Modifier.weight(1f)
                )
                OrphanWarningButton(
                    onClick = vip.onOpenOrphanWarning,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private val MaterialThemeLabel = androidx.compose.ui.text.TextStyle(
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace
)

/** The §4 honesty chip: live exchange data, or the clearly labelled offline seed. */
@Composable
private fun DataSourceChip(isLiveUniverse: Boolean) {
    val tone = if (isLiveUniverse) EmeraldBull else AmberWarning
    Box(
        modifier = Modifier
            .testTag("data_source_chip")
            .clip(RoundedCornerShape(10.dp))
            .background(tone.copy(alpha = 0.15f))
            .border(1.dp, tone.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isLiveUniverse) "LIVE DATA" else "OFFLINE SEED",
            style = MaterialThemeLabel,
            color = tone,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun ModeSelectorPill(
    executionMode: ExecutionMode,
    onModeChange: (ExecutionMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val tone = Color(executionMode.badgeColorHex)

    Box {
        Row(
            modifier = Modifier
                .testTag("mode_selector_pill")
                .clip(RoundedCornerShape(16.dp))
                .background(tone.copy(alpha = 0.15f))
                .border(1.dp, tone.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .clickable { expanded = true }
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tone)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = executionMode.displayName.uppercase(),
                color = tone,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Switch execution mode",
                tint = tone,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExecutionMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = mode.displayName,
                            color = Color(mode.badgeColorHex),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    onClick = {
                        onModeChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun KillSwitchButton(engaged: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .testTag("kill_switch_button")
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (engaged) CrimsonBear else Color(0xFF2A1519))
            .border(1.dp, CrimsonBear, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Dangerous,
            contentDescription = if (engaged) {
                "Emergency kill switch engaged — tap to review reset"
            } else {
                "Emergency kill switch armed — tap to engage"
            },
            tint = if (engaged) Color.White else CrimsonBear,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = if (engaged) "KILL-SWITCH · ENGAGED" else "KILL-SWITCH · ARMED",
            color = if (engaged) Color.White else CrimsonBear,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun OrphanWarningButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .testTag("orphan_warning_button")
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2D2512))
            .border(1.dp, AmberWarning, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Orphaned order guard — open the intercept",
            tint = AmberWarning,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "ORPHAN ORDER GUARD",
            color = AmberWarning,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun HeaderStatusPill(pipelineRunning: Boolean, pipelineSecondsRemaining: Int) {
    val tone = if (pipelineRunning) BrandAmber else BrandBullish
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(tone.copy(alpha = 0.15f))
            .border(1.dp, tone.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tone)
            )
            Text(
                text = if (pipelineRunning) {
                    "Screening (${pipelineSecondsRemaining}s)"
                } else {
                    "Ready"
                },
                color = tone,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
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
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = text,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
