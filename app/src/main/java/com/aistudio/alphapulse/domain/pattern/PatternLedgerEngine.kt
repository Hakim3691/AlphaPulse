package com.aistudio.alphapulse.domain.pattern

import java.util.Locale
import kotlin.math.max

/**
 * One row of the secondary Pattern Prediction & Learning Ledger.
 *
 * Field names follow the directive's `PatternLedgerEntry`, flattened: the nested
 * `predictedPattern { id, name, category, confidence }` is four columns so the row is
 * queryable and exportable without parsing a blob.
 *
 * Spec Ref: Directive §4 State Schema
 */
data class PatternLedgerEntry(
    /** Stable key: asset + horizon + phase-locked target boundary. */
    val id: String,
    /** Base asset, e.g. `VVV`. */
    val asset: String,
    /** `1m` | `5m` | `15m` | `30m` | `1h`. */
    val horizon: String,
    /** Expiration timestamp in epoch millis; rendered `HH:mm:ss`. */
    val targetTime: Long,
    val issuedTimestamp: Long,
    val predictedPatternId: String,
    val predictedPatternName: String,
    /** `Bullish Reversal` | `Bearish Reversal` | `Continuation`. */
    val predictedCategory: String,
    /** Normalized probability in [0.00, 1.00]. */
    val confidence: Double,
    /** Audited formation at the boundary, or null while unsettled. */
    val realizedPattern: String? = null,
    /** Categorical cross-entropy at settlement, or null. */
    val fitLoss: Double? = null,
    /** `Pending` | `Evaluating` | `Settled`. */
    val status: String,
    /** The SGD callback state, e.g. `SGD Step: +w_reinforce (Loss: 0.41)`. */
    val sgdAction: String,
    val settled: Boolean = false
)

/** Result of settling one entry. */
data class PatternSettlement(
    val entry: PatternLedgerEntry,
    val realized: PatternMatch?,
    val fitLoss: Double?,
    val step: PatternSgdStep?
)

/**
 * Issues, settles and grades pattern predictions.
 *
 * Why this exists
 * ---------------
 * The price ledger already settles a forecast against a realized price. Patterns need the
 * same discipline against realized *geometry*: freeze the prediction at the horizon
 * boundary, re-run the deterministic classifier on the closed bars, score the miss with
 * categorical cross-entropy and take one SGD step. Keeping that in one pure object means
 * the ledger can be exercised over synthetic candles, with no clock, network or database.
 *
 * Target times use the price ledger's phase-locked formula
 * (`floor(now/60s) + horizon minutes`), so both tables count down to the same boundary for
 * the same asset — the directive's "synchronized horizon clocks".
 *
 * Spec Ref: Directive §1 Dual Ledger, §2 Horizon Mapping, §4 SGD Pipeline
 */
object PatternLedgerEngine {

    const val STATUS_PENDING = "Pending"
    const val STATUS_EVALUATING = "Evaluating"
    const val STATUS_SETTLED = "Settled"

    /** Recorded when the boundary produced no auditable formation. */
    const val NO_PATTERN = "None"

    /** Recorded on a row whose settlement produced no usable ground truth. */
    const val SGD_SKIPPED = "SGD Skip: no realized pattern"

    /** The phase-locked expiry the price ledger uses for the same horizon. */
    fun targetTimestamp(nowMs: Long, horizon: PatternHorizon): Long =
        ((nowMs / 60_000L) + horizon.minutes) * 60_000L

    /** Deterministic key; a re-issue inside the same candle replaces rather than duplicates. */
    fun idOf(symbol: String, horizon: PatternHorizon, targetTime: Long): String =
        "$symbol-${horizon.id}-$targetTime"

    /**
     * Roll [candles] into [horizon] bars.
     *
     * Buckets are aligned to the epoch, which is how exchanges align their own bars, so a
     * series already at the horizon's interval passes through unchanged.
     */
    fun aggregate(candles: List<PatternCandle>, horizon: PatternHorizon): List<PatternCandle> {
        if (candles.isEmpty()) return emptyList()
        val bucketMs = horizon.durationMs
        val buckets = LinkedHashMap<Long, MutableList<PatternCandle>>()
        for (candle in candles) {
            val bucket = candle.openTime / bucketMs
            buckets.getOrPut(bucket) { mutableListOf() }.add(candle)
        }
        return buckets.map { (bucket, bars) ->
            PatternCandle(
                openTime = bucket * bucketMs,
                open = bars.first().open,
                high = bars.maxOf { it.high },
                low = bars.minOf { it.low },
                close = bars.last().close,
                volume = bars.sumOf { it.volume }
            )
        }
    }

