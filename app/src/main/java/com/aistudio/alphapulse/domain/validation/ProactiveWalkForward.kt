package com.aistudio.alphapulse.domain.validation

/**
 * §70 / Contract C-21 — the walk-forward sweep that chooses `ProactiveMS_MaxLoss_R`.
 *
 * Why this exists
 * ---------------
 * A single parameter value can always be found that looks best on one window. Choosing
 * it that way over-fits, and the cost shows up as a live drawdown rather than in the
 * backtest. So the sweep evaluates every candidate across non-overlapping folds and
 * promotes the value that sits on a *plateau*: the one whose neighbours are nearly as
 * good, which is the signature of a real effect rather than a lucky window.
 *
 * The fixed 2.00R hard stop is recorded on every configuration and is never swept,
 * because a wider stop would flatter the proactive threshold's numbers by moving the
 * loss it is supposed to prevent.
 *
 * This file declares the sweep's shape and the promotion rule. The fold mechanics live
 * in [WalkForwardValidator], and no-look-ahead is enforced by [LeakageGuard] — one
 * implementation each, deliberately, so there is no second opinion about what leakage is.
 *
 * Spec Ref: §70 Walk-Forward 8-Configuration Matrix, §50, §51
 */

/**
 * The eight configurations §70 requires.
 *
 * @param proactiveThresholdR the `ProactiveMS_MaxLoss_R` under test; null for the
 *   baseline, which never exits proactively.
 * @param hardStopR the fixed backstop — identical on every configuration.
 */
enum class WalkForwardConfigId(
    val label: String,
    val proactiveThresholdR: Double?,
    val hardStopR: Double = 2.00
) {
    BASELINE("Baseline", null),
    P1("P1 0.25R", 0.25),
    P2("P2 0.50R", 0.50),
    P3("P3 0.75R", 0.75),
    P4("P4 1.00R", 1.00),
    P5("P5 1.25R", 1.25),
    P6("P6 1.50R", 1.50),
    HARD_STOP_ONLY("Hard-stop only 2.00R", 2.00);

    companion object {
        /** The candidates eligible to become the production threshold. */
        val SWEPT: List<WalkForwardConfigId> = listOf(P1, P2, P3, P4, P5, P6)
    }
}

/**
 * The 21 metrics §70 records for one configuration over one fold, plus the
 * configuration they belong to.
 *
 * The post-exit counters are the point of the sweep: a proactive exit that was
 * followed by a further -2R move saved a stop, and one that was followed by a
 * recovery cost profit. Both are recorded rather than only the flattering one.
 */
data class WalkForwardReport21Metrics(
    val configId: WalkForwardConfigId,
    val netPnlR: Double,
    val expectancyPerTradeR: Double,
    val profitFactor: Double,
    val winRate: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val maxDrawdownR: Double,
    val sharpeRatio: Double,
    val calmarRatio: Double,
    val totalTrades: Int,
    val proactiveExitCount: Int,
    val proactiveExitPct: Double,
    val avgRAtProactiveExit: Double,
    /** Shadow tracking: exits that would subsequently have reached the -2R stop. */
    val pctProactiveExitsSubsequentlyReachedMinus2R: Double,
    /** The other half of that ledger: exits that would have recovered. */
    val pctProactiveExitsSubsequentlyRecoveredToProfit: Double,
    val missedProfitOpportunityCostR: Double,
    val avgAdverseExcursionMaeR: Double,
    val avgFavorableExcursionMfeR: Double,
    val hardStopFrequencyPct: Double,
    val maxConsecutiveLosses: Int,
    val oosDegradationVsTrainPct: Double
)

/**
 * Picks the production candidate by out-of-sample stability, not by peak return.
 *
 * Each candidate is scored on its mean fold expectancy and Sharpe, credited for the
 * stops its proactive exits would have saved, and penalised when a single fold carries
 * most of its return — that shape is a window artefact, not an edge. The score is then
 * smoothed across neighbouring candidates (0.25R..1.50R) so the promoted value is the
 * centre of a plateau rather than a spike.
 */
class ParameterStabilityProfiler {

    /**
     * @param windowReportsByCandidate every fold's report for each swept candidate
     * @return the candidate to promote; the baseline and the hard-stop-only row are
     *   never promoted, because they are yardsticks rather than thresholds
     */
    fun selectStableProductionCandidate(
        windowReportsByCandidate: Map<WalkForwardConfigId, List<WalkForwardReport21Metrics>>
    ): WalkForwardConfigId {
        val candidates = WalkForwardConfigId.SWEPT
        require(candidates.any { windowReportsByCandidate[it].orEmpty().isNotEmpty() }) {
            "No walk-forward fold reports were supplied for any swept candidate"
        }

        val rawScores = candidates.associateWith { config ->
            val folds = windowReportsByCandidate[config].orEmpty()
            if (folds.isEmpty()) return@associateWith Double.NEGATIVE_INFINITY

            val meanExpectancy = folds.map { it.expectancyPerTradeR }.average()
            val meanSharpe = folds.map { it.sharpeRatio }.average()
            val meanSaved2R = folds.map { it.pctProactiveExitsSubsequentlyReachedMinus2R }.average()

            // Share of the candidate's total return contributed by its best fold.
            val bestFold = folds.maxOf { it.netPnlR }
            val total = folds.sumOf { it.netPnlR }
            val dominance = if (total > 0.0) bestFold / total else 1.0
            val dominancePenalty = if (dominance > DOMINANCE_LIMIT) DOMINANCE_PENALTY else 0.0

            (meanExpectancy * 2.0) + meanSharpe + (meanSaved2R * 0.02) - dominancePenalty
        }

        val plateauScores = candidates.mapIndexed { index, config ->
            val left = candidates.getOrNull(index - 1)?.let { rawScores.getValue(it) }
                ?: rawScores.getValue(config)
            val right = candidates.getOrNull(index + 1)?.let { rawScores.getValue(it) }
                ?: rawScores.getValue(config)
            val centre = rawScores.getValue(config)
            // Negative infinity from a missing candidate must not poison a real neighbour.
            val leftWeighted = if (left.isFinite()) left else centre
            val rightWeighted = if (right.isFinite()) right else centre
            config to (0.25 * leftWeighted + 0.50 * centre + 0.25 * rightWeighted)
        }.toMap()

        return plateauScores.maxByOrNull { it.value }!!.key
    }

    private companion object {
        /** A fold carrying more than this share of the return is an outlier, not an edge. */
        const val DOMINANCE_LIMIT = 0.65
        const val DOMINANCE_PENALTY = 1.5
    }
}
