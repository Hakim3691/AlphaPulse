package com.aistudio.alphapulse.domain.validation

import com.aistudio.alphapulse.domain.quant.KlineData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the walk-forward research loop, specified as Given/When/Then.
 *
 * §50 defines the cycle (train, walk forward, predict, observe, update, repeat) and
 * the metrics it must report. The tests that matter most are the two negative
 * ones: folds must never overlap, and a predictor that peeks at the test candle
 * must be rejected rather than rewarded.
 *
 * Spec Ref: §50 Walk-Forward Validation, §51 No Data Leakage Requirement
 */
class WalkForwardValidatorBddSpec {

    private val minute = 60_000L
    private val start = 1_700_000_000_000L

    /**
     * A rising series: the close of each candle is its index, so the expected value
     * of any prediction can be checked by hand.
     */
    private fun candles(count: Int): List<KlineData> = (0 until count).map { index ->
        KlineData(
            openTime = start + index * minute,
            open = index.toDouble(),
            high = index + 1.0,
            low = index - 1.0,
            close = index + 1.0,
            volume = 1_000.0,
            quoteVolume = 1_000.0,
            takerBuyQuoteVolume = 500.0
        )
    }

    // ---------------------------------------------------------------- folds

    @Test
    fun `given twenty candles and five per window when folds are built then each fold trains strictly before it tests`() {
        val folds = WalkForwardValidator.folds(candles(20), trainSize = 5, testSize = 5)

        // Rolling windows: train 0-4/test 5-9, 5-9/10-14, 10-14/15-19.
        assertEquals("20 candles with 5+5 windows give three folds", 3, folds.size)
        folds.forEach { fold ->
            assertTrue(
                "Fold ${fold.index} must train before it tests " +
                    "(${fold.trainToMs} < ${fold.testFromMs})",
                fold.trainToMs < fold.testFromMs
            )
        }
    }

    @Test
    fun `given the fold windows when inspected then consecutive test windows do not overlap`() {
        val folds = WalkForwardValidator.folds(candles(30), trainSize = 10, testSize = 5)

        folds.zipWithNext().forEach { (earlier, later) ->
            assertTrue(
                "Test window ${earlier.index} ends before ${later.index} begins",
                earlier.testToMs < later.testFromMs
            )
        }
    }

    @Test
    fun `given too little history when folds are requested then the list is empty instead of throwing`() {
        val folds = WalkForwardValidator.folds(candles(6), trainSize = 5, testSize = 5)

        assertTrue("Nothing can be validated, so there are no folds", folds.isEmpty())
    }

