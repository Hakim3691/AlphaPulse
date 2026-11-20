package com.aistudio.alphapulse.domain.screener

/**
 * §65 / Contract C-17 — the unified institutional universe gate.
 *
 * Why this exists
 * ---------------
 * The ProactiveMS reference screened a fixed mid-cap band: $50M-$750M quote volume.
 * That band was a proxy for "liquid enough to trade, small enough to move", and it
 * silently excluded the assets a campaign desk most wants to run — BTC and the large
 * caps — while letting through thin coins purely because they were small. §65 replaces
 * the band with what the band was trying to approximate: verified institutional flow,
 * a risk-adjusted hurdle, and an execution-quality guardrail.
 *
 * What replaces the band
 * ----------------------
 * Four intraday filters, all of which must hold today (or on eight of the last ten
 * days, so one quiet session does not evict a coin that is otherwise persistent):
 *
 *  - $5M daily quote volume — the absolute floor below which a ladder cannot be staged
 *    without the market noticing;
 *  - taker-buy ratio at or above 0.50 — aggressive flow is buying, not selling;
 *  - volume-to-open-interest velocity above 1.0 — new money is arriving rather than
 *    positions merely rolling over;
 *  - 40% conviction displacement — the close finished away from the open relative to
 *    the day's range, which is what a directional day looks like.
 *
 * On top of the flow check, the coin must clear a risk-adjusted hurdle over the last
 * year: positive Calmar (return per unit of drawdown) and positive Sharpe over the
 * risk-free rate. A coin can pass every flow filter and still be a bad campaign simply
 * because its return did not pay for its volatility, which is why both are required.
 *
 * Execution quality is a separate flag rather than a rejection: [ScreenedAssetVerdict]
 * carries `passiveStagingEligible`, false when the L2 spread is 0.10% or wider or the
 * coin has no active funding history. The coin stays in the universe — it can still be
 * traded tactically — but a passive campaign may not stage into a spread it cannot
 * cross.
 *
 * There is no upper volume bound: the legacy $750M ceiling is deliberately absent.
 *
 * Spec Ref: §65 Unified Universe Screener, C-17
 */

/**
 * One candidate coin: today's flow, the last ten days of it, and the yearly
 * risk-adjusted inputs.
 */
data class CandidateAssetInput(
    val symbol: String,
    val todaySnapshot: DailyFilterSnapshot,
    val last10DaysSnapshots: List<DailyFilterSnapshot>,
    val annualizedReturn1Y: Double,
    val maxDrawdown1Y: Double,
    val annualizedVolatility1Y: Double,
    val orderBookSpreadPct: Double,
    val hasBinanceSpotAndFutures: Boolean,
    val hasCoinGlassFuturesFeed: Boolean,
    val hasActiveFundingHistory: Boolean
) {
    /** Return per unit of drawdown; -1.0 when there is no drawdown to divide by. */
    val calmarRatio1Y: Double
        get() = if (maxDrawdown1Y > 0.0) annualizedReturn1Y / maxDrawdown1Y else -1.0

    /** Excess return per unit of volatility; -1.0 when there is no volatility. */
    fun sharpeRatio1Y(riskFreeRate: Double = 0.04): Double {
        return if (annualizedVolatility1Y > 0.0) {
            (annualizedReturn1Y - riskFreeRate) / annualizedVolatility1Y
        } else {
            -1.0
        }
    }
}

/** What the gate decided about one candidate. */
data class ScreenedAssetVerdict(
    val symbol: String,
    val verifiedInstitutionalFlow: Boolean,
    val passesRiskAdjustedHurdle: Boolean,
    val passiveStagingEligible: Boolean,
    val calmar: Double,
    val sharpe: Double
)

/**
 * Applies §65 to a candidate list and returns the admitted coins, best Sharpe first.
 *
 * @param riskFreeRate the rate the Sharpe hurdle is measured against
 * @param requireStrict8Of10PersistenceWhenTodayFails when true (the §65 rule), a coin
 *   that fails today's flow gate still qualifies if eight of the last ten days passed;
 *   when false, a single historical pass is enough. The strict setting is the default
 *   because the looser one lets a coin in on one lucky session.
 */
class UnifiedUniverseScreener(
    private val riskFreeRate: Double = 0.04,
    private val requireStrict8Of10PersistenceWhenTodayFails: Boolean = true
) {

    /**
     * @param btcOnlyOverride the desk's "BTC only" switch: the gate still runs, so a
     *   BTC-only book never arms on a BTC that would not otherwise qualify
     */
    fun screenUniverse(
        candidates: List<CandidateAssetInput>,
        btcOnlyOverride: Boolean = false
    ): List<ScreenedAssetVerdict> {
        val filtered = candidates.mapNotNull { candidate ->
            // Both venue feeds are inclusion requirements, not preferences: a coin the
            // app cannot price on spot and futures cannot be hedged.
            if (!candidate.hasBinanceSpotAndFutures || !candidate.hasCoinGlassFuturesFeed) {
                return@mapNotNull null
            }

            val assessment = ScreenedUniverseFilter.assess(
                today = candidate.todaySnapshot,
                last10Days = candidate.last10DaysSnapshots,
                requireStrict8Of10PersistenceWhenTodayFails =
                    requireStrict8Of10PersistenceWhenTodayFails
            )
            val verifiedFlow = assessment.verified

            val calmar = candidate.calmarRatio1Y
            val sharpe = candidate.sharpeRatio1Y(riskFreeRate)
            val passesRiskAdjusted = calmar > 0.0 && sharpe > 0.0

            if (!verifiedFlow || !passesRiskAdjusted) {
                return@mapNotNull null
            }

            val stagingEligible = candidate.orderBookSpreadPct < MAX_PASSIVE_STAGING_SPREAD_PCT &&
                candidate.hasActiveFundingHistory
            ScreenedAssetVerdict(
                symbol = candidate.symbol,
                verifiedInstitutionalFlow = true,
                passesRiskAdjustedHurdle = true,
                passiveStagingEligible = stagingEligible,
                calmar = calmar,
                sharpe = sharpe
            )
        }

        // Best risk-adjusted candidate first: the campaign queue is built in this order.
        val ranked = filtered.sortedByDescending { it.sharpe }

        return if (btcOnlyOverride) {
            ranked.filter { it.symbol == BTC_ONLY_SYMBOL }
        } else {
            ranked
        }
    }

    private companion object {
        /** A passive ladder may not stage into a spread at or above this width. */
        const val MAX_PASSIVE_STAGING_SPREAD_PCT = 0.10

        const val BTC_ONLY_SYMBOL = "BTCUSDT"
    }
}
