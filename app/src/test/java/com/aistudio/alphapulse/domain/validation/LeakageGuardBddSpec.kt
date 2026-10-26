package com.aistudio.alphapulse.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * Behaviour of the look-ahead guard, specified as Given/When/Then.
 *
 * §51 calls no-leakage a *mandatory test requirement*, so these specs are the
 * requirement: information after the prediction timestamp must be reported as a
 * leak, information at or before it must not, and the strict form must refuse to
 * continue rather than warn.
 *
 * Spec Ref: §51 No Data Leakage Requirement
 */
class LeakageGuardBddSpec {

    private val cutoff = 1_700_000_000_000L

    private fun observation(offsetMs: Long, stream: DataStream = DataStream.CANDLES) =
        TimedObservation(stream, cutoff + offsetMs, "BTCUSDT")

    // ------------------------------------------------------------ clean case

    @Test
    fun `given every input arrived before the prediction when checked then the result is clean`() {
        val observations = listOf(
            observation(-60_000L),
            observation(-1_000L),
            observation(-5_000L, DataStream.ORDER_BOOK)
        )

        val result = LeakageGuard.check(observations, cutoff)

        assertTrue(result is LeakageCheck.Clean)
        assertEquals(3, (result as LeakageCheck.Clean).observationsChecked)
        assertTrue(LeakageGuard.isClean(observations, cutoff))
    }

    @Test
    fun `given an input timestamped exactly at the prediction moment when checked then it is allowed`() {
        // Information available *at* T is legal; only after T is a leak.
        val result = LeakageGuard.check(listOf(observation(0L)), cutoff)

        assertTrue("The cutoff itself is inclusive", result is LeakageCheck.Clean)
    }

    // -------------------------------------------------------------- leak case

    @Test
    fun `given a candle from the future when checked then the leak is reported with its stream and time`() {
        val future = observation(+60_000L)

        val result = LeakageGuard.check(listOf(observation(-1_000L), future), cutoff)

        assertTrue(result is LeakageCheck.Leaked)
        val leaked = result as LeakageCheck.Leaked
        assertEquals(1, leaked.violations.size)
        assertEquals(future, leaked.violations.first())
        val summary = leaked.summary()
        assertTrue("Summary must name the stream: $summary", summary.contains("candles"))
        assertTrue("Summary must state the prediction time: $summary", summary.contains("$cutoff"))
    }

    @Test
    fun `given several future inputs when checked then every one is listed oldest first`() {
        val result = LeakageGuard.check(
            listOf(
                observation(+120_000L, DataStream.ORDER_BOOK),
                observation(+30_000L, DataStream.FEATURE_NORMALIZATION),
                observation(+60_000L, DataStream.LIQUIDATIONS)
            ),
            cutoff
        ) as LeakageCheck.Leaked

        assertEquals(3, result.violations.size)
        assertEquals(
            "Violations must be ordered so the earliest cheat is shown first",
            listOf(30_000L, 60_000L, 120_000L),
            result.violations.map { it.timestampMs - cutoff }
        )
    }

    @Test
    fun `given leaks in any of the eight streams the spec names when checked then each is caught`() {
        DataStream.entries.forEach { stream ->
            val result = LeakageGuard.check(listOf(observation(+1_000L, stream)), cutoff)
            assertTrue("Leakage via ${stream.label} must be caught", result is LeakageCheck.Leaked)
        }
    }

    // --------------------------------------------------------- series checks

    @Test
    fun `given a series of predictions where a later one cheats when checked then that prediction is reported`() {
        val predictions = listOf(
            cutoff to listOf(observation(-1_000L)),
            cutoff + 60_000L to listOf(observation(+90_000L))
        )

        val result = LeakageGuard.checkAll(predictions)

        assertTrue(result is LeakageCheck.Leaked)
        assertEquals(cutoff + 60_000L, (result as LeakageCheck.Leaked).cutoffMs)
    }

    @Test
    fun `given a whole series with no leaks when checked then the total number of observations is reported`() {
        val predictions = listOf(
            cutoff to listOf(observation(-1_000L), observation(-2_000L)),
            cutoff + 60_000L to listOf(observation(-3_000L))
        )

        val result = LeakageGuard.checkAll(predictions) as LeakageCheck.Clean

        assertEquals(3, result.observationsChecked)
    }

    // ------------------------------------------------------ mandatory form

    @Test
    fun `given a leak when the mandatory check runs then it throws instead of returning a verdict`() {
        val exception = assertThrows(LeakageGuard.LeakageException::class.java) {
            LeakageGuard.requireNoLeakage(listOf(observation(+1_000L)), cutoff)
        }

        assertTrue(exception.message!!.contains("Look-ahead leak"))
    }

    @Test
    fun `given only past data when the mandatory check runs then nothing is thrown`() {
        LeakageGuard.requireNoLeakage(listOf(observation(-1_000L)), cutoff)
    }

    // ------------------------------------------------------ the usable helper

    @Test
    fun `given a full history when trimmed for a cutoff then the result passes the guard`() {
        val history = listOf(observation(-120_000L), observation(-60_000L), observation(+60_000L))

        val visible = LeakageGuard.upto(history, cutoff)

        assertEquals(2, visible.size)
        assertTrue(LeakageGuard.isClean(visible, cutoff))
        assertFalse("The future candle must be gone", visible.any { it.timestampMs > cutoff })
    }
}
