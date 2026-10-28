package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the on-device decision router, specified as Given/When/Then.
 *
 * §29 has to stay a *replaceable interface*: no caller may be able to tell whether
 * the verdict came from Needle 3 or from the deterministic rules. Every scenario
 * below therefore checks two things at once - the right state comes back on a
 * healthy turn, and every failure degrades to the deterministic verdict with a
 * reason, never to an exception and never to an invented answer.
 *
 * The engine is driven through [FakeNeedle3Bridge], so these tests assert the
 * router's contract, not the network or the NDK.
 *
 * Spec Ref: §29 Quantitative Decision Router, §30 Needle / Agent Orchestration,
 * §33 Forecast Confidence Score, §44 Failure Handling.
 */
class Needle3DecisionRouterBddSpec {

    private val checkpoint = "/data/user/0/app/files/models/needle3/needle3.cact"

    /** Bullish on both models with healthy confidence - the rules say STRONG_LONG. */
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

    private val rules = DeterministicDecisionRouter()

    /** The engine's JSON for a routed turn. */
    private fun answer(
        tool: String,
        confidence: Double? = 0.82,
        reasoning: String = ""
    ): String = "{\"function_calls\":[{\"name\":\"$tool\",\"arguments\":{}}]," +
        "\"suppressed_calls\":[],\"reasoning\":\"$reasoning\"," +
        "\"confidence\":${confidence ?: "null"}}"