    /**
     * Issue a prediction for [horizon], or null when the geometry shows no formation.
     *
     * Returning null is deliberate: the ledger must not invent a pattern to fill a row.
     */
    fun issue(
        symbol: String,
        candles: List<PatternCandle>,
        horizon: PatternHorizon,
        nowMs: Long
    ): PatternLedgerEntry? {
        val series = aggregate(candles, horizon)
        if (series.size < 2) return null
        val match = PatternClassifier.classify(series, horizon).firstOrNull() ?: return null
        val target = targetTimestamp(nowMs, horizon)

        return PatternLedgerEntry(
            id = idOf(symbol, horizon, target),
            asset = symbol.removeSuffix("USDT"),
            horizon = horizon.id,
            targetTime = target,
            issuedTimestamp = nowMs,
            predictedPatternId = match.id,
            predictedPatternName = match.name,
            predictedCategory = match.category.label,
            confidence = match.confidence,
            realizedPattern = null,
            fitLoss = null,
            status = STATUS_PENDING,
            sgdAction = "Pending Settlement",
            settled = false
        )
    }

    /**
     * Grade [entry] against the closed bar that ends at its target boundary.
     *
     * Until that bar exists the row stays `Evaluating` — a settlement decided on an
     * unfinished candle would be graded against a geometry that can still change.
     */
    fun settle(
        entry: PatternLedgerEntry,
        candles: List<PatternCandle>,
        horizon: PatternHorizon,
        model: PatternSgdModel
    ): PatternSettlement {
        val series = aggregate(candles, horizon)
        val targetOpen = entry.targetTime - horizon.durationMs
        val closeIndex = series.indexOfLast { it.openTime <= targetOpen }
        val boundaryClosed = closeIndex >= 1 && series[closeIndex].openTime == targetOpen

        if (!boundaryClosed) {
            return PatternSettlement(
                entry = entry.copy(status = STATUS_EVALUATING),
                realized = null,
                fitLoss = null,
                step = null
            )
        }

        val window = series.subList(0, closeIndex + 1)
        val realized = PatternClassifier.classify(window, horizon).firstOrNull()
        if (realized == null) {
            return PatternSettlement(
                entry = entry.copy(
                    status = STATUS_SETTLED,
                    realizedPattern = NO_PATTERN,
                    sgdAction = SGD_SKIPPED,
                    settled = true
                ),
                realized = null,
                fitLoss = null,
                step = null
            )
        }

        val features = featuresFor(entry.predictedPatternId, model.classes)
            ?: return PatternSettlement(
                entry = entry.copy(
                    status = STATUS_SETTLED,
                    realizedPattern = realized.name,
                    sgdAction = SGD_SKIPPED,
                    settled = true
                ),
                realized = realized,
                fitLoss = null,
                step = null
            )

        val step = model.step(features, realized.id)
        return PatternSettlement(
            entry = entry.copy(
                realizedPattern = realized.name,
                fitLoss = step.loss,
                status = STATUS_SETTLED,
                sgdAction = step.action,
                settled = true
            ),
            realized = realized,
            fitLoss = step.loss,
            step = step
        )
    }

    /** `In mm:ss` before the boundary, `Evaluating` at it, `Settled` after a grading. */
    fun statusLabel(entry: PatternLedgerEntry, nowMs: Long): String = when {
        entry.status == STATUS_SETTLED -> STATUS_SETTLED
        nowMs >= entry.targetTime -> STATUS_EVALUATING
        else -> {
            val remaining = max(0L, entry.targetTime - nowMs)
            String.format(
                Locale.US,
                "In %02d:%02d",
                remaining / 60_000L,
                (remaining % 60_000L) / 1000L
            )
        }
    }

    /**
     * The evidence the online step uses: the formation that was predicted, one-hot.
     *
     * That makes `Wpattern` a learned affinity matrix between predicted and realized
     * formations per horizon — directly interpretable as "when I called a flag, what did
     * it actually turn out to be".
     */
    fun featuresFor(predictedPatternId: String, classes: List<String>): DoubleArray? {
        val position = classes.indexOf(predictedPatternId)
        if (position < 0) return null
        return DoubleArray(classes.size).also { it[position] = 1.0 }
    }
}
