package com.aistudio.alphapulse.domain.ensemble

import com.aistudio.alphapulse.domain.decision.CoinCalibrationOrchestrator
import com.aistudio.alphapulse.domain.decision.FakeNeedle3Bridge
import com.aistudio.alphapulse.domain.decision.Needle3Engine
import com.aistudio.alphapulse.domain.quant.KlineData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 30-day per-coin calibration, specified Given/When/Then (spec §20, §30).
 *
 * Three things are specified here, each independently checkable:
 *
 *  1. [EnsembleCalibration] turns measured error into a W4/W5 split without
 *     breaking the ΣW = 1.000 contract.
 *  2. [CoinCalibrationPipeline] really runs a rolling-context loop: the model that
 *     predicts the window better ends up with the larger share, at every horizon.
 *  3. [CoinCalibrationOrchestrator] lets Needle 3 pick the horizon set, and falls
 *     back to the deterministic plan whenever the engine cannot answer.
 */
class CoinCalibrationBddSpec {

    // ------------------------------------------------------------ weight rules

    @Test
    fun `given one model with a lower error when weights are derived then it receives the larger tsfm share and sigma stays one`() {
        val scores = listOf(
            ModelHorizonScore("chronos", "15m", mae = 1.0, rmse = 1.2, directionalAccuracy = 0.7, samples = 30),
            ModelHorizonScore("timesfm", "15m", mae = 4.0, rmse = 4.5, directionalAccuracy = 0.4, samples = 30)
        )

        val weights = EnsembleCalibration.weightsForHorizon(listOf("chronos", "timesfm"), scores)

        assertTrue(
            "Chronos has a quarter of TimesFM's MAE, so its share must be larger " +
                "(${weights.weight4} vs ${weights.weight5})",
            weights.weight4 > weights.weight5
        )
        assertEquals("The mounted budget is unchanged", 0.20, weights.weight4 + weights.weight5, 1e-9)
        assertEquals(
            "The core tri-factor keeps the remaining budget",
            0.80,
            weights.weightK + weights.weightP + weights.weightAlpha,
            1e-9
        )
        assertEquals(1.0, stateSum(weights), 1e-9)
        assertEquals(EnsembleCalibration.CALIBRATED_MODE, weights.mode)
    }

    @Test
    fun `given two models with the same error when weights are derived then the tsfm budget is split evenly`() {
        val scores = listOf(
            ModelHorizonScore("chronos", "15m", mae = 2.0, rmse = 2.5, directionalAccuracy = 0.5, samples = 20),
            ModelHorizonScore("timesfm", "15m", mae = 2.0, rmse = 2.5, directionalAccuracy = 0.5, samples = 20)
        )

        val weights = EnsembleCalibration.weightsForHorizon(listOf("chronos", "timesfm"), scores)

        assertEquals(0.10, weights.weight4, 1e-9)
        assertEquals(0.10, weights.weight5, 1e-9)
        assertEquals(1.0, stateSum(weights), 1e-9)
    }

    @Test
    fun `given only chronos mounted when weights are derived then timesfm stays zero and the core keeps its budget`() {
        val scores = listOf(
            ModelHorizonScore("chronos", "15m", mae = 1.5, rmse = 1.8, directionalAccuracy = 0.6, samples = 20)
        )

        val weights = EnsembleCalibration.weightsForHorizon(listOf("chronos"), scores)

        assertEquals(0.10, weights.weight4, 1e-9)
        assertEquals("An unmounted member never receives a share", 0.0, weights.weight5, 0.0)
        assertEquals(1.0, stateSum(weights), 1e-9)
    }

    @Test
    fun `given no usable score when weights are derived then the mount split is kept and nothing is invented`() {
        val weights = EnsembleCalibration.weightsForHorizon(listOf("chronos", "timesfm"), emptyList())

        assertEquals(0.10, weights.weight4, 1e-9)
        assertEquals(0.10, weights.weight5, 1e-9)
        assertEquals("Baseline Init", weights.mode)
        assertEquals(1.0, stateSum(weights), 1e-9)
    }

