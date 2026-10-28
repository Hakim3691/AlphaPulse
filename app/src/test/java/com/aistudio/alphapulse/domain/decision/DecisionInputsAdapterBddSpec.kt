package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behaviour of the §29 routing snapshot, specified as Given/When/Then.
 *
 * The dashboard verdict is only as trustworthy as the snapshot it is routed from.
 * The router's own rules are covered elsewhere; what was unpinned is the adapter
 * that turns a live evaluation into [DecisionInputs] - where two inputs have no
 * published value and are therefore derived. These scenarios fix that derivation
 * so it cannot drift into silently reporting conviction the evaluation does not
 * have: a balanced book carries no order-book conviction, an unmounted TSFM leg
 * has no direction to disagree with, and missing market data cannot be traded on.
 *
 * Spec Ref: §29 Quantitative Decision Router, §44 Failure Handling
 */
class DecisionInputsAdapterBddSpec {

    private fun snapshot(
        kinematicPct: Double = 0.42,
        tsfmPct: Double = 0.31,
        bidRatioPct: Double = 80.0,
        confluenceConfidence: Double = 0.80,
        regimeConfidence: Double = 0.60,
        coneBreaches: Int = 0,
        recentErrorPct: Double = 0.0,
        dataAvailable: Boolean = true
    ) = decisionInputsFor(
        kinematicPct = kinematicPct,
        tsfmPct = tsfmPct,
        bidRatioPct = bidRatioPct,
        confluenceConfidence = confluenceConfidence,
        regimeConfidence = regimeConfidence,
        coneBreaches = coneBreaches,
        recentErrorPct = recentErrorPct,
        dataAvailable = dataAvailable
    )

    @Test
    fun `given both legs point the same way when the snapshot is built then the forecasts pass through unchanged with no disagreement`() {
        val inputs = snapshot(kinematicPct = 0.42, tsfmPct = 0.31)

        assertEquals("The kinematic leg is the one the cone used", 0.42, inputs.triFactorForecast, 1e-12)
        assertEquals("The TSFM leg is the one the cone used", 0.31, inputs.tsfmForecast, 1e-12)
        assertEquals("Two legs pointing up do not disagree", 0.0, inputs.modelDisagreement, 1e-12)
    }

    @Test
    fun `given the legs point opposite ways when the snapshot is built then disagreement is total and the rules stand aside`() {
        val inputs = snapshot(kinematicPct = 0.42, tsfmPct = -0.31)

        assertEquals(1.0, inputs.modelDisagreement, 1e-12)
        assertEquals(
            "Opposing forecasts carry no tradable edge",
            DecisionState.NEUTRAL,
            DeterministicDecisionRouter().router(inputs)
        )
    }

    @Test
    fun `given no tsfm is mounted when the snapshot is built then its flat leg cannot disagree and the rules find no convergence`() {
        // With nothing mounted the TSFM leg contributes exactly zero drift, so there
        // is no second opinion to agree with - the rules must not claim convergence.
        val inputs = snapshot(kinematicPct = 0.42, tsfmPct = 0.0)

        assertEquals(0.0, inputs.modelDisagreement, 1e-12)
        assertEquals(
            DecisionState.NEUTRAL,
            DeterministicDecisionRouter().router(inputs)
        )
    }

    @Test
    fun `given the order book when the snapshot is built then conviction is the distance from a balanced book`() {
        assertEquals("A balanced book carries no conviction", 0.0, snapshot(bidRatioPct = 50.0).lobConfidence, 1e-12)
        assertEquals("A 60-40 book is a fifth of the way", 0.2, snapshot(bidRatioPct = 60.0).lobConfidence, 1e-12)
        assertEquals("A fully bid book is full conviction", 1.0, snapshot(bidRatioPct = 100.0).lobConfidence, 1e-12)
        assertEquals("A fully offered book is equally convicted", 1.0, snapshot(bidRatioPct = 0.0).lobConfidence, 1e-12)
        // Out-of-range input is clamped rather than allowed to score above full conviction.
        assertEquals(1.0, snapshot(bidRatioPct = 140.0).lobConfidence, 1e-12)
    }

    @Test
    fun `given a one-sided book and confident confluence when the snapshot is routed then a strong long is possible`() {
        val inputs = snapshot(bidRatioPct = 90.0, confluenceConfidence = 0.80)

        assertEquals(
            DecisionState.STRONG_LONG,
            DeterministicDecisionRouter().router(inputs)
        )
    }

    @Test
    fun `given no live price or history when the snapshot is built then freshness is zero and the router refuses to trade`() {
        val inputs = snapshot(dataAvailable = false)

        assertEquals(0.0, inputs.dataFreshness, 1e-12)
        assertEquals(
            DecisionState.NO_TRADE,
            DeterministicDecisionRouter().router(inputs)
        )
    }

    @Test
    fun `given out-of-range confidences when the snapshot is built then they are clamped into the unit interval`() {
        val inputs = snapshot(confluenceConfidence = 1.4, regimeConfidence = -0.2)

        assertEquals(1.0, inputs.liquidityConfidence, 1e-12)
        assertEquals(0.0, inputs.regimeConfidence, 1e-12)
    }

    @Test
    fun `given recent breaches and errors when the snapshot is built then the router's guards see them unchanged`() {
        val inputs = snapshot(coneBreaches = 3, recentErrorPct = 1.75)

        assertEquals(3, inputs.coneBreaches)
        assertEquals(1.75, inputs.recentErrors, 1e-12)
        assertEquals(
            "Three recent breaches are the router's stand-aside threshold",
            DecisionState.NO_TRADE,
            DeterministicDecisionRouter().router(inputs)
        )
    }
}
