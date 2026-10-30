package com.aistudio.alphapulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.aistudio.alphapulse.data.model.BacktestMetrics
import com.aistudio.alphapulse.engine.SimulationScenario
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.engine.ReplayEngine.ReplayResult
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary

@Composable
fun SimulationScreen(
    metrics: BacktestMetrics,
    selectedScenario: SimulationScenario?,
    onRunScenario: (SimulationScenario) -> Unit,
    lastReplay: ReplayResult? = null,
    isReplayRunning: Boolean = false,
    onRunReplay: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scenarios = listOf(
        SimulationScenario(
            id = "SCENARIO_SHORT_SQUEEZE",
            name = "Negative Funding Liquidation Squeeze",
            regime = com.aistudio.alphapulse.data.model.MarketRegime.BULLISH_CONTINUATION,
            description = "Shorts crowded with negative funding (-0.025%). Price probes resistance and accelerates through stop pools.",
            expectedBehavior = "Proactive Support & Breakout Longs capture pre-move pain path; Short boards aggressively suppressed."
        ),
        SimulationScenario(
            id = "SCENARIO_BREAKOUT_TRAP",
            name = "False Breakout Wick & Immediate Trap",
            regime = com.aistudio.alphapulse.data.model.MarketRegime.BALANCE,
            description = "Price pushes above range high on thin volume, then violently prints rejection wick and loses reclaim level.",
            expectedBehavior = "Fast soft-invalidation (under 4.5s) cuts Breakout Long; Reclaim Fade Short automatically activated."
        ),
        SimulationScenario(
            id = "SCENARIO_SPLIT_BIAS_PUMP",
            name = "Tactical Pump into Strategic Supply",
            regime = com.aistudio.alphapulse.data.model.MarketRegime.TRANSITION,
            description = "15m tactical momentum pushes price into 4h heavy supply band. High tactical bias vs deeply negative strategic bias.",
            expectedBehavior = "Tactical long monetizes quickly at pivot; Strategic Resistance Short armed with heavy size at supply band."
        ),
        SimulationScenario(
            id = "SCENARIO_CASCADE_BREAKDOWN",
            name = "Support Failure Liquidation Cascade",
            regime = com.aistudio.alphapulse.data.model.MarketRegime.BEARISH_CONTINUATION,
            description = "Price accepts below HTF support shelf with OI surging, triggering automated liquidations down to next floor.",
            expectedBehavior = "Support Long cancels immediately on acceptance below shelf; Breakdown Short slices fill and achieve target."
        )
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
            .testTag("simulation_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "HISTORICAL REPLAY & BACKTEST HARNESS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 0.5.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Testing proactive asymmetry vs symmetric baseline across volatile market regimes",
                    style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 10.sp)
                )
            }
        }

        // Key Backtest Outputs Table (Section 37)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "KEY PERFORMANCE OUTPUTS (REPLAY BENCHMARK)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = SlateTextSecondary,
                        fontSize = 10.sp
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                MetricItem(
                    title = "Captured Move Before Confirmation",
                    value = "${metrics.capturedMoveBeforeConfirmationPct}%",
                    explanation = "Edge metric: validates proactive staging enters ahead of crowd",
                    valueColor = EmeraldBull
                )
                MetricItem(
                    title = "Avg Invalidation Speed",
                    value = "${metrics.averageInvalidationSpeedSec} sec",
                    explanation = "Speed cutting bad ideas before adverse momentum expands",
                    valueColor = CyanAccent
                )
                MetricItem(
                    title = "Order Miss Rate",
                    value = "${metrics.orderMissRatePct}%",
                    explanation = "Passive limit orders missed due to frontrunning or fast cancels",
                    valueColor = SlateTextPrimary
                )
                MetricItem(
                    title = "Max Adverse Excursion (MAE)",
                    value = "${metrics.maxAdverseExcursionPct}%",
                    explanation = "Max unrealized heat absorbed across active campaign slices",
                    valueColor = AmberWarning
                )
                MetricItem(
                    title = "False Positive Invalidation Rate",
                    value = "${metrics.falsePositiveInvalidationPct}%",
                    explanation = "Premature cancellations before original target was reached",
                    valueColor = SlateTextMuted
                )
            }
        }

        // §37 historical replay runner (Phase 5)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "DETERMINISTIC HISTORICAL REPLAY (PHASE 5)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        fontSize = 10.sp
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onRunReplay(false) },
                        enabled = !isReplayRunning,
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isReplayRunning) Color(0xFF1E293B) else EmeraldBull,
                            contentColor = Color.Black
                        )
                    ) {
                        Text(
                            if (isReplayRunning) "Replaying…" else "Replay History",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { onRunReplay(true) },
                        enabled = !isReplayRunning,
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = CyanAccent
                        )
                    ) {
                        Text("Run Symmetric Baseline", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                lastReplay?.let { replay ->
                    Spacer(modifier = Modifier.height(10.dp))
                    ReplayStatRow(
                        label = "Replayed boards",
                        value = "${replay.metrics.replayedCampaigns}"
                    )
                    ReplayStatRow(
                        label = "Filled slices",
                        value = "${replay.metrics.filledSliceCount}"
                    )
                    ReplayStatRow(
                        label = "Replay PnL",
                        value = "$${replay.metrics.totalPnlUsd}"
                    )
                    ReplayStatRow(
                        label = "Miss rate / Max adverse excursion",
                        value = "${replay.metrics.orderMissRatePct}% / ${replay.metrics.maxAdverseExcursionPct}%"
                    )
                    ReplayStatRow(
                        label = "False-positive / slow invalidations",
                        value = "${replay.metrics.falsePositiveInvalidationPct}% / ${replay.metrics.slowInvalidationPct}%"
                    )
                    if (replay.degradedFeeds) {
                        Text(
                            text = "Crowding/liquidity feeds absent: run degraded toward neutral posture (§37)",
                            style = MaterialTheme.typography.labelSmall.copy(color = AmberWarning, fontSize = 9.sp)
                        )
                    }
                }
            }
        }

        // PnL By Regime Bucket Breakdown
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "CUMULATIVE PNL BY REGIME BUCKET",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = SlateTextSecondary,
                        fontSize = 10.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                metrics.pnlByRegime.forEach { (regimeName, pnl) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = regimeName,
                            style = MaterialTheme.typography.labelSmall.copy(color = SlateTextPrimary, fontSize = 10.sp)
                        )
                        Text(
                            text = "+$${pnl.toInt()} USD",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = EmeraldBull,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
        }

        // Stress-Test Scenarios Runner
        item {
            Text(
                text = "SELECT STRESS-TEST SCENARIO TO REPLAY",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent,
                    fontSize = 10.sp
                )
            )
        }

        items(scenarios, key = { it.id }) { scenario ->
            val isSelected = scenario.id == selectedScenario?.id
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBackground)
                    .border(
                        1.dp,
                        if (isSelected) CyanAccent else CardBorder,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onRunScenario(scenario) }
                    .padding(12.dp)
                    .testTag("scenario_card_${scenario.id}")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = scenario.name,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SlateTextPrimary
                        )
                    )
                    Button(
                        onClick = { onRunScenario(scenario) },
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) CyanAccent else Color(0xFF1E293B),
                            contentColor = if (isSelected) Color.Black else CyanAccent
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Replay", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = scenario.description,
                    style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 10.sp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Expected Proactive Reaction: ${scenario.expectedBehavior}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFF81D4FA),
                        fontSize = 9.sp
                    )
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun MetricItem(title: String, value: String, explanation: String, valueColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(color = SlateTextPrimary, fontSize = 10.sp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = valueColor,
                    fontSize = 11.sp
                )
            )
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 9.sp)
        )
    }
}

@Composable
private fun ReplayStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = SlateTextPrimary, fontSize = 10.sp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = SlateTextSecondary,
                fontSize = 10.sp
            )
        )
    }
}
