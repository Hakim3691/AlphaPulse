package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §67 / Contract C-19 — campaigns are assembled from the forecast cone, and the §68 gate
 * alone decides whether they may arm orders.
 *
 * The builder lives in production (`domain/campaign/PredictionCampaignBuilder.kt`);
 * this spec drives it, so a campaign that invents its own bias, ignores the template
 * split, or arms without the gate's verdict breaks the build.
 *
 * Spec Ref: §67, §15, §16.1, C-19
 */
class PredictionCampaignBuilderBddSpec {

    private fun forecast(
        horizonId: String,
        minutes: Int,
        predicted: Double,
        lower: Double,
        upper: Double
    ) = HorizonForecast(
        horizonId = horizonId,
        minutes = minutes,
        predictedPrice = predicted,
        lowerBound = lower,
        upperBound = upper,
        isBullish = predicted >= (lower + upper) / 2.0
    )

    // Cones straddle the live price at 100.0, which is what a real forecast looks like:
    // the downside edge is where a long board may stage, the centre is the target.
    private val bullishForecasts = listOf(
        forecast("15m", 15, 101.5, 98.6, 103.4),
        forecast("30m", 30, 103.0, 98.2, 104.2),
        forecast("1h", 60, 105.0, 98.0, 106.4)
    )

    private val mixedForecasts = bullishForecasts + listOf(
        forecast("5m", 5, 98.6, 97.9, 99.4)
    )

    private fun readyTelemetry() = AlphaPulseReadinessTelemetry(
        settled1mCount = 4,
        settled5mCount = 2,
        settled15mCount = 3,
        settled30mCount = 2,
        settled1hCount = 2,
        readyEnsembleModels = 5,
        totalEnsembleModels = 5,
        readyTsfmDockModels = 2,
        requiredTsfmDockModels = 2,
        horizonConeHitRatePct = mapOf("15m" to 93.0, "30m" to 94.0, "1h" to 92.5)
    )

    private fun input(tactical: Int, strategic: Int) = CampaignAssemblyInput(
        symbol = "BTCUSDT",
        lastPrice = 100.0,
        tacticalBias = tactical,
        strategicBias = strategic,
        forecasts = bullishForecasts
    )

    @Test
    fun `given tactical and strategic bias both bullish when the board is built then only long families are staged with entries below price`() {
        val builder = PredictionCampaignBuilder()

        val campaigns = builder.build(
            input(tactical = 45, strategic = 60),
            readyTelemetry(),
            governingHorizons = listOf("15m", "30m", "1h")
        )

        assertEquals(BoardTemplate.BULL_DOMINANT, builder.templateFor(45, 60))
        assertTrue(campaigns.isNotEmpty())
        campaigns.forEach { campaign ->
            assertEquals(CampaignDirection.LONG, campaign.direction)
            assertTrue(
                "A long ladder never stages above the live price: ${campaign.entryLadder}",
                campaign.entryLadder.all { it <= 100.0 }
            )
            assertEquals(
                "Slices are nearest-first",
                campaign.entryLadder.sortedDescending(),
                campaign.entryLadder
            )
            assertEquals(CampaignLifecycleState.STAGED, campaign.lifecycleState)
            assertTrue(campaign.canArmOrders)
        }
    }

    @Test
    fun `given tactical bias bullish but strategic bias bearish when the board is built then a split template stages one board per side`() {
        val builder = PredictionCampaignBuilder()

        val campaigns = builder.build(
            CampaignAssemblyInput(
                symbol = "BTCUSDT",
                lastPrice = 100.0,
                tacticalBias = 40,
                strategicBias = -55,
                forecasts = mixedForecasts
            ),
            readyTelemetry(),
            governingHorizons = listOf("15m", "30m", "1h")
        )

        assertEquals(BoardTemplate.TACTICAL_BULL_STRATEGIC_BEAR, builder.templateFor(40, -55))
        assertEquals(
            setOf(CampaignFamily.SUPPORT_LONG, CampaignFamily.RECLAIM_FADE_SHORT),
            campaigns.map { it.family }.toSet()
        )
        assertTrue(campaigns.any { it.direction == CampaignDirection.LONG })
        assertTrue(campaigns.any { it.direction == CampaignDirection.SHORT })
    }

    @Test
    fun `given a governing horizon cone hit rate at or below 90 percent when campaigns are built then they are planned but cannot arm orders`() {
        val builder = PredictionCampaignBuilder()
        val unready = readyTelemetry().copy(
            horizonConeHitRatePct = mapOf("15m" to 89.4, "30m" to 94.0, "1h" to 92.5)
        )

        val campaigns = builder.build(
            input(tactical = 45, strategic = 60),
            unready,
            governingHorizons = listOf("15m", "30m", "1h")
        )

        assertTrue("The board is still assembled for the operator", campaigns.isNotEmpty())
        campaigns.forEach { campaign ->
            assertEquals(CampaignLifecycleState.PLANNED, campaign.lifecycleState)
            assertFalse(campaign.canArmOrders)
        }
    }

    @Test
    fun `given a staged campaign when its stops are read then the proactive tolerance is half an R and the backstop is exactly two R from the first slice`() {
        val builder = PredictionCampaignBuilder()

        val campaign = builder.build(
            input(tactical = 45, strategic = 60),
            readyTelemetry(),
            governingHorizons = listOf("15m", "30m", "1h")
        ).first()

        val firstSlice = campaign.entryLadder.first()
        val oneR = Math.abs(firstSlice - campaign.stopPrice)
        assertEquals(firstSlice - oneR * 0.50, campaign.proactiveExitPrice, 1e-9)
        assertEquals(firstSlice - oneR * 2.00, campaign.hardStopPrice, 1e-9)
        assertTrue(
            "The backstop must sit beyond the proactive exit for a long board",
            campaign.hardStopPrice < campaign.proactiveExitPrice
        )
        assertTrue("Targets come from the forecast ladder", campaign.targets.isNotEmpty())
        assertTrue(campaign.painPath.contains("15m"))
    }
}