    private fun routerOn(
        engineAnswer: String?,
        engineLinked: Boolean = true,
        path: String? = checkpoint
    ): Pair<Needle3DecisionRouter, FakeNeedle3Bridge> {
        val bridge = FakeNeedle3Bridge(engineLinked = engineLinked, answer = engineAnswer)
        val router = Needle3DecisionRouter(
            engine = Needle3Engine(bridge),
            weightsPathProvider = { path }
        )
        return router to bridge
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun `given a confident strong long call when routed then the router returns STRONG_LONG and records the derivation`() {
        val (router, _) = routerOn(answer("decide_strong_long", 0.88, "both forecasts positive"))

        val state = router.router(bullish)

        assertEquals(DecisionState.STRONG_LONG, state)
        assertEquals(0.88, router.decisionConfidence, 1e-9)
        assertTrue(
            "Reason must name the tool: ${router.lastReason}",
            router.lastReason.contains("decide_strong_long")
        )
        assertTrue(
            "Reason must keep the model's derivation: ${router.lastReason}",
            router.lastReason.contains("both forecasts positive")
        )
        assertTrue(router.lastReason.startsWith("Needle 3:"))
    }

    @Test
    fun `given each of the six decision tools when chosen with confidence then the router returns that exact state`() {
        assertEquals(
            "One tool per route: six states, six tools",
            DecisionState.values().toSet(),
            Needle3DecisionRouter.ROUTE_TO_STATE.values.toSet()
        )

        Needle3DecisionRouter.ROUTE_TO_STATE.forEach { (tool, expected) ->
            val (router, _) = routerOn(answer(tool))
            assertEquals("Needle 3 chose $tool", expected, router.router(bullish))
        }
    }

    @Test
    fun `given another tool name when routed then the turn still lands on the mapped state`() {
        // Even a snapshot the deterministic rules would read as bullish is routed
        // to whatever the engine chose once its confidence clears the floor.
        val (router, _) = routerOn(answer("decide_short", 0.81, "forecasts turned negative"))

        assertEquals(DecisionState.SHORT, router.router(bullish))
    }

    // ----------------------------------------------------------- confidence gate

    @Test
    fun `given confidence exactly at the floor when routed then the route is executed`() {
        val (router, _) = routerOn(
            answer("decide_long", Needle3DecisionRouter.DEFAULT_CONFIDENCE_THRESHOLD)
        )

        assertEquals(DecisionState.LONG, router.router(bullish))
    }

    @Test
    fun `given confidence below the floor when routed then the deterministic rules answer instead`() {
        val (router, _) = routerOn(answer("decide_long", 0.42, "not very sure"))

        val state = router.router(bullish)

        assertEquals(rules.router(bullish), state)
        assertTrue(
            "The gate must say why it deferred: ${router.lastReason}",
            router.lastReason.contains("below the 0.70 act threshold")
        )
        assertEquals("A deferred route reports the fallback's confidence", 0.0, router.decisionConfidence, 0.0)
    }

    @Test
    fun `given a confidence above one when routed then it is clamped into the valid range`() {
        val (router, _) = routerOn(answer("decide_long", 1.7, "over-confident model"))

        assertEquals(DecisionState.LONG, router.router(bullish))
        assertEquals(1.0, router.decisionConfidence, 1e-9)
    }

    @Test
    fun `given lo ra weights with no calibrated head when routed then the grammar-constrained call is honoured and reported as unscored`() {
        // A fine-tuned .cact invalidates the post-hoc head, so the engine sends
        // `confidence: null`. The call is still grammar-constrained and usable; the
        // router must not pretend it carries a calibrated score.
        val (router, _) = routerOn(answer("decide_neutral", confidence = null))

        assertEquals(DecisionState.NEUTRAL, router.router(bullish))
        assertEquals(Needle3DecisionRouter.UNSCORED_CONFIDENCE, router.decisionConfidence, 0.0)
        assertTrue(
            "The reason must admit there is no calibrated number: ${router.lastReason}",
            router.lastReason.contains("no calibrated confidence")
        )
    }

    // --------------------------------------------------------- typed refusals

    @Test
    fun `given no mounted checkpoint when routed then the engine is never called and the rules answer`() {
        val (router, bridge) = routerOn(answer("decide_long"), path = null)

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(
            "The reason must name the missing checkpoint: ${router.lastReason}",
            router.lastReason.contains("no mounted checkpoint")
        )
        assertTrue("No checkpoint means no native call, but got ${bridge.calls}", bridge.calls.isEmpty())
    }

    @Test
    fun `given a blank checkpoint path when routed then it is treated exactly like no checkpoint`() {
        val (router, bridge) = routerOn(answer("decide_long"), path = "   ")

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun `given an abi without the cactus engine when routed then the rules answer and the blocker is reported`() {
        val (router, _) = routerOn(answer("decide_long"), engineLinked = false)

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(
            "The reason must say the engine could not run: ${router.lastReason}",
            router.lastReason.contains("could not run")
        )
    }

    @Test
    fun `given the generation fails when routed then the rules answer rather than an error reaching the caller`() {
        val (router, _) = routerOn(engineAnswer = null)

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(router.lastReason.contains("could not run"))
    }

    @Test
    fun `given the engine withheld the call into suppressed calls when routed then the rules answer`() {
        val suppressed = "{\"function_calls\":[],\"suppressed_calls\":[" +
            "{\"name\":\"decide_long\",\"arguments\":{}}],\"reasoning\":\"\"," +
            "\"confidence\":0.05}"
        val (router, _) = routerOn(suppressed)

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(
            "The withheld tool must be named: ${router.lastReason}",
            router.lastReason.contains("withheld") && router.lastReason.contains("decide_long")
        )
    }

    @Test
    fun `given an ungrounded argument when routed then the route is refused`() {
        val ungrounded = "{\"function_calls\":[{\"name\":\"decide_long\",\"arguments\":{}}]," +
            "\"suppressed_calls\":[],\"reasoning\":\"\",\"confidence\":0.9," +
            "\"validation\":{\"ungrounded\":[\"decide_long.confidence\"]}}"
        val (router, _) = routerOn(ungrounded)

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(
            "The ungrounded field must be named: ${router.lastReason}",
            router.lastReason.contains("could not ground")
        )
    }

    @Test
    fun `given more than one route when routed then neither is executed`() {
        val twoCalls = "{\"function_calls\":[" +
            "{\"name\":\"decide_long\",\"arguments\":{}}," +
            "{\"name\":\"decide_short\",\"arguments\":{}}]," +
            "\"suppressed_calls\":[],\"reasoning\":\"\",\"confidence\":0.9}"
        val (router, _) = routerOn(twoCalls)

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(router.lastReason.contains("exactly one"))
    }

    @Test
    fun `given a tool outside the six routes when routed then it is refused by name`() {
        val (router, _) = routerOn(answer("route_decision", 0.95))

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(
            "The refused name must be quoted: ${router.lastReason}",
            router.lastReason.contains("route_decision") && router.lastReason.contains("not one of the six")
        )
    }

    @Test
    fun `given an answer that is not routing json when routed then it is refused instead of interpreted`() {
        val (router, _) = routerOn("Sure! The market looks bullish to me.")

        assertEquals(rules.router(bullish), router.router(bullish))
        assertTrue(router.lastReason.contains("not the promised routing JSON"))
    }

    @Test
    fun `given nonsense from the engine when routed then every turn still resolves to one of the six states`() {
        listOf("", "{", "null", "[]", "{\"function_calls\":null}", "{\"reasoning\":\"hi\"}")
            .forEach { garbage ->
                val (router, _) = routerOn(garbage)
                val state = router.router(bullish)
                assertTrue(
                    "Answer '$garbage' must resolve to a real state, got $state",
                    state in DecisionState.values().toList()
                )
                assertEquals(
                    "Answer '$garbage' must not silently invent a route",
                    rules.router(bullish),
                    state
                )
            }
    }

    // ------------------------------------------------------------ the catalogue

    @Test
    fun `given the routing catalogue when inspected then every state has its own tool and none requires a grounded argument`() {
        assertEquals(6, Needle3DecisionRouter.ROUTE_TO_STATE.size)

        Needle3DecisionRouter.ROUTE_TO_STATE.keys.forEach { tool ->
            assertTrue(
                "The catalogue must declare $tool",
                Needle3DecisionRouter.ROUTING_TOOLS_JSON.contains("\"name\": \"$tool\"")
            )
        }
        assertFalse(
            "A catch-all route_decision tool would force the model to infer an abstract enum",
            Needle3DecisionRouter.ROUTING_TOOLS_JSON.contains("\"name\": \"route_decision\"")
        )
        // Required arguments can only be satisfied by a literal span, and the
        // snapshot offers none, so an argument-free schema cannot be suppressed
        // for a missing grounding.
        assertFalse(
            "No parameter may be required: ${Needle3DecisionRouter.ROUTING_TOOLS_JSON}",
            Needle3DecisionRouter.ROUTING_TOOLS_JSON.contains("\"required\": [\"")
        )
        assertTrue(Needle3DecisionRouter.ROUTING_TOOLS_JSON.contains("\"required\": []"))
    }

    // ------------------------------------------------------------ attribution

    @Test
    fun `given needle answered above the floor when routed then the verdict is attributed to needle`() {
        val (router, _) = routerOn(answer("decide_long"))

        router.router(bullish)

        assertEquals(DecisionSource.NEEDLE3, router.decisionSource)
    }

    @Test
    fun `given every refusal path when routed then the verdict is attributed to the deterministic rules`() {
        // Attribution is what makes the audit ledger readable: a NO_TRADE from the
        // rules must never be indistinguishable from one the model chose.
        val refusals = listOf(
            routerOn(answer("decide_long"), path = null),
            routerOn(answer("decide_long"), engineLinked = false),
            routerOn(answer("decide_long", 0.20)),
            routerOn("not json at all")
        )

        refusals.forEach { (router, _) ->
            router.router(bullish)
            assertEquals(
                "A refused route is the rules answering: ${router.lastReason}",
                DecisionSource.DETERMINISTIC,
                router.decisionSource
            )
        }
    }

    // ------------------------------------------------------ shared engine session

    @Test
    fun `given the narrator ran first when a route is asked for then the engine restarts its grammar instead of reusing the narrative prefix`() {
        val combined = answer("decide_long", 0.80, "both forecasts positive")
        val bridge = FakeNeedle3Bridge(answer = combined)
        val engine = Needle3Engine(bridge)
        engine.generateBrief(
            Needle3Request(
                symbol = "BTCUSDT",
                regime = "Momentum Burst",
                kinematic = 0.00042,
                painPath = 0.00031,
                alpha = 0.00028,
                tsfm = 0.00051,
                lobConfidence = 0.87,
                liquidationBias = "UPPER",
                coneBreaches = 2
            ),
            checkpoint
        )

        val router = Needle3DecisionRouter(
            engine = engine,
            weightsPathProvider = { checkpoint }
        )

        assertEquals(DecisionState.LONG, router.router(bullish))
        assertEquals(
            "A different grammar must reset the session",
            1,
            bridge.calls.count { it == "reset" }
        )
        assertEquals(
            "The routing prefix must be tokenized as its own session",
            2,
            bridge.calls.count { it == "beginSession" }
        )
        assertEquals(
            "The checkpoint is still mapped once",
            1,
            bridge.calls.count { it.startsWith("loadWeights(") }
        )
    }
}