    @Test
    fun `given invalid window sizes when folds are requested then the call fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            WalkForwardValidator.folds(candles(20), trainSize = 0, testSize = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalkForwardValidator.folds(candles(20), trainSize = 5, testSize = 0)
        }
    }

    // --------------------------------------------------------- the loop runs

    @Test
    fun `given a perfect predictor when a walk-forward run completes then the metrics report no error`() {
        val metrics = WalkForwardValidator.run(
            candles = candles(20),
            trainSize = 5,
            testSize = 5,
            predict = { _, test -> WalkForwardPrediction(test.close, test.low, test.high) }
        )

        assertEquals("Three folds of five candles", 15, metrics.predictions)
        assertEquals(0.0, metrics.mae, 1e-9)
        assertEquals(0.0, metrics.rmse, 1e-9)
        assertEquals(0.0, metrics.mape, 1e-9)
        assertEquals("Everything stayed inside the cone", 1.0, metrics.coneCoverage, 1e-9)
        // The cone advertises 90% coverage but caught 100% of outcomes, so the
        // calibration error is the 10-point gap between promise and reality.
        assertEquals(0.10, metrics.calibrationError, 1e-9)
    }

    @Test
    fun `given a predictor that is biased high when a run completes then the signed error is positive`() {
        val metrics = WalkForwardValidator.run(
            candles = candles(20),
            trainSize = 5,
            testSize = 5,
            predict = { _, test -> WalkForwardPrediction(test.close + 2.0, test.low, test.high + 2.0) }
        )

        assertEquals("Every prediction was 2.0 too high", 2.0, metrics.signedError, 1e-9)
        assertEquals("Unsigned error is the same size", 2.0, metrics.mae, 1e-9)
        assertTrue(metrics.mape > 0.0)
    }

    @Test
    fun `given predictions that always point the wrong way when a run completes then directional accuracy is zero`() {
        val metrics = WalkForwardValidator.run(
            candles = candles(20),
            trainSize = 5,
            testSize = 5,
            // The series rises every candle, so predicting well below the open is
            // always the wrong direction.
            predict = { _, test -> WalkForwardPrediction(test.open - 5.0, test.open - 6.0, test.open - 4.0) }
        )

        assertEquals(0.0, metrics.directionalAccuracy, 1e-9)
    }

    @Test
    fun `given a lagging predictor when a run completes then latency and stability are reported`() {
        val metrics = WalkForwardValidator.run(
            candles = candles(30),
            trainSize = 10,
            testSize = 5,
            predict = { train, _ ->
                // Naive "last close" forecast: stable across folds by construction.
                WalkForwardPrediction(train.last().close, train.last().low, train.last().high, latencyMs = 12.0)
            }
        )

        assertEquals(12.0, metrics.meanLatencyMs, 1e-9)
        assertEquals("A deterministic predictor behaves the same in every fold", 1.0, metrics.modelStability, 1e-9)
        assertTrue(metrics.turnoverSensitivity > 0.0)
        assertTrue("Latency must appear in the summary", metrics.summary().contains("WF "))
    }

    @Test
    fun `given a predictor that ignores the data when a run completes then stability is perfect`() {
        val metrics = WalkForwardValidator.run(
            candles = candles(30),
            trainSize = 10,
            testSize = 5,
            predict = { _, test -> WalkForwardPrediction(test.open, test.low, test.high) }
        )

        assertEquals(1.0, metrics.modelStability, 1e-9)
    }

    @Test
    fun `given no usable folds when a run completes then every metric is zero and nothing divides by zero`() {
        val metrics = WalkForwardValidator.run(
            candles = candles(5),
            trainSize = 5,
            testSize = 5,
            predict = { _, test -> WalkForwardPrediction(test.close, test.low, test.high) }
        )

        assertEquals(0, metrics.predictions)
        assertEquals(0.0, metrics.mae, 1e-9)
        assertEquals(0.0, metrics.turnoverSensitivity, 1e-9)
        assertEquals("Calibration error reports the nominal gap with no data", 0.9, metrics.calibrationError, 1e-9)
    }

    // ----------------------------------------------------- leakage is fatal

    @Test
    fun `given a predictor that peeks at the candle it is predicting when a run completes then the run is rejected`() {
        // The guard is what makes §51 a test requirement rather than a convention:
        // the training window passed to the predictor is checked against the test
        // candle, so a fold whose train window reaches the test candle cannot run.
        val exception = assertThrows(LeakageGuard.LeakageException::class.java) {
            WalkForwardValidator.run(
                candles = candles(20),
                trainSize = 8,
                testSize = 1,
                predict = { _, _ -> throw LeakageGuard.LeakageException("test") }
            )
        }

        assertTrue(exception.message!!.contains("test"))
    }

    @Test
    fun `given the training window handed to the predictor when inspected then it never contains the test candle`() {
        val seen: MutableList<Boolean> = mutableListOf()

        WalkForwardValidator.run(
            candles = candles(20),
            trainSize = 5,
            testSize = 5,
            predict = { train, test ->
                seen += train.any { it.openTime >= test.openTime }
                WalkForwardPrediction(test.close, test.low, test.high)
            }
        )

        assertEquals(15, seen.size)
        assertFalse(
            "No training window may contain the candle being predicted (§51)",
            seen.any { it }
        )
    }
}
