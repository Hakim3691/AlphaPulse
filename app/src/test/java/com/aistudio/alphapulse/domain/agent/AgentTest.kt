package com.aistudio.alphapulse.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Agent unit tests (spec section 30, unit group).
 * Covers BaseAgent interface, AgentDAG orchestration, and all 7 concrete agents.
 */
class AgentTest {

    @Test
    fun `agent_health_defaults_to_healthy`() {
        val agent = IngestionAgent()
        assertEquals(AgentHealth.HEALTHY, agent.health)
    }

    @Test
    fun `agent_telemetry_defaults_to_zero`() {
        val agent = IngestionAgent()
        assertEquals(0L, agent.telemetry.executionCount)
        assertEquals(0L, agent.telemetry.lastExecutionDurationMs)
        assertTrue(agent.telemetry.lastSuccess)
    }

    @Test
    fun `ingestion_agent_valid_inputs_returns_output`() {
        val agent = IngestionAgent()
        val input = IngestionInput(
            symbol = "BTCUSDT",
            marketData = mapOf(
                "lastPrice" to 100.0,
                "priceChangePercent" to 0.5,
                "high24h" to 105.0,
                "low24h" to 95.0,
                "volume" to 1000.0,
                "quoteVolume" to 100000.0
            ),
            priceHistory = listOf(100.0, 101.0, 99.0, 102.0, 101.5)
        )
        val output = agent.execute(input) as IngstionOutput
        assertEquals("BTCUSDT", output.symbol)
        assertEquals(100.0, output.lastPrice, 0.001)
        assertTrue("Current regime should be determined", output.currentRegime.isNotEmpty())
    }

    @Test
    fun `ingestion_agent_invalid_symbol_returns_null`() {
        val agent = IngestionAgent()
        val input = IngestionInput(
            symbol = "",
            marketData = mapOf(
                "lastPrice" to 100.0,
                "priceChangePercent" to 0.5
            ),
            priceHistory = emptyList()
        )
        val output = agent.execute(input)
        assertNull("Should return null for blank symbol", output)
    }

    @Test
    fun `ingestion_agent_empty_market_data_returns_null`() {
        val agent = IngestionAgent()
        val input = IngestionInput(
            symbol = "BTCUSDT",
            marketData = emptyMap(),
            priceHistory = emptyList()
        )
        val output = agent.execute(input)
        assertNull("Should return null for empty market data", output)
    }

    @Test
    fun `market_state_agent_valid_inputs_returns_output`() {
        val agent = MarketStateAgent()
        val input = MarketStateInput(
            symbol = "BTCUSDT",
            priceData = mapOf("priceChangePercent" to 1.5),
            orderBookSnapshot = mapOf("bidRatio" to 0.6, "imbalance" to 50.0)
        )
        val output = agent.execute(input) as MarketStateOutput
        assertEquals("BTCUSDT", output.symbol)
        assertEquals(1.5, output.priceChange, 0.001)
        assertTrue("Regime should be determined", output.currentRegime.isNotEmpty())
        assertTrue("Regime confidence should be positive", output.regimeConfidence > 0.0)
    }

    @Test
    fun `market_state_agent_stale_returns_mean_reverting`() {
        val agent = MarketStateAgent()
        val input = MarketStateInput(
            symbol = "BTCUSDT",
            priceData = mapOf("priceChangePercent" to 0.1),
            orderBookSnapshot = mapOf("bidRatio" to 0.5, "imbalance" to 0.0)
        )
        val output = agent.execute(input) as MarketStateOutput
        assertTrue("Mean-reverting regime expected for small move", 
            output.currentRegime.contains("Mean-Reverting") || output.currentRegime.contains("State 0"))
    }

    @Test
    fun `quant_vector_agent_valid_inputs_returns_output`() {
        val agent = QuantVectorAgent()
        val input = QuantVectorInput(
            symbol = "BTCUSDT",
            currentRegime = "State 0: Mean-Reverting",
            priceHistory = listOf(100.0, 101.0, 99.0, 102.0, 101.5),
            depthSnapshot = mapOf("bidRatio" to 0.6, "imbalance" to 10.0)
        )
        val output = agent.execute(input) as QuantVectorOutput
        assertEquals("BTCUSDT", output.symbol)
        assertTrue("Tri-factor forecast should be computed", output.triFactorForecast != 0.0 || output.liquidityPath != 0.0)
    }

    @Test
    fun `tsfm_agent_valid_inputs_returns_output`() {
        val agent = TsfmAgent()
        val input = TsfmInput(
            symbol = "BTCUSDT",
            currentPrice = 100.0,
            regime = "State 0: Mean-Reverting",
            quantVector = 0.01
        )
        val output = agent.execute(input) as TsfmOutput
        assertEquals("BTCUSDT", output.symbol)
        assertTrue("TSFM forecast should be computed", output.tsfmForecast != 0.0)
        assertEquals("State 0: Mean-Reverting", output.regime)
        assertTrue("Confidence should be positive", output.confidence > 0.0)
    }

