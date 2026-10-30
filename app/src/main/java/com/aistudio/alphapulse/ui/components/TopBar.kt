package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary

@Composable
fun TopBar(
    executionMode: ExecutionMode,
    onModeChange: (ExecutionMode) -> Unit,
    riskEnvelope: RiskEnvelope,
    venueHealth: VenueHealth,
    isSessionActive: Boolean,
    isLiveUniverse: Boolean,
    onToggleKillSwitch: () -> Unit,
    onOpenOrphanWarning: () -> Unit
) {
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    // Edge-to-edge: the status bar draws over the app, so the header MUST pad itself
    // below the system status bar (clock/battery/notifications) — previously missing.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .border(width = 1.dp, color = CardBorder)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App branding & active status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (riskEnvelope.isKillSwitchEngaged) CrimsonBear else CyanAccent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "ProactiveMS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = SlateTextPrimary
                        )
                    )
                    Text(
                        text = "Cancellation-First Trading Automation",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SlateTextMuted,
                            fontSize = 9.sp
                        )
                    )
                }
            }

            // Data-source honesty chip (Spec §4): shows whether the displayed universe
            // is live exchange data or the clearly-labeled offline seed placeholder.
            Box(
                modifier = Modifier
                    .testTag("data_source_chip")
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        (if (isLiveUniverse) EmeraldBull else AmberWarning).copy(alpha = 0.15f)
                    )
                    .border(
                        1.dp,
                        (if (isLiveUniverse) EmeraldBull else AmberWarning).copy(alpha = 0.6f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isLiveUniverse) "LIVE DATA" else "OFFLINE SEED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isLiveUniverse) EmeraldBull else AmberWarning,
                        fontSize = 9.sp
                    )
                )
            }

            // Mode Selector Pill (tap for Shadow / Paper / Capped Live / Scaled Live)
            Box {
                Row(
                    modifier = Modifier
                        .testTag("mode_selector_pill")
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(executionMode.badgeColorHex).copy(alpha = 0.15f))
                        .border(
                            1.dp,
                            Color(executionMode.badgeColorHex).copy(alpha = 0.6f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { modeDropdownExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(executionMode.badgeColorHex))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = executionMode.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(executionMode.badgeColorHex),
                            fontSize = 10.sp
                        )
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Switch execution mode",
                        tint = Color(executionMode.badgeColorHex),
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = modeDropdownExpanded,
                    onDismissRequest = { modeDropdownExpanded = false }
                ) {
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
                                modeDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Emergency Kill Switch
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleKillSwitch,
                    modifier = Modifier
                        .testTag("kill_switch_button")
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (riskEnvelope.isKillSwitchEngaged) CrimsonBear else Color(0xFF2A1519)
                        )
                        .border(1.dp, CrimsonBear, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Dangerous,
                        contentDescription = "Emergency Kill Switch",
                        tint = if (riskEnvelope.isKillSwitchEngaged) Color.White else CrimsonBear,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Orphaned order safety simulator trigger
                IconButton(
                    onClick = onOpenOrphanWarning,
                    modifier = Modifier
                        .testTag("orphan_warning_button")
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2D2512))
                        .border(1.dp, AmberWarning, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Orphaned Order Guardrail Intercept",
                        tint = AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Mandatory Section 38 Display: Worst-Case Concurrent Stop-Out & Venue Diagnostics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Worst-case Stop-out display
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Worst-Case Stop-Out: ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SlateTextSecondary,
                        fontSize = 11.sp
                    )
                )
                Text(
                    text = "${riskEnvelope.worstCaseStopOutR}R",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (riskEnvelope.worstCaseStopOutR > 1.2) CrimsonBear else CyanAccent,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )
                Text(
                    text = " (Max: 2.0R)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SlateTextMuted,
                        fontSize = 10.sp
                    )
                )
            }

            // Real-time hardware health metrics
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Ping: ${venueHealth.binancePingMs}ms",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (venueHealth.isLatencySpike) CrimsonBear else EmeraldBull,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RAM: ${venueHealth.availableRamMb}MB",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (venueHealth.isRamConstrained) AmberWarning else SlateTextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (isSessionActive) Icons.Default.Lock else Icons.Default.Security,
                    contentDescription = "API Session Security Status",
                    tint = if (isSessionActive) EmeraldBull else SlateTextMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