    @Test
    fun `given predictions against realised closes when scored then mae rmse and directional accuracy describe the error`() {
        val score = EnsembleCalibration.score(
            modelId = "chronos",
            horizonId = "15m",
            predictions = listOf(102.0, 98.0),
            actuals = listOf(101.0, 99.0),
            references = listOf(100.0, 100.0)
        )

        assertNotNull(score)
        assertEquals(1.0, score!!.mae, 1e-9)
        assertEquals(1.0, score.rmse, 1e-9)
        assertEquals("Both directions were called correctly", 1.0, score.directionalAccuracy, 1e-9)
        assertEquals(2, score.samples)
    }

    @Test
    fun `given no scored origins when a score is requested then null is returned instead of a zero sample rank`() {
        assertEquals(
            null,
            EnsembleCalibration.score("chronos", "15m", emptyList(), emptyList(), emptyList())
        )
    }

    // ------------------------------------------------------- the real pipeline

    @Test
    fun `given history across every horizon when calibration runs then the better model wins each horizon and sigma stays one`() = runBlocking {
        val plan = CoinCalibrationPlan(
            symbol = "BTCUSDT",
            windowDays = 30,
            horizons = CalibrationHorizons.ALL,
            modelIds = listOf("chronos", "timesfm"),
            dispatchedBy = CalibrationPlanSource.DETERMINISTIC,
            reason = "spec"
        )
        val candles = CalibrationHorizons.ALL.associate { horizon ->
            horizon.id to trendingKlines()
        }

        // Chronos tracks the fixture's 0.1% drift closely; TimesFM is far too low.
        val report = CoinCalibrationPipeline(
            forecaster = HorizonForecaster { modelId, closes, _ ->
                closes.last() * if (modelId == "chronos") 1.0012 else 0.995
            }
        ).run(plan, candles)

        assertEquals(CalibrationHorizons.ALL.size, report.horizons.size)
        assertEquals(CalibrationPlanSource.DETERMINISTIC, report.dispatchedBy)
        assertEquals("Every horizon was scored on real bars", 240, report.barsPerHorizon["15m"])

        for (horizon in report.horizons) {
            assertTrue(
                "Chronos must win ${horizon.horizonId} (w4=${horizon.weights.weight4}, " +
                    "w5=${horizon.weights.weight5})",
                horizon.weights.weight4 > horizon.weights.weight5
            )
            assertEquals(1.0, stateSum(horizon.weights), 1e-9)
            assertEquals(
                "Each horizon scored both models",
                setOf("chronos", "timesfm"),
                horizon.scores.map { it.modelId }.toSet()
            )
            assertTrue("A scored horizon has origins: ${horizon.horizonId}", horizon.samples > 0)
        }
    }

    @Test
    fun `given a horizon with too little history when calibration runs then the mount split is kept and zero samples are reported`() = runBlocking {
        val plan = CoinCalibrationPlan(
            symbol = "BTCUSDT",
            windowDays = 30,
            horizons = CalibrationHorizons.REFERENCE,
            modelIds = listOf("chronos", "timesfm"),
            dispatchedBy = CalibrationPlanSource.DETERMINISTIC,
            reason = "spec"
        )
        val shortHistory = CalibrationHorizons.REFERENCE.associate { it.id to trendingKlines(count = 10) }

        val report = CoinCalibrationPipeline(
            forecaster = HorizonForecaster { _, closes, _ -> closes.last() }
        ).run(plan, shortHistory)

        val horizon = report.horizons.single()
        assertEquals("Ten bars cannot be scored against a 32-bar context", 0, horizon.samples)
        assertEquals(0.10, horizon.weights.weight4, 1e-9)
        assertEquals(0.10, horizon.weights.weight5, 1e-9)
        assertEquals(10, report.barsPerHorizon["15m"])
    }

    // --------------------------------------------------- Needle 3 dispatches it

