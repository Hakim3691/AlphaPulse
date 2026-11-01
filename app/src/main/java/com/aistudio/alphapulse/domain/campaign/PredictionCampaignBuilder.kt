package com.aistudio.alphapulse.domain.campaign

import kotlin.math.abs

/**
 * §67 / Contract C-19 — campaigns assembled from AlphaPulse's forecasts, never from a
 * candle-derived opinion of their own.
 *
 * Why this exists
 * ---------------
 * ProactiveMS's original campaign generator read its own structure: swing clusters and
 * volume-profile levels decided the bias, and the forecast was a confirmation at best.
 * The unified system inverts that. The multi-horizon forecast is the thesis, so the
 * ladders have to be built *on* the confidence cone: a long board stages where the cone
 * says the downside is bounded, and its targets are the horizon targets the ensemble
 * actually published.
 *
 * Bias separates into two readings, and they are not interchangeable (§15):
 *
 *  - **tactical** (`15m`-`1h`) decides which boards may stage near price;
 *  - **strategic** (`4h`-`1d`) decides which side receives the larger budget.
 *
 * When they agree the template is one-sided. When they disagree the desk runs a split:
 * a tactical board that monetises and cancels quickly, and a strategic board on the
 * opposite side that holds the reserved capital. That split is the whole point of the
 * asymmetric templates, so it is modelled explicitly rather than averaged away.
 *
 * Nothing here decides whether orders may be armed: that is
 * [CampaignReadinessGate]'s verdict, and a campaign the gate has not staged stays
 * `PLANNED` with `canArmOrders = false` — built, visible, and unable to reach a venue.
 *
 * Spec Ref: §67 Prediction-Driven Campaign Generation, §15, §16.1, §17, C-19
 */

/** Which side of the market a campaign expresses. */
enum class CampaignDirection { LONG, SHORT }

/** The six campaign families of §14. */
enum class CampaignFamily(val direction: CampaignDirection) {
    SUPPORT_LONG(CampaignDirection.LONG),
    RECLAIM_LONG(CampaignDirection.LONG),
    BREAKOUT_LONG(CampaignDirection.LONG),
    RESISTANCE_SHORT(CampaignDirection.SHORT),
    RECLAIM_FADE_SHORT(CampaignDirection.SHORT),
    BREAKDOWN_SHORT(CampaignDirection.SHORT)
}

/** Which asymmetric board template the two biases select (§15.2). */
enum class BoardTemplate {
    BULL_DOMINANT,
    BEAR_DOMINANT,
    TACTICAL_BULL_STRATEGIC_BEAR,
    TACTICAL_BEAR_STRATEGIC_BULL,
    BALANCED
}

/**
 * One horizon of the forecast the campaign is built on.
 *
 * @param lowerBound the `-1 sigma` / `P25` edge of the cone
 * @param upperBound the `+1 sigma` / `P75` edge
 */
data class HorizonForecast(
    val horizonId: String,
    val minutes: Int,
    val predictedPrice: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val isBullish: Boolean
)

/**
 * Everything the builder is allowed to read.
 *
 * There is deliberately no candle series here: a campaign that could re-derive a bias
 * from price would eventually disagree with the dashboard showing the same asset.
 */
data class CampaignAssemblyInput(
    val symbol: String,
    val lastPrice: Double,
    val tacticalBias: Int,
    val strategicBias: Int,
    val forecasts: List<HorizonForecast>
)

/** A campaign that exists on the board, staged or not. */
data class PlannedCampaign(
    val symbol: String,
    val family: CampaignFamily,
    val direction: CampaignDirection,
    /** Passive slices, nearest to price first. */
    val entryLadder: List<Double>,
    /** The structural stop the ladder invalidates at. */
    val stopPrice: Double,
    /** `ProactiveMS_MaxLoss_R` expressed in price from the first slice. */
    val proactiveExitPrice: Double,
    /** The fixed `2.00R` backstop expressed in price from the first slice. */
    val hardStopPrice: Double,
    val targets: List<Double>,
    val tacticalBias: Int,
    val strategicBias: Int,
    val painPath: String,
    val lifecycleState: CampaignLifecycleState,
    val canArmOrders: Boolean
)

/**
 * Builds the board from the forecast and the readiness verdict.
 *
 * @param gate the §68 gate; the builder never decides armability itself
 */
class PredictionCampaignBuilder(private val gate: CampaignReadinessGate = CampaignReadinessGate()) {

    /** §15.2: the template the tactical/strategic split selects. */
    fun templateFor(tacticalBias: Int, strategicBias: Int): BoardTemplate {
        val tacticalBullish = tacticalBias >= BULLISH_THRESHOLD
        val tacticalBearish = tacticalBias <= -BULLISH_THRESHOLD
        val strategicBullish = strategicBias >= BULLISH_THRESHOLD
        val strategicBearish = strategicBias <= -BULLISH_THRESHOLD

        return when {
            tacticalBullish && strategicBullish -> BoardTemplate.BULL_DOMINANT
            tacticalBearish && strategicBearish -> BoardTemplate.BEAR_DOMINANT
            tacticalBullish && strategicBearish -> BoardTemplate.TACTICAL_BULL_STRATEGIC_BEAR
            tacticalBearish && strategicBullish -> BoardTemplate.TACTICAL_BEAR_STRATEGIC_BULL
            else -> BoardTemplate.BALANCED
        }
    }

