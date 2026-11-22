package com.aistudio.alphapulse.data.local

import com.aistudio.alphapulse.engine.UniverseScreener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5 / Contract C-53 — heavy data is cached for its own horizon, not four hours for all.
 *
 * The Room-first candle cache applied the four-hour HTF staleness window to every
 * interval, so the 5m series that structure detection and calibration read could be
 * nearly four hours old: a level map built on the morning's swings while price traded
 * somewhere else. The four-hour window belongs to the 4h candles; the shorter series
 * are reused for roughly their own bar.
 *
 * Spec Ref: Phase 1 §5 Heavy Data, Phase 2 §23
 */
class CandleCacheTtlBddSpec {

    @Test
    fun `given the four-hour series when its staleness window is read then it is four hours`() {
        assertEquals(4L * 60L * 60L * 1000L, UniverseScreener.stalenessMsFor("4h"))
    }

    @Test
    fun `given the hourly series when its staleness window is read then it is one hour`() {
        assertEquals(60L * 60L * 1000L, UniverseScreener.stalenessMsFor("1h"))
    }

    @Test
    fun `given the five-minute series when its staleness window is read then it is five minutes`() {
        assertEquals(5L * 60L * 1000L, UniverseScreener.stalenessMsFor("5m"))
    }

    @Test
    fun `given a series exactly at its window when freshness is asked then it is stale`() {
        val ttl = UniverseScreener.stalenessMsFor("5m")

        assertTrue(UniverseScreener.isFresh(ageMs = ttl - 1, interval = "5m"))
        assertFalse(UniverseScreener.isFresh(ageMs = ttl, interval = "5m"))
    }
}
