package com.aistudio.alphapulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.Locale

/**
 * ProactiveMS Telemetry — the venue, feed and envelope readout behind the desk.
 *
 * It replaces the four-line text block the old host printed for this tab: every value is a
 * measurement from the live [MainUiState] (latency, freshness, rejects, exposure), laid out
 * in the same cards and typography as the AlphaPulse workspace so the two screens read as
 * one product. Nothing here is invented: a quiet feed shows its real age, not zero.
 *
 * Spec Ref: Phase 2 §32, §56, §72
 */
@Composable
fun ProactiveTelemetryTab(state: MainUiState, modifier: Modifier = Modifier) {
    val health = state.venueHealth
    val risk = state.riskEnvelope

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
            .testTag("proactive_telemetry_tab"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TelemetryHeader(health = health, isLiveUniverse = state.isLiveUniverse)
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                SectionTitle("VENUE & FEED TELEMETRY")
                MetricRow(
                    Metric("BINANCE PING", "${health.binancePingMs} ms", if (health.isLatencySpike) CrimsonBear else EmeraldBull),
                    Metric("COINGLASS PING", "${health.coinglassPingMs} ms", if (health.isLatencySpike) CrimsonBear else EmeraldBull),
                    Metric("BOOK SPREAD", String.format(Locale.US, "%.2f bps", health.spreadBps), CyanAccent)
                )
                Spacer(modifier = Modifier.height(10.dp))
                MetricRow(
                    Metric(
                        "FEED AGE",
                        "${health.feedStalenessSec}s",
                        if (health.isFeedStale) AmberWarning else EmeraldBull
                    ),
                    Metric(
                        "REJECT RATE",
                        String.format(Locale.US, "%.2f%%", health.orderRejectRatePct),
                        if (health.orderRejectRatePct > 1.0) AmberWarning else SlateTextPrimary
                    ),
                    Metric("RAM", "${health.availableRamMb} MB", if (health.isRamConstrained) AmberWarning else SlateTextPrimary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                MetricRow(
                    Metric("UNIVERSE", if (state.isLiveUniverse) "LIVE" else "SEED", if (state.isLiveUniverse) EmeraldBull else AmberWarning),
                    Metric("CAMPAIGNS", "${state.campaigns.size}", SlateTextPrimary),
                    Metric("AUDIT ROWS", "${state.auditLogs.size}", SlateTextPrimary)
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                SectionTitle("VENUE HEALTH CHECKS")
                HealthRow("Price feed stream", if (health.isFeedStale) "STALE" else "LIVE", !health.isFeedStale)
                HealthRow(
                    "Exchange latency",
                    if (health.isLatencySpike) "SPIKE" else "${health.binancePingMs} ms",
                    !health.isLatencySpike
                )
                HealthRow(
                    "Device memory",
                    if (health.isRamConstrained) "CONSTRAINED" else "${health.availableRamMb} MB free",
                    !health.isRamConstrained
                )
                HealthRow(
                    "Thermal / low power",
                    if (health.isLowPowerMode || health.isThermallyThrottled) "THROTTLED" else "NOMINAL",
                    !health.isLowPowerMode && !health.isThermallyThrottled
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                SectionTitle("RISK ENVELOPE")
                MetricRow(
                    Metric("ACCOUNT EQUITY", "$${risk.accountEquityUsd.toInt()}", SlateTextPrimary),
                    Metric(
                        "GROSS EXPOSURE",
                        "$${risk.currentGrossExposureUsd.toInt()} / $${risk.maxGrossExposureUsd.toInt()}",
                        CyanAccent
                    ),
                    Metric(
                        "DAILY PNL",
                        String.format(
                            Locale.US,
                            "%s$%.2f",
                            if (risk.dailyPnLUsd >= 0.0) "+" else "-",
                            kotlin.math.abs(risk.dailyPnLUsd)
                        ),
                        if (risk.dailyPnLUsd >= 0.0) EmeraldBull else CrimsonBear
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                MetricRow(
                    Metric("DAILY STOP", "${risk.dailyLossLimitR}R", AmberWarning),
                    Metric(
                        "WORST-CASE STOP-OUT",
                        String.format(Locale.US, "%.2fR", risk.worstCaseStopOutR),
                        if (risk.worstCaseStopOutR > 1.2) CrimsonBear else CyanAccent
                    ),
                    Metric(
                        "KILL SWITCH",
                        if (risk.isKillSwitchEngaged) "ENGAGED" else "STANDBY",
                        if (risk.isKillSwitchEngaged) CrimsonBear else EmeraldBull
                    )
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                SectionTitle("SESSION")
                HealthRow("Execution mode", state.executionMode.displayName.uppercase(), true)
                HealthRow(
                    "In-memory API session",
                    if (state.isSessionActive) "ACTIVE (RAM)" else "LOCKED / NO KEY",
                    state.isSessionActive
                )
                HealthRow(
                    "Real futures wallet",
                    state.liveWalletUsd?.let { String.format(Locale.US, "%.2f USDT", it) } ?: "PAPER WALLET",
                    state.liveWalletUsd != null
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun TelemetryHeader(health: VenueHealth, isLiveUniverse: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SYSTEM TELEMETRY",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "Venue, feed and envelope readout for the running workspace",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                        color = SlateTextMuted,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            StatusPill(
                label = when {
                    !health.isHealthy -> "DEGRADED"
                    !isLiveUniverse -> "OFFLINE SEED"
                    else -> "HEALTHY"
                },
                tint = when {
                    !health.isHealthy -> CrimsonBear
                    !isLiveUniverse -> AmberWarning
                    else -> EmeraldBull
                }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = SlateTextSecondary,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

private data class Metric(val label: String, val value: String, val tint: Color)

@Composable
private fun MetricRow(vararg metrics: Metric) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        metrics.forEach { metric ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0D1526), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = metric.label,
                    color = SlateTextMuted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = metric.value,
                    color = metric.tint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, isPass: Boolean) {
    val tint = if (isPass) EmeraldBull else CrimsonBear
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = SlateTextPrimary,
                fontSize = 10.sp
            )
        }
        Text(
            text = value,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StatusPill(label: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.15f))
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