    /**
     * @param telemetry readiness evidence for [governingHorizons]
     * @return at most one board per family the template allows, all carrying the gate's
     *   verdict — never armed just because a forecast was confident
     */
    fun build(
        input: CampaignAssemblyInput,
        telemetry: AlphaPulseReadinessTelemetry,
        governingHorizons: List<String>
    ): List<PlannedCampaign> {
        require(input.lastPrice > 0.0) { "${input.symbol}: a campaign needs a live price" }
        require(input.forecasts.isNotEmpty()) { "${input.symbol}: a campaign needs a forecast" }

        val verdict = gate.evaluate(telemetry, governingHorizons)
        val template = templateFor(input.tacticalBias, input.strategicBias)

        val bullish = bullishForecasts(input.forecasts)
        val bearish = bearishForecasts(input.forecasts)
        val families = familiesFor(template)

        return families.mapNotNull { family ->
            val direction = family.direction
            val cones = if (direction == CampaignDirection.LONG) bullish else bearish
            if (cones.isEmpty()) return@mapNotNull null
            assemble(input, family, cones, verdict)
        }
    }

    /** Families a template may stage: one-sided templates keep to their side. */
    private fun familiesFor(template: BoardTemplate): List<CampaignFamily> = when (template) {
        BoardTemplate.BULL_DOMINANT -> listOf(
            CampaignFamily.SUPPORT_LONG,
            CampaignFamily.RECLAIM_LONG,
            CampaignFamily.BREAKOUT_LONG
        )
        BoardTemplate.BEAR_DOMINANT -> listOf(
            CampaignFamily.RESISTANCE_SHORT,
            CampaignFamily.RECLAIM_FADE_SHORT,
            CampaignFamily.BREAKDOWN_SHORT
        )
        // A split keeps the tactical board on its own side and the strategic board on
        // the other, which is what makes the two budgets behave differently.
        BoardTemplate.TACTICAL_BULL_STRATEGIC_BEAR -> listOf(
            CampaignFamily.SUPPORT_LONG,
            CampaignFamily.RECLAIM_FADE_SHORT
        )
        BoardTemplate.TACTICAL_BEAR_STRATEGIC_BULL -> listOf(
            CampaignFamily.RESISTANCE_SHORT,
            CampaignFamily.RECLAIM_LONG
        )
        BoardTemplate.BALANCED -> listOf(
            CampaignFamily.SUPPORT_LONG,
            CampaignFamily.RESISTANCE_SHORT
        )
    }

    private fun bullishForecasts(forecasts: List<HorizonForecast>): List<HorizonForecast> =
        forecasts.filter { it.isBullish }.sortedBy { it.minutes }

    private fun bearishForecasts(forecasts: List<HorizonForecast>): List<HorizonForecast> =
        forecasts.filterNot { it.isBullish }.sortedBy { it.minutes }

    private fun assemble(
        input: CampaignAssemblyInput,
        family: CampaignFamily,
        cones: List<HorizonForecast>,
        verdict: GateEvaluationResult
    ): PlannedCampaign {
        val nearest = cones.first()
        val directional = cones.filter { if (family.direction == CampaignDirection.LONG) it.isBullish else !it.isBullish }

        val ladder: List<Double>
        val structuralStop: Double
        val targets: List<Double>
        if (family.direction == CampaignDirection.LONG) {
            // Stage into the cone's lower edge, then at the midpoint of the cone. A
            // passive bid never stages above the live price: when a cone edge sits above
            // market, the slice is clamped to price rather than crossing the spread.
            ladder = listOf(nearest.lowerBound, (nearest.lowerBound + nearest.predictedPrice) / 2.0)
                .map { minOf(it, input.lastPrice) }
                .distinct()
                .sortedDescending()
            structuralStop = directional.minOf { it.lowerBound }
            targets = directional.map { it.predictedPrice }.sorted()
        } else {
            ladder = listOf(nearest.upperBound, (nearest.upperBound + nearest.predictedPrice) / 2.0)
                .map { maxOf(it, input.lastPrice) }
                .distinct()
                .sorted()
            structuralStop = directional.maxOf { it.upperBound }
            targets = directional.map { it.predictedPrice }.sortedDescending()
        }

        val firstSlice = ladder.first()
        // 1R is the distance from the first slice to the structural stop; the proactive
        // tolerance and the backstop are both expressed from that same slice.
        val oneR = abs(firstSlice - structuralStop).coerceAtLeast(input.lastPrice * MIN_R_DISTANCE_FRACTION)
        val sign = if (family.direction == CampaignDirection.LONG) -1.0 else 1.0
        val proactiveExit = firstSlice + sign * oneR * PROACTIVE_MULTIPLE_R
        val hardStop = firstSlice + sign * oneR * HARD_STOP_MULTIPLE_R

        val painPath = buildString {
            append(if (family.direction == CampaignDirection.LONG) "Bullish " else "Bearish ")
            append("${nearest.horizonId} path toward ${format(nearest.predictedPrice)}")
            append(" (cone ${format(nearest.lowerBound)}..${format(nearest.upperBound)})")
        }

        return PlannedCampaign(
            symbol = input.symbol,
            family = family,
            direction = family.direction,
            entryLadder = ladder,
            stopPrice = structuralStop,
            proactiveExitPrice = proactiveExit,
            hardStopPrice = hardStop,
            targets = targets,
            tacticalBias = input.tacticalBias,
            strategicBias = input.strategicBias,
            painPath = painPath,
            lifecycleState = verdict.nextState,
            canArmOrders = verdict.canArmOrders
        )
    }

    private fun format(value: Double): String =
        Math.round(value * 100000.0).div(100000.0).toString()

    private companion object {
        const val BULLISH_THRESHOLD = 20

        /** §19.2 default candidate; the walk-forward sweep may promote another. */
        const val PROACTIVE_MULTIPLE_R = 0.50

        /** §19.3 the backstop is fixed and never widened by a campaign. */
        const val HARD_STOP_MULTIPLE_R = 2.00

        /** A degenerate cone still needs a non-zero R to express stops against. */
        const val MIN_R_DISTANCE_FRACTION = 0.001
    }
}
