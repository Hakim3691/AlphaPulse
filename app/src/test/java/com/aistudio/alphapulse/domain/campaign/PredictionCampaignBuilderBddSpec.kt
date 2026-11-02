package com.aistudio.alphapulse.domain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BDD Guard Spec for §67:
 * Prediction-Driven Campaign Generator (6 Families & Asymmetric Boards A-D).
 */
class PredictionCampaignBuilderBddSpec {

    enum class CampaignFamily {
        SUPPORT_LONG,
        RESISTANCE_SHORT,
        RECLAIM_LONG,
        RECLAIM_FADE_SHORT,
        BREAKOUT_LONG,
        BREAKDOWN_SHORT
    }

    enum class BoardRole(val minBudgetPct: Int, val maxBudgetPct: Int) {
        PRIMARY(40, 60),
        SECONDARY(20, 35),
        DEFENSIVE(5, 20),
        DISABLED(0, 0)
    }

    enum class AsymmetricBoardTemplate {
        A_BULL_DOMINANT,
        B_BEAR_DOMINANT,
        C_TACTICAL_BULL_STRATEGIC_BEAR,
        D_TACTICAL_BEAR_STRATEGIC_BULL
    }

    data class HorizonForecastEnvelope(
        val horizon: String,
        val spotPrice: Double,
        val targetPrice: Double,
        val upper1Sigma: Double,
        val lower1Sigma: Double,
        val upper95Cone: Double,
        val lower95Cone: Double,
        val confidence: Double,
        val painVectorPct: Double
    ) {
        val expectedReturnPct: Double
            get() = ((targetPrice - spotPrice) / spotPrice) * 100.0
    }

    data class PlannedCampaign(
        val symbol: String,
        val family: CampaignFamily,
        val direction: String,
        val boardRole: BoardRole,
        val entryLadder: List<Double>,
        val targetLadder: List<Double>,
        val initialStop1R: Double,
        val tacticalBias: Int,
        val strategicBias: Int,
        val painPathDescription: String
    )

    class PredictionCampaignBuilder {
        fun computeTacticalBias(forecasts15mTo1h: List<HorizonForecastEnvelope>): Int {
            val weightedSlope = forecasts15mTo1h.sumOf { it.expectedReturnPct * it.confidence } /
                forecasts15mTo1h.size.coerceAtLeast(1)
            return (weightedSlope * 25.0).toInt().coerceIn(-100, 100)
        }

        fun selectBoardTemplate(tacticalBias: Int, strategicBias: Int): AsymmetricBoardTemplate {
            return when {
                tacticalBias >= 20 && strategicBias >= 20 -> AsymmetricBoardTemplate.A_BULL_DOMINANT
                tacticalBias <= -20 && strategicBias <= -20 -> AsymmetricBoardTemplate.B_BEAR_DOMINANT
                tacticalBias >= 20 && strategicBias <= -20 -> AsymmetricBoardTemplate.C_TACTICAL_BULL_STRATEGIC_BEAR
                else -> AsymmetricBoardTemplate.D_TACTICAL_BEAR_STRATEGIC_BULL
            }
        }

        fun buildSupportLong(
            symbol: String,
            f15m: HorizonForecastEnvelope,
            f30m: HorizonForecastEnvelope,
            f1h: HorizonForecastEnvelope,
            valLevel: Double,
            tacticalBias: Int,
            strategicBias: Int,
            role: BoardRole
        ): PlannedCampaign {
            val slice1 = f15m.lower1Sigma
            val slice2 = (f15m.lower1Sigma + valLevel) / 2.0
            val slice3 = valLevel
            return PlannedCampaign(
                symbol = symbol,
                family = CampaignFamily.SUPPORT_LONG,
                direction = "LONG",
                boardRole = role,
                entryLadder = listOf(slice1, slice2, slice3),
                targetLadder = listOf(f15m.targetPrice, f30m.targetPrice, f1h.targetPrice),
                initialStop1R = f30m.lower95Cone,
                tacticalBias = tacticalBias,
                strategicBias = strategicBias,
                painPathDescription = "Upward mean reversion (pain ${(f15m.painVectorPct * 100).toInt()})"
            )
        }
    }

    @Test
    fun `given AlphaPulse 15m 30m 1h Penta Factor forecasts and cones when building Support Long campaign then entry ladder targets and 1R stop anchor to prediction envelopes`() {
        val builder = PredictionCampaignBuilder()
        val f15m = HorizonForecastEnvelope("15m", 1.15000, 1.16363, 1.17500, 1.14200, 1.19000, 1.12500, 0.892, 0.42)
        val f30m = HorizonForecastEnvelope("30m", 1.15000, 1.17100, 1.18500, 1.14000, 1.20100, 1.11800, 0.885, 0.48)
        val f1h = HorizonForecastEnvelope("1h", 1.15000, 1.18400, 1.19900, 1.13800, 1.21500, 1.11000, 0.870, 0.55)

        val tacticalBias = builder.computeTacticalBias(listOf(f15m, f30m, f1h))
        val campaign = builder.buildSupportLong(
            symbol = "SOPHUSDT",
            f15m = f15m,
            f30m = f30m,
            f1h = f1h,
            valLevel = 1.13600,
            tacticalBias = tacticalBias,
            strategicBias = 35,
            role = BoardRole.PRIMARY
        )

        assertTrue(tacticalBias > 0)
        assertEquals(CampaignFamily.SUPPORT_LONG, campaign.family)
        assertEquals(3, campaign.entryLadder.size)
        assertEquals(1.14200, campaign.entryLadder.first(), 1e-5)
        assertEquals(listOf(1.16363, 1.17100, 1.18400), campaign.targetLadder)
        assertEquals(1.11800, campaign.initialStop1R, 1e-5)
    }

    @Test
    fun `given split tactical bullish and strategic bearish biases when selecting asymmetric board template then Template C is armed`() {
        val builder = PredictionCampaignBuilder()

        val template = builder.selectBoardTemplate(tacticalBias = 48, strategicBias = -45)

        assertEquals(AsymmetricBoardTemplate.C_TACTICAL_BULL_STRATEGIC_BEAR, template)
    }
}