package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.MarketRegime

data class SimulationScenario(
    val id: String,
    val name: String,
    val regime: MarketRegime,
    val description: String,
    val expectedBehavior: String
)

class SimulationHarness {

    val availableScenarios = listOf(
        SimulationScenario(
            id = "SCENARIO_SHORT_SQUEEZE",
            name = "Negative Funding Liquidation Squeeze",
            regime = MarketRegime.BULLISH_CONTINUATION,
            description = "Shorts crowded with negative funding (-0.025%). Price probes resistance and accelerates through stop pools.",
            expectedBehavior = "Proactive Support & Breakout Longs capture pre-move pain path; Short boards aggressively suppressed."
        ),
        SimulationScenario(
            id = "SCENARIO_BREAKOUT_TRAP",
            name = "False Breakout Wick & Immediate Trap",
            regime = MarketRegime.BALANCE,
            description = "Price pushes above range high on thin volume, then violently prints rejection wick and loses reclaim level.",
            expectedBehavior = "Fast soft-invalidation (under 4.5s) cuts Breakout Long; Reclaim Fade Short automatically activated."
        ),
        SimulationScenario(
            id = "SCENARIO_SPLIT_BIAS_PUMP",
            name = "Tactical Pump into Strategic Supply",
            regime = MarketRegime.TRANSITION,
            description = "15m tactical momentum pushes price into 4h heavy supply band. High tactical bias vs deeply negative strategic bias.",
            expectedBehavior = "Tactical long monetizes quickly at pivot; Strategic Resistance Short armed with heavy size at supply band."
        ),
        SimulationScenario(
            id = "SCENARIO_CASCADE_BREAKDOWN",
            name = "Support Failure Liquidation Cascade",
            regime = MarketRegime.BEARISH_CONTINUATION,
            description = "Price accepts below HTF support shelf with OI surging, triggering automated liquidations down to next floor.",
            expectedBehavior = "Support Long cancels immediately on acceptance below shelf; Breakdown Short slices fill and achieve target."
        )
    )

    /**
     * §37: the harness defines scenarios structurally only. Metrics are never emitted
     * from literals — [com.aistudio.alphapulse.ui.MainViewModel.runSimulationScenario] replays the
     * scenario over persisted candle history via ReplayEngine, and degrades honestly
     * (neutral metrics + audit note) when history is insufficient.
     */
}
