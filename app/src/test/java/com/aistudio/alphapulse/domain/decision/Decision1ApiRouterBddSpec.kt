package com.aistudio.alphapulse.domain.decision

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the Decision-1 API router, specified as Given/When/Then.
 *
 * §29 requires the Microsoft-Decision-1 layer to stay a *replaceable interface*
 * and forbids it from becoming a hard dependency, so every scenario here checks
 * two things at once: the right verdict comes back on success, and the router
 * degrades to the deterministic verdict (never to an exception) on any failure.
 *
 * Spec Ref: §29 Quantitative Decision Router, §44 failure fallback.
 */
class Decision1ApiRouterBddSpec {

    /** A sample decision - bullish on both models with healthy confidence. */
    private val bullish = DecisionInputs(
        triFactorForecast = 0.42,
        tsfmForecast = 0.51,
        lobConfidence = 0.90,
        liquidityConfidence = 0.88,
        regimeConfidence = 0.85,
        modelDisagreement = 0.05,
        dataFreshness = 0.98,
        coneBreaches = 0,
        recentErrors = 0.01
    )

    /** A canned client so tests never touch the network. */
    private class FakeClient(
        private val reply: Decision1Reply?,
        private val failure: Boolean = false
    ) : Decision1Client {
        var calls = 0
            private set

        override suspend fun request(inputs: DecisionInputs): Decision1Reply? {
            calls++
            if (failure) throw IllegalStateException("network is down")
            return reply
        }

        override fun describe(): String = "fake-decision1"
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun `given a hosted decision-1 model that answers STRONG LONG when routed then the router returns STRONG_LONG and records the reason`() {
        val client = FakeClient(Decision1Reply("STRONG LONG", 0.91, "all four vectors converge"))
        val router = Decision1ApiRouter(client = client, fallback = DeterministicDecisionRouter())

        val state = router.router(bullish)

        assertEquals(DecisionState.STRONG_LONG, state)
        assertEquals(0.91, router.decisionConfidence, 1e-9)
        assertTrue("Reason must quote the model's explanation", router.lastReason.contains("all four vectors converge"))
        assertEquals("The client must have been asked exactly once", 1, client.calls)
    }

    @Test
    fun `given a model that answers NO TRADE when routed then the router honours the veto wording`() {
        val client = FakeClient(Decision1Reply("NO TRADE", 0.55, "disagreement too high"))
        val router = Decision1ApiRouter(client = client)

        assertEquals(DecisionState.NO_TRADE, router.router(bullish))
    }

    @Test
    fun `given a confidence above one when routed then the router clamps it into the valid range`() {
        val client = FakeClient(Decision1Reply("LONG", 1.7, "over-confident model"))
        val router = Decision1ApiRouter(client = client)

        router.router(bullish)

        assertEquals(1.0, router.decisionConfidence, 1e-9)
    }

    // ----------------------------------------------------------- failure paths

    @Test
    fun `given the api is unreachable when routed then the router falls back to the deterministic verdict instead of throwing`() {
        val fallback = DeterministicDecisionRouter()
        val router = Decision1ApiRouter(client = FakeClient(reply = null), fallback = fallback)

        val state = router.router(bullish)

        // The deterministic router sees converging, fresh, low-disagreement input.
        assertEquals(fallback.router(bullish), state)
        assertTrue("Fallback must be logged", router.lastReason.contains("unavailable"))
    }

    @Test
    fun `given the client throws when routed then the exception is swallowed and the deterministic verdict is returned`() {
        val fallback = DeterministicDecisionRouter()
        val router = Decision1ApiRouter(
            client = FakeClient(reply = null, failure = true),
            fallback = fallback
        )

        assertEquals(fallback.router(bullish), router.router(bullish))
    }

    @Test
    fun `given an unrecognised verdict when routed then the router refuses it and falls back`() {
        val fallback = DeterministicDecisionRouter()
        val client = FakeClient(Decision1Reply("MAYBE", 0.9, "not one of the six states"))
        val router = Decision1ApiRouter(client = client, fallback = fallback)

        val state = router.router(bullish)

        assertEquals(fallback.router(bullish), state)
        assertTrue("Unknown verdict must be reported", router.lastReason.contains("unknown verdict"))
    }

    @Test
    fun `given a slow model that never answers when routed then the call returns within the routing budget`() {
        val slow = object : Decision1Client {
            override suspend fun request(inputs: DecisionInputs): Decision1Reply? {
                delay(60_000) // far beyond the <100 ms routing target of §45
                return Decision1Reply("LONG", 0.9, "too late")
            }

            override fun describe(): String = "slow-decision1"
        }
        val router = Decision1ApiRouter(
            client = slow,
            fallback = DeterministicDecisionRouter(),
            timeoutMs = 120
        )

        val started = System.currentTimeMillis()
        val state = router.router(bullish)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("Routing must respect its timeout (took ${elapsed}ms)", elapsed < 5_000)
        assertTrue(
            "A timed-out call must resolve to a real state, not an error",
            state in DecisionState.values().toList()
        )
        assertTrue("Timeout must be logged", router.lastReason.contains("unavailable"))
    }

    // ------------------------------------------------------ interface contract

    @Test
    fun `given the six spec verdicts when mapped then every one is reachable from a model reply`() {
        val router = Decision1ApiRouter(client = FakeClient(Decision1Reply("LONG", 0.5, "")))
        val wording = mapOf(
            "STRONG LONG" to DecisionState.STRONG_LONG,
            "LONG" to DecisionState.LONG,
            "NEUTRAL" to DecisionState.NEUTRAL,
            "SHORT" to DecisionState.SHORT,
            "STRONG SHORT" to DecisionState.STRONG_SHORT,
            "NO TRADE" to DecisionState.NO_TRADE
        )

        wording.forEach { (wording1, expected) ->
            val perCall = Decision1ApiRouter(
                client = FakeClient(Decision1Reply(wording1, 0.7, "")),
                fallback = DeterministicDecisionRouter()
            )
            val state = perCall.router(bullish)
            assertTrue(
                "Model said '$wording1' but router answered $state",
                state == expected
            )
        }
    }
}
