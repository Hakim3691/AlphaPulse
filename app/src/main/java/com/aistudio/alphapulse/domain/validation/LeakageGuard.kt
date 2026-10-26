package com.aistudio.alphapulse.domain.validation

/**
 * LeakageGuard - proves that a prediction never saw the future.
 *
 * §51 is short and absolute: "At prediction timestamp T, no information after T
 * may be used." That rule is easy to state, easy to break by accident (an
 * off-by-one slice, a sort that runs before a filter, a normalisation computed
 * over the whole series), and impossible to notice from the output - a leaking
 * backtest simply looks better than reality.
 *
 * So this is a guard, not a metric: give it every observation that fed a
 * prediction and the timestamp the prediction was made, and it either says
 * "clean" or lists the exact observations that came from the future.
 *
 * The list of streams is straight from §51 - candles, open interest,
 * liquidations, volume profile, order book, model calibration, ensemble weights
 * and feature normalisation - because leakage in any one of them invalidates the
 * result.
 *
 * Spec Ref: §51 No Data Leakage Requirement, §50 Walk-Forward Validation
 */

/**
 * Every kind of input §51 names.
 *
 * @property label name used in violation messages
 */
enum class DataStream(val label: String) {
    CANDLES("candles"),
    OPEN_INTEREST("open interest"),
    LIQUIDATIONS("liquidations"),
    VOLUME_PROFILE("volume profile"),
    ORDER_BOOK("order book"),
    MODEL_CALIBRATION("model calibration"),
    ENSEMBLE_WEIGHTS("ensemble weights"),
    FEATURE_NORMALIZATION("feature normalization")
}

/**
 * One piece of information that fed a prediction.
 *
 * @property stream which input family it belongs to
 * @property timestampMs when the information became available
 * @property label optional detail, e.g. the symbol or the field name
 */
data class TimedObservation(
    val stream: DataStream,
    val timestampMs: Long,
    val label: String = ""
)

/** The guard's verdict. */
sealed class LeakageCheck {

    /**
     * Nothing came from the future.
     *
     * @property observationsChecked how many observations were inspected
     * @property cutoffMs the prediction timestamp they were checked against
     */
    data class Clean(val observationsChecked: Int, val cutoffMs: Long) : LeakageCheck()

    /**
     * At least one observation is newer than the prediction timestamp.
     *
     * @property cutoffMs the prediction timestamp
     * @property violations every future observation found, oldest first
     */
    data class Leaked(val cutoffMs: Long, val violations: List<TimedObservation>) : LeakageCheck() {

        /** One line naming the first offender and how many more there are. */
        fun summary(): String {
            val first = violations.first()
            val streamName = first.stream.label
            val extra = violations.size - 1
            val suffix = if (extra > 0) " (and $extra more)" else ""
            return "Look-ahead leak: $streamName available at ${first.timestampMs} " +
                "was used for a prediction made at $cutoffMs$suffix."
        }
    }
}

object LeakageGuard {

    /**
     * Check a single prediction.
     *
     * An observation timestamped exactly at the cutoff is allowed - that is the
     * information available *at* the decision moment. Anything strictly later is a
     * leak.
     *
     * @param observations everything that fed the prediction
     * @param cutoffMs the prediction timestamp T
     */
    fun check(observations: List<TimedObservation>, cutoffMs: Long): LeakageCheck {
        val leaked = observations
            .filter { it.timestampMs > cutoffMs }
            .sortedBy { it.timestampMs }

        return if (leaked.isEmpty()) {
            LeakageCheck.Clean(observationsChecked = observations.size, cutoffMs = cutoffMs)
        } else {
            LeakageCheck.Leaked(cutoffMs = cutoffMs, violations = leaked)
        }
    }

    /** Convenience for callers that only want a yes/no. */
    fun isClean(observations: List<TimedObservation>, cutoffMs: Long): Boolean =
        check(observations, cutoffMs) is LeakageCheck.Clean

    /**
     * Check a whole series of predictions - the shape a backtest walks through.
     *
     * @param predictions cutoffs paired with the observations that fed them
     * @return the first leak found, or a [LeakageCheck.Clean] covering every check
     */
    fun checkAll(predictions: List<Pair<Long, List<TimedObservation>>>): LeakageCheck {
        var total = 0
        for ((cutoff, observations) in predictions) {
            total += observations.size
            val result = check(observations, cutoff)
            if (result is LeakageCheck.Leaked) return result
        }
        return LeakageCheck.Clean(
            observationsChecked = total,
            cutoffMs = predictions.lastOrNull()?.first ?: 0L
        )
    }

    /**
     * The mandatory-test form of the check (§51 calls leakage a mandatory test
     * requirement): fail the run instead of returning a verdict a caller might
     * ignore.
     *
     * @throws LeakageException when any observation comes from the future
     */
    fun requireNoLeakage(observations: List<TimedObservation>, cutoffMs: Long) {
        val result = check(observations, cutoffMs)
        if (result is LeakageCheck.Leaked) {
            throw LeakageException(result.summary())
        }
    }

    /** Raised when a prediction consumed information from the future. */
    class LeakageException(message: String) : IllegalStateException(message)

    /**
     * Trim a series to what a prediction at [cutoffMs] may legally see.
     *
     * This is the helper backtests should use instead of hand-writing a filter:
     * applying it is what makes [requireNoLeakage] pass.
     *
     * @param observations the full series
     * @param cutoffMs the prediction timestamp T
     */
    fun upto(observations: List<TimedObservation>, cutoffMs: Long): List<TimedObservation> =
        observations.filter { it.timestampMs <= cutoffMs }
}