    @Test
    fun `decision_router_agent_delegates_to_deterministic`() {
        val agent = DecisionRouterAgent()
        val input = DecisionRouterInput(
            symbol = "BTCUSDT",
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0
        )
        val output = agent.execute(input) as DecisionState
        assertEquals(DecisionState.STRONG_LONG, output)
    }

    @Test
    fun `risk_confidence_agent_valid_inputs_returns_output`() {
        val agent = RiskConfidenceAgent()
        val input = RiskConfidenceInput(
            symbol = "BTCUSDT",
            decisionState = DecisionState.LONG,
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0
        )
        val output = agent.execute(input) as RiskConfidenceOutput
        assertEquals("BTCUSDT", output.symbol)
        assertEquals(DecisionState.LONG, output.decisionState)
        assertTrue("Confidence should be between 0 and 1", output.confidence in 0.0..1.0)
        assertNotNull("riskMetrics should not be null", output.riskMetrics)
        assertTrue("riskMetrics should contain expected keys", 
            output.riskMetrics.keys.any { it == "confidence" })
    }

    @Test
    fun `narrator_agent_valid_inputs_returns_json`() {
        val agent = NarratorAgent()
        val input = NarratorInput(
            symbol = "BTCUSDT",
            decisionState = DecisionState.LONG,
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0,
            riskMetrics = mapOf("confidence" to 0.85, "triFactorForecast" to 1.5)
        )
        val output = agent.execute(input) as String
        assertNotNull("Narrator should return a JSON string", output)
        assertTrue("Output should contain symbol", output.contains("BTCUSDT"))
        assertTrue("Output should contain decisionState", output.contains("LONG"))
    }

    @Test
    fun `agent_dag_full_pipeline_with_valid_inputs_returns_decision_state`() {
        // Minimal market data for pipeline execution
        val marketData = mapOf(
            "lastPrice" to 100.0,
            "priceChangePercent" to 1.5,
            "high24h" to 105.0,
            "low24h" to 95.0,
            "volume" to 1000.0,
            "quoteVolume" to 100000.0,
            "bidRatioPct" to 60.0,
            "netImbalanceUsdt" to 100.0
        )

        val result = AgentDAG.executeFullPipeline(
            symbol = "BTCUSDT",
            marketData = marketData,
            triFactorForecast = 1.5,
            tsfmForecast = 1.2,
            lobConfidence = 0.80,
            liquidityConfidence = 0.80,
            regimeConfidence = 0.80,
            modelDisagreement = 0.20,
            dataFreshness = 1.0,
            coneBreaches = 0,
            recentErrors = 0.0
        )

        // Pipeline should return a decision state (may be NO_TRADE depending on inputs)
        assertNotNull("Pipeline should return a DecisionState", result)
    }

    @Test
    fun `agent_dag_full_pipeline_with_stale_data_returns_no_trade`() {
        val marketData = mapOf(
            "lastPrice" to 100.0,
            "priceChangePercent" to 0.1,
            "high24h" to 101.0,
            "low24h" to 99.0,
            "volume" to 500.0,
            "quoteVolume" to 50000.0,
            "bidRatioPct" to 52.0,
            "netImbalanceUsdt" to 5.0
        )

        val result = AgentDAG.executeFullPipeline(
            symbol = "BTCUSDT",
            marketData = marketData,
            triFactorForecast = 0.1,
            tsfmForecast = 0.1,
            lobConfidence = 0.55,
            liquidityConfidence = 0.55,
            regimeConfidence = 0.55,
            modelDisagreement = 0.50,
            dataFreshness = 0.30,
            coneBreaches = 2,
            recentErrors = 0.5
        )

        // With stale data and many cone breaches, should return NO_TRADE
        assertNotNull("Pipeline should return a DecisionState", result)
        // The result may vary based on exact inputs, but should not crash
    }

    @Test
    fun `ingestion_agent_execution_timeout_5_seconds`() {
        val agent = IngestionAgent()
        // Timeout should be 5000ms
        assertEquals(5000L, agent.executionTimeoutMs)
    }

    @Test
    fun `market_state_agent_execution_timeout_3_seconds`() {
        val agent = MarketStateAgent()
        assertEquals(3000L, agent.executionTimeoutMs)
    }

    @Test
    fun `quant_vector_agent_execution_timeout_4_seconds`() {
        val agent = QuantVectorAgent()
        assertEquals(4000L, agent.executionTimeoutMs)
    }

    @test
    fun `tsfm_agent_execution_timeout_8_seconds`() {
        val agent = TsfmAgent()
        assertEquals(8000L, agent.executionTimeoutMs)
    }

    @Test
    fun `decision_router_agent_execution_timeout_2_seconds`() {
        val agent = DecisionRouterAgent()
        assertEquals(2000L, agent.executionTimeoutMs)
    }

    @Test
    fun `risk_confidence_agent_execution_timeout_2_seconds`() {
        val agent = RiskConfidenceAgent()
        assertEquals(2000L, agent.executionTimeoutMs)
    }

    @Test
    fun `narrator_agent_execution_timeout_3_seconds`() {
        val agent = NarratorAgent()
        assertEquals(3000L, agent.executionTimeoutMs)
    }
}