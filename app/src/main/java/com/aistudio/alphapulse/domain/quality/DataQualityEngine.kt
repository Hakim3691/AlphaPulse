package com.aistudio.alphapulse.domain.quality

/**
 * DataQualityEngine - one verdict on whether the market data in hand can be trusted.
 *
 * Before this existed, freshness was checked in one place, spread sanity in
 * another, and "is a required model missing?" somewhere else. Nothing produced a
 * single answer, so the UI and the notification veto could disagree about whether
 * the feed was healthy.
 *
 * This engine takes every symptom it can observe in one snapshot and returns one
 * report: a score from 0.0 (worthless) to 1.0 (pristine), the specific issues
 * found, and a plain-English reason for each. Callers decide what to do with it -
 * the veto suppresses alerts, the dashboard shows the score.
 *
 * Spec Ref: §35 Data Quality Engine, §44 Failure Handling
 */

/** How much the engine trusts the current data snapshot. */
enum class DataQualityStatus {
    /** Everything checks out; forecasts may be published. */
    HEALTHY,
    /** Usable, but at least one symptom is present. */
    DEGRADED,
    /** Must not drive a forecast or an alert. */
    UNUSABLE
}

/**
 * Every symptom the engine can detect.
 *
 * The weight is how much of the total trust that symptom removes. They are
 * deliberately coarse: the goal is a stable, explainable verdict, not a
 * statistically fitted score that nobody can reason about.
 *
 * @property weight fraction of trust lost when this issue is present (0.0..1.0)
 * @property description short phrase used when explaining the issue to a user
 */
enum class DataQualityIssue(val weight: Double, val description: String) {
    /** No tick has ever arrived for this symbol. */
    NO_DATA(0.50, "no market data received yet"),
    /** The newest tick is older than the staleness limit. */
    STALE(0.35, "market data is stale"),
    /** Candles or ticks are missing inside the window. */
    GAPS(0.20, "gaps in the data stream"),
    /** Messages arrived out of sequence. */
    OUT_OF_ORDER(0.10, "messages arrived out of order"),
    /** The bid-ask spread is wider than this strategy tolerates. */
    WIDE_SPREAD(0.20, "spread is wider than the strategy tolerates"),
    /** Too few price levels to reconstruct the book. */
    SHALLOW_BOOK(0.10, "order book is too shallow"),
    /** A model the decision depends on is not mounted. */
    MODEL_MISSING(0.30, "a required model is unavailable")
}

/**
 * One observation of the feed, as measured by the caller.
 *
 * Every field has a default so a caller only supplies what it actually knows;
 * anything omitted is treated as "fine", never as "failed".
 *
 * @property lastTickTimestampMs newest message time, or 0 when nothing arrived yet
 * @property nowMs the current time used to measure age
 * @property staleAfterMs age at which data is considered stale (default 5 minutes, §58)
 * @property gapCount missing messages detected in the window
 * @property outOfOrderCount messages that arrived before an older one
 * @property spreadBps current spread in basis points
 * @property maxAcceptableSpreadBps widest spread still considered tradable
 * @property bookLevels price levels reconstructed
 * @property requiredBookLevels minimum levels for a usable book
 * @property requiredModelAvailable whether every model the decision needs is mounted
 */
data class DataQualityInput(
    val lastTickTimestampMs: Long,
    val nowMs: Long,
    val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    val gapCount: Int = 0,
    val outOfOrderCount: Int = 0,
    val spreadBps: Double = 0.0,
    val maxAcceptableSpreadBps: Double = DEFAULT_MAX_SPREAD_BPS,
    val bookLevels: Int = DEFAULT_REQUIRED_BOOK_LEVELS,
    val requiredBookLevels: Int = DEFAULT_REQUIRED_BOOK_LEVELS,
    val requiredModelAvailable: Boolean = true
) {
    companion object {
        /** §58 vetoes alerts when data is more than five minutes old. */
        const val DEFAULT_STALE_AFTER_MS: Long = 5 * 60 * 1000L
        /** A 25 bps spread is already wide for a liquid perpetual. */
        const val DEFAULT_MAX_SPREAD_BPS: Double = 25.0
        /** Ten levels is the minimum for the L2 reconstruction to mean anything. */
        const val DEFAULT_REQUIRED_BOOK_LEVELS: Int = 10
    }
}

/**
 * The engine's answer.
 *
 * @property status trust level (healthy / degraded / unusable)
 * @property score 1.0 minus the weight of every issue found, floored at 0.0
 * @property issues every symptom detected, as a set so callers can test membership
 * @property reasons one plain-English sentence per issue, in the order they were found
 * @property ageMs how old the newest tick is (0 when no tick has arrived)
 */
data class DataQualityReport(
    val status: DataQualityStatus,
    val score: Double,
    val issues: Set<DataQualityIssue>,
    val reasons: List<String>,
    val ageMs: Long
) {
    /** True when the snapshot may drive a forecast at all. */
    val isUsable: Boolean
        get() = status != DataQualityStatus.UNUSABLE

    /** True when the snapshot is fresh enough to justify an alert (§58). */
    val isFresh: Boolean
        get() = DataQualityIssue.NO_DATA !in issues && DataQualityIssue.STALE !in issues

    /**
     * Freshness as a 0.0..1.0 number, which is the shape the decision router
     * already expects in `DecisionInputs.dataFreshness`.
     */
    val freshness: Double
        get() = when {
            DataQualityIssue.NO_DATA in issues -> 0.0
            ageMs <= 0L -> 1.0
            else -> (1.0 - (ageMs.toDouble() / DataQualityInput.DEFAULT_STALE_AFTER_MS))
                .coerceIn(0.0, 1.0)
        }
}

object DataQualityEngine {

    /** A score at or above this is considered healthy. */
    const val HEALTHY_SCORE: Double = 0.80

    /** A score at or above this is degraded but usable. */
    const val USABLE_SCORE: Double = 0.40

    /**
     * Assess one snapshot.
     *
     * The rules are intentionally simple and ordered by severity:
     *  - no tick at all, or a tick older than the limit -> cannot be trusted;
     *  - a required model missing -> cannot be trusted (the decision is incomplete);
     *  - everything else lowers the score and the status follows the score.
     */
    fun evaluate(input: DataQualityInput): DataQualityReport {
        val reasons = mutableListOf<String>()
        val issues = linkedSetOf<DataQualityIssue>()

        // Age is measured only when a tick exists; 0 means "never received".
        val hasTick = input.lastTickTimestampMs > 0L
        val ageMs = if (hasTick) (input.nowMs - input.lastTickTimestampMs).coerceAtLeast(0L) else 0L

        if (!hasTick) {
            issues += DataQualityIssue.NO_DATA
            reasons += "No market data has been received for this symbol yet."
        } else if (ageMs > input.staleAfterMs) {
            issues += DataQualityIssue.STALE
            val ageSeconds = ageMs / 1000
            reasons += "Market data is $ageSeconds s old, beyond the " +
                "${input.staleAfterMs / 1000} s freshness limit."
        }

        if (input.gapCount > 0) {
            issues += DataQualityIssue.GAPS
            reasons += "${input.gapCount} gap(s) detected in the data stream."
        }

        if (input.outOfOrderCount > 0) {
            issues += DataQualityIssue.OUT_OF_ORDER
            reasons += "${input.outOfOrderCount} message(s) arrived out of order."
        }

        if (input.spreadBps > input.maxAcceptableSpreadBps) {
            issues += DataQualityIssue.WIDE_SPREAD
            reasons += "Spread is ${"%.1f".format(input.spreadBps)} bps, above the " +
                "${"%.1f".format(input.maxAcceptableSpreadBps)} bps limit."
        }

        if (input.bookLevels < input.requiredBookLevels) {
            issues += DataQualityIssue.SHALLOW_BOOK
            reasons += "Order book has ${input.bookLevels} level(s), below the " +
                "${input.requiredBookLevels} required."
        }

        if (!input.requiredModelAvailable) {
            issues += DataQualityIssue.MODEL_MISSING
            reasons += "A required model is not mounted, so the decision would be incomplete."
        }

        // Trust is what is left after every symptom has taken its share.
        val score = (1.0 - issues.sumOf { it.weight }).coerceIn(0.0, 1.0)

        val status = when {
            // These two make a forecast meaningless regardless of the score.
            DataQualityIssue.NO_DATA in issues || DataQualityIssue.MODEL_MISSING in issues ->
                DataQualityStatus.UNUSABLE
            score >= HEALTHY_SCORE -> DataQualityStatus.HEALTHY
            score >= USABLE_SCORE -> DataQualityStatus.DEGRADED
            else -> DataQualityStatus.UNUSABLE
        }

        return DataQualityReport(
            status = status,
            score = score,
            issues = issues,
            reasons = reasons,
            ageMs = ageMs
        )
    }

    /**
     * One-line summary for the UI: the score plus the first problem found.
     *
     * @return e.g. "Data quality 65% (degraded) - market data is stale"
     */
    fun summarize(report: DataQualityReport): String {
        val headline = "Data quality ${"%.0f".format(report.score * 100)}% " +
            "(${report.status.name.lowercase()})"
        val firstProblem = report.reasons.firstOrNull() ?: return "$headline - all checks passed"
        return "$headline - ${firstProblem.decapitalizeFirst()}"
    }

    /** Lowercase only the first character so a sentence reads naturally mid-line. */
    private fun String.decapitalizeFirst(): String =
        if (isEmpty()) this else first().lowercaseChar() + substring(1)
}