    @Test
    fun `given no mounted checkpoint when the plan is requested then every horizon is calibrated deterministically`() {
        val plan = CoinCalibrationOrchestrator(
            engine = Needle3Engine(FakeNeedle3Bridge()),
            weightsPathProvider = { null }
        ).plan("BTCUSDT", listOf("chronos", "timesfm"))

        assertEquals(CalibrationPlanSource.DETERMINISTIC, plan.dispatchedBy)
        assertEquals(CalibrationHorizons.ALL, plan.horizons)
        assertEquals(listOf("chronos", "timesfm"), plan.modelIds)
        assertEquals("The reason must say why the engine was not used", true, plan.reason.contains("no mounted checkpoint"))
    }

    @Test
    fun `given a mounted engine that chooses the intraday set when the plan is requested then only those horizons are calibrated`() {
        val orchestrator = orchestratorAnswering(
            """
            {"type":"call","success":true,
             "function_calls":[{"name":"calibrate_intraday_horizons","arguments":{}}],
             "suppressed_calls":[],"confidence":0.91,"reasoning":"short-horizon request",
             "validation":{"ungrounded":[]}}
            """.trimIndent()
        )

        val plan = orchestrator.plan("SOLUSDT", listOf("chronos"))

        assertEquals(CalibrationPlanSource.NEEDLE3, plan.dispatchedBy)
        assertEquals(listOf("1m", "5m", "15m"), plan.horizons.map { it.id })
        assertEquals(listOf("chronos"), plan.modelIds)
        assertTrue("The engine's own words are kept: ${plan.reason}", plan.reason.contains("calibrate_intraday_horizons"))
    }

    @Test
    fun `given an engine answer below the act threshold when the plan is requested then the deterministic plan runs`() {
        val orchestrator = orchestratorAnswering(
            """
            {"type":"call","success":true,
             "function_calls":[{"name":"calibrate_reference_horizon","arguments":{}}],
             "suppressed_calls":[],"confidence":0.20,"reasoning":"unsure",
             "validation":{"ungrounded":[]}}
            """.trimIndent()
        )

        val plan = orchestrator.plan("BTCUSDT", listOf("chronos", "timesfm"))

        assertEquals(CalibrationPlanSource.DETERMINISTIC, plan.dispatchedBy)
        assertEquals(CalibrationHorizons.ALL, plan.horizons)
        assertTrue("The reason names the threshold: ${plan.reason}", plan.reason.contains("act threshold"))
    }

    @Test
    fun `given no mounted model when the plan is requested then the engine is not asked and the plan is empty`() {
        val orchestrator = orchestratorAnswering("")

        val plan = orchestrator.plan("BTCUSDT", emptyList())

        assertTrue("Nothing mounted means nothing to score", plan.modelIds.isEmpty())
        assertEquals(CalibrationPlanSource.DETERMINISTIC, plan.dispatchedBy)
    }

    // ----------------------------------------------------------------- helpers

    private fun orchestratorAnswering(answer: String) = CoinCalibrationOrchestrator(
        engine = Needle3Engine(FakeNeedle3Bridge(answer = answer)),
        weightsPathProvider = { "/data/user/0/com.aistudio.alphapulse/files/models/needle3/needle3.cact" }
    )

    private fun stateSum(state: com.aistudio.alphapulse.domain.quant.ModelState): Double =
        state.weightK + state.weightP + state.weightAlpha + state.weight4 + state.weight5

    /** A rising series, one 0.1% step per bar, so a trend-following answer can win. */
    private fun trendingKlines(count: Int = 240): List<KlineData> {
        var price = 100.0
        return (0 until count).map { index ->
            price *= 1.001
            KlineData(
                openTime = 1_700_000_000_000L + index * 900_000L,
                open = price,
                high = price * 1.0002,
                low = price * 0.9998,
                close = price,
                volume = 10.0,
                quoteVolume = price * 10.0,
                takerBuyQuoteVolume = price * 5.0
            )
        }
    }
}
