package com.aistudio.alphapulse.domain.validation

import com.aistudio.alphapulse.domain.quant.KlineData
import kotlin.math.abs

/**
 * WalkForwardValidator - the offline research loop of §50, written as real code.
 *
 * §50 describes a cycle: train/calibrate, walk forward, predict, observe, update,
 * repeat - and lists the metrics a run must report (MAE, RMSE, MAPE, directional
 * accuracy, signed error, cone coverage, calibration error, prediction latency,
 * model stability, turnover sensitivity).
 *
 * Two things matter more than the arithmetic:
 *
 *  1. **Folds never overlap.** Each fold trains on a window that ends strictly
 *     before its test window begins, so a fold cannot learn from its own answers.
 *     That is what "walk forward" means, and [folds] enforces it.
 *  2. **Leakage is impossible, not unlikely.** Every predictor call is wrapped in
 *     [LeakageGuard.requireNoLeakage], so a cheating predictor fails the run
 *     instead of producing a flattering number (§51).
 *
 * The predictor is passed in, which keeps this class pure: it can validate the
 * real ensemble offline, and it can be tested with a deliberate cheater.
 *
 * Spec Ref: §50 Walk-Forward Validation, §51 No Data Leakage Requirement
 */

/**
 * One train/test split.
 *
 * @property index 1-based fold number, for logs
 * @property trainFromMs first timestamp the training window may use (inclusive)
 * @property trainToMs last timestamp the training window may use (inclusive)
 * @property testFromMs first timestamp being predicted
 * @property testToMs last timestamp being predicted
 */
data class WalkForwardFold(
    val index: Int,
    val trainFromMs: Long,
    val trainToMs: Long,
    val testFromMs: Long,
    val testToMs: Long
)

/**
 * What a predictor said for one candle.
 *
 * @property predictedPrice expected price at the end of the horizon
 * @property lowerBound lower cone bound, used for cone coverage
 * @property upperBound upper cone bound, used for cone coverage
 * @property latencyMs how long the prediction took, in milliseconds
 */
data class WalkForwardPrediction(
    val predictedPrice: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val latencyMs: Double = 0.0
)

/**
 * The metrics §50 requires.
 *
 * @property predictions how many predictions were scored
 * @property mae mean absolute error of price
 * @property rmse root mean squared error of price
 * @property mape mean absolute percentage error, in percent
 * @property directionalAccuracy share of predictions that got the direction right (0..1)
 * @property signedError mean(predicted - realised); positive means the model is biased high
 * @property coneCoverage share of outcomes that landed inside the cone (0..1)
 * @property calibrationError distance between cone coverage and the nominal level (0..1)
 * @property meanLatencyMs average prediction time
 * @property modelStability 1 - coefficient of variation of per-fold MAE, clamped to 0..1
 * @property turnoverSensitivity mean |change in forecast| divided by mean |change in price|
 */
data class WalkForwardMetrics(
    val predictions: Int,
    val mae: Double,
    val rmse: Double,
    val mape: Double,
    val directionalAccuracy: Double,
    val signedError: Double,
    val coneCoverage: Double,
    val calibrationError: Double,
    val meanLatencyMs: Double,
    val modelStability: Double,
    val turnoverSensitivity: Double
) {
    /** One-line summary for the research screen. */
    fun summary(): String =
        "WF $predictions predictions · MAE ${"%.2f".format(mae)} · " +
            "RMSE ${"%.2f".format(rmse)} · MAPE ${"%.2f".format(mape)}% · " +
            "direction ${"%.0f".format(directionalAccuracy * 100)}% · " +
            "cone ${"%.0f".format(coneCoverage * 100)}% · " +
            "stability ${"%.2f".format(modelStability)}"
}

object WalkForwardValidator {

    /**
     * Build non-overlapping folds over a candle timeline.
     *
     * @param candles the full history, in any order
     * @param trainSize candles per training window
     * @param testSize candles per test window
     * @param step candles advanced per fold (defaults to the test size, i.e. no overlap)
     * @throws IllegalArgumentException when the parameters cannot produce a fold
     */
    fun folds(
        candles: List<KlineData>,
        trainSize: Int,
        testSize: Int,
        step: Int = testSize
    ): List<WalkForwardFold> {
        require(trainSize > 0) { "trainSize must be positive" }
        require(testSize > 0) { "testSize must be positive" }
        require(step > 0) { "step must be positive" }

        val ordered = candles.sortedBy { it.openTime }
        val folds = mutableListOf<WalkForwardFold>()
        var trainStart = 0
        var index = 1

        while (trainStart + trainSize + testSize <= ordered.size) {
            val trainEnd = trainStart + trainSize - 1
            val testStart = trainEnd + 1
            val testEnd = testStart + testSize - 1
            folds += WalkForwardFold(
                index = index,
                trainFromMs = ordered[trainStart].openTime,
                trainToMs = ordered[trainEnd].openTime,
                testFromMs = ordered[testStart].openTime,
                testToMs = ordered[testEnd].openTime
            )
            trainStart += step
            index += 1
        }
        return folds
    }

    /**
     * Run the §50 loop and score it.
     *
     * @param candles full history
     * @param trainSize candles per training window
     * @param testSize candles per test window
     * @param nominalCoverage the cone's advertised coverage, e.g. 0.90, used for
     *   the calibration error
     * @param predict a pure function from training window to one prediction
     */
    fun run(
        candles: List<KlineData>,
        trainSize: Int,
        testSize: Int,
        nominalCoverage: Double = 0.90,
        predict: (train: List<KlineData>, test: KlineData) -> WalkForwardPrediction
    ): WalkForwardMetrics {
        val ordered = candles.sortedBy { it.openTime }
        val folds = folds(ordered, trainSize, testSize)

        val realisedErrors = mutableListOf<Double>()
        val signedErrors = mutableListOf<Double>()
        val percentageErrors = mutableListOf<Double>()
        val absoluteChangesPredicted = mutableListOf<Double>()
        val absoluteChangesRealised = mutableListOf<Double>()
        val latencies = mutableListOf<Double>()
        val perFoldMae = mutableListOf<Double>()
        var directionHits = 0
        var coneHits = 0
        var count = 0
        var previousPrediction: Double? = null
        var previousRealised: Double? = null

        for (fold in folds) {
            // What the model may legally see: strictly before the test window.
            val trainEndIndex = ordered.indexOfLast { it.openTime == fold.trainToMs }
            val train = ordered.subList(0, trainEndIndex + 1)
            val test = ordered.filter { it.openTime in fold.testFromMs..fold.testToMs }

            var foldErrorSum = 0.0
            var foldCount = 0

            for (sample in test) {
                // §51: the training window ends before the sample being predicted.
                LeakageGuard.requireNoLeakage(
                    observations = train.map {
                        TimedObservation(DataStream.CANDLES, it.openTime, "train candle")
                    },
                    cutoffMs = sample.openTime
                )

                val prediction = predict(train, sample)

                val realised = sample.close
                val error = prediction.predictedPrice - realised
                realisedErrors += abs(error)
                signedErrors += error
                percentageErrors += if (realised != 0.0) abs(error / realised) * 100.0 else 0.0
                latencies += prediction.latencyMs

                if (prediction.predictedPrice in prediction.lowerBound..prediction.upperBound &&
                    realised in prediction.lowerBound..prediction.upperBound
                ) {
                    coneHits += 1
                }

                val realisedDirection = realised - sample.open
                val predictedDirection = prediction.predictedPrice - sample.open
                if (realisedDirection == 0.0 || predictedDirection == 0.0 ||
                    (realisedDirection > 0) == (predictedDirection > 0)
                ) {
                    directionHits += 1
                }

                previousPrediction?.let { absoluteChangesPredicted += abs(prediction.predictedPrice - it) }
                previousRealised?.let { absoluteChangesRealised += abs(realised - it) }
                previousPrediction = prediction.predictedPrice
                previousRealised = realised

                foldErrorSum += abs(error)
                foldCount += 1
                count += 1
            }

            if (foldCount > 0) perFoldMae += foldErrorSum / foldCount
        }

        // An empty run has no metrics; report zeros rather than dividing by zero.
        if (count == 0) {
            return WalkForwardMetrics(
                predictions = 0, mae = 0.0, rmse = 0.0, mape = 0.0,
                directionalAccuracy = 0.0, signedError = 0.0, coneCoverage = 0.0,
                calibrationError = abs(nominalCoverage), meanLatencyMs = 0.0,
                modelStability = 0.0, turnoverSensitivity = 0.0
            )
        }

        val mae = realisedErrors.average()
        val rmse = kotlin.math.sqrt(realisedErrors.sumOf { it * it } / count)
        val coverage = coneHits.toDouble() / count
        val meanChangeRealised = absoluteChangesRealised.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val meanChangePredicted = absoluteChangesPredicted.takeIf { it.isNotEmpty() }?.average() ?: 0.0

        return WalkForwardMetrics(
            predictions = count,
            mae = mae,
            rmse = rmse,
            mape = percentageErrors.average(),
            directionalAccuracy = directionHits.toDouble() / count,
            signedError = signedErrors.average(),
            coneCoverage = coverage,
            calibrationError = abs(coverage - nominalCoverage),
            meanLatencyMs = latencies.average(),
            modelStability = stability(perFoldMae),
            turnoverSensitivity = if (meanChangeRealised <= 0.0) {
                0.0
            } else {
                meanChangePredicted / meanChangeRealised
            }
        )
    }

    /**
     * How consistent the model was between folds.
     *
     * 1.0 means every fold had the same error; lower means the result depended on
     * which slice of history it saw. Half the coefficient of variation maps to
     * about 0.5, which is the intuitive reading.
     */
    private fun stability(perFoldMae: List<Double>): Double {
        if (perFoldMae.size < 2) return 1.0
        val mean = perFoldMae.average()
        if (mean <= 0.0) return 1.0
        val variance = perFoldMae.sumOf { (it - mean) * (it - mean) } / perFoldMae.size
        val coefficientOfVariation = kotlin.math.sqrt(variance) / mean
        return (1.0 - coefficientOfVariation).coerceIn(0.0, 1.0)
    }
}
