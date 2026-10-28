package com.aistudio.alphapulse.domain.agent

import com.aistudio.alphapulse.domain.decision.DecisionInputs
import com.aistudio.alphapulse.domain.decision.DecisionRouter
import com.aistudio.alphapulse.domain.decision.DecisionState
import com.aistudio.alphapulse.domain.decision.Needle3DecisionRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the pipeline's decision agent, specified as Given/When/Then.
 *
 * §29 requires the router to be replaceable, and the pipeline must actually use the
 * one it was given. These scenarios pin the wiring: the production agent resolves
 * the on-device Needle 3 router, the deterministic rules answer whenever no
 * verified checkpoint is mounted, and the agent exposes why a route was chosen
 * instead of discarding the router's reason.
 *
 * Spec Ref: §29 Quantitative Decision Router, §30 Needle / Agent Orchestration,
 * §44 Failure Handling
 */
class DecisionRouterAgentBddSpec {

    private val bullish = DecisionRouterInput(
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

    private val marketData = mapOf(
        "lastPrice" to 100.0,
        "priceChangePercent" to 1.5,
        "high24h" to 105.0,
        "low24h" to 95.0,
        "volume" to 1000.0,
        "quoteVolume" to 100000.0,
        "bidRatioPct" to 60.0,
        "netImbalanceUsdt" to 100.0
    )

    /** A router that records the snapshot it was asked about and answers fixed. */
    private class StubRouter(
        private val state: DecisionState,
        override val decisionConfidence: Double = 0.66
    ) : DecisionRouter {

        var seen: DecisionInputs? = null

        override var lastReason: String = "stub router answered"
            private set

        override fun router(inputs: DecisionInputs): DecisionState {
            seen = inputs
            return state
        }
    }

    @Test
    fun `given an injected router when the agent executes then its verdict and its reason are the ones used`() {
        val stub = StubRouter(DecisionState.SHORT)
        val agent = DecisionRouterAgent(stub)

        assertEquals(DecisionState.SHORT, agent.execute(bullish))
        assertEquals("The agent must not swallow the router's confidence", 0.66, agent.decisionConfidence, 1e-9)
        assertEquals("stub router answered", agent.lastReason)

        // The snapshot reaches the router intact, not a copy with defaults filled in.
        val seen = stub.seen!!
        assertEquals(1.5, seen.triFactorForecast, 1e-9)
        assertEquals(1.2, seen.tsfmForecast, 1e-9)
        assertEquals(0.80, seen.lobConfidence, 1e-9)
        assertEquals(0.20, seen.modelDisagreement, 1e-9)
        assertEquals(0, seen.coneBreaches)
    }

    @Test
    fun `given nothing mounted when the production agent executes then the deterministic rules answer and say why`() {
        // The only state a test machine can be in: no verified needle3.cact, so the
        // on-device engine has nothing to run and the rules must carry the decision.
        val agent = DecisionRouterAgent()

        assertEquals(DecisionState.STRONG_LONG, agent.execute(bullish))
        assertTrue(
            "The fallback must explain itself: ${agent.lastReason}",
            agent.lastReason.contains("Fallback:")
        )
        assertEquals(
            "A deterministic verdict carries no calibrated confidence",
            0.0,
            agent.decisionConfidence,
            0.0
        )
    }

    @Test
    fun `given no mounted checkpoint when the production router routes then it stays deterministic`() {
        val router = Needle3DecisionRouter.forMountedCheckpoint()

        val state = router.router(
            DecisionInputs(
                triFactorForecast = 1.5,
                tsfmForecast = 1.2,
                lobConfidence = 0.80,
                liquidityConfidence = 0.80,
                regimeConfidence = 0.80,
                modelDisagreement = 0.20,
                dataFreshness = 1.0
            )
        )

        assertEquals(DecisionState.STRONG_LONG, state)
        assertTrue(router.lastReason.contains("Fallback:"))
    }

    @Test
    fun `given the full pipeline and an injected router when it runs then the router's verdict is the pipeline's decision`() {
        val stub = StubRouter(DecisionState.NO_TRADE, 0.90)

        val result = runBlocking {
            AgentDAG.executeFullPipeline(
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
                recentErrors = 0.0,
                decisionRouter = stub
            )
        }

        // The deterministic rules would answer STRONG_LONG for this snapshot, so only
        // the injected router can produce NO_TRADE.
        assertEquals(DecisionState.NO_TRADE, result)
        assertNotNull("The injected router must have been asked", stub.seen)
    }
}
