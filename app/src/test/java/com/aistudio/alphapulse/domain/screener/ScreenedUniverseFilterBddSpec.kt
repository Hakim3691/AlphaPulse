package com.aistudio.alphapulse.domain.screener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §65 / Contract C-17 — the boundaries of the one institutional flow gate.
 *
 * The thresholds are inclusive or strict on purpose, and the difference decides whether a
 * coin armed by a hair is armed at all. These scenarios pin each boundary, plus the
 * persistence rule the app's screening path used to bypass.
 *
 * Spec Ref: §65, C-17, C-37
 */
class ScreenedUniverseFilterBddSpec {

    /** Narrows a snapshot to a single tweaked input, leaving the rest passing. */
    private fun day(
        volume: Double = 120_000_000.0,
        openInterest: Double = 60_000_000.0,
        takerBuyRatio: Double = 0.54,
        open: Double = 100.0,
        close: Double = 106.0,
        high: Double = 110.0,
        low: Double = 98.0
    ) = DailyFilterSnapshot(
        quoteVolume24hUsdt = volume,
        takerBuyRatio = takerBuyRatio,
        openInterestUsdt = openInterest,
        openPrice = open,
        closePrice = close,
        highPrice = high,
        lowPrice = low
    )

    @Test
    fun `given a day at exactly the five million volume floor and the fifty percent taker ratio when gated then it passes both inclusive legs`() {
        val boundary = day(volume = 5_000_000.0, openInterest = 2_000_000.0, takerBuyRatio = 0.50)

        assertTrue(
            "The volume floor and the taker ratio are minimums, so exactly-on must pass",
            ScreenedUniverseFilter.dayPasses(boundary)
        )
    }

    @Test
    fun `given a day one quote unit under the volume floor when gated then it fails`() {
        assertFalse(ScreenedUniverseFilter.dayPasses(day(volume = 4_999_999.99)))
    }

    @Test
    fun `given a taker buy ratio just under one half when gated then it fails`() {
        assertFalse(ScreenedUniverseFilter.dayPasses(day(takerBuyRatio = 0.4999)))
    }

    @Test
    fun `given volume exactly equal to open interest when gated then the velocity leg fails because new money means strictly above one turnover`() {
        val exactlyOneTurn = day(volume = 6_000_000.0, openInterest = 6_000_000.0)

        assertEquals(1.0, exactlyOneTurn.volumeToOiVelocity, 1e-12)
        assertFalse(ScreenedUniverseFilter.dayPasses(exactlyOneTurn))
        assertTrue(
            "Just above one turnover is new money and must pass",
            ScreenedUniverseFilter.dayPasses(
                day(volume = 6_000_000.0, openInterest = 6_000_000.0 / 1.0001)
            )
        )
    }

    @Test
    fun `given unknown open interest when gated then the velocity leg fails instead of passing on a fabricated number`() {
        // The app's screening path used to substitute 1.0 USDT for a missing open interest,
        // which every coin's volume cleared many times over: the leg never failed.
        val noOpenInterest = day(volume = 4_200_000_000.0, openInterest = 0.0)

        assertEquals(0.0, noOpenInterest.volumeToOiVelocity, 0.0)
        assertFalse(ScreenedUniverseFilter.dayPasses(noOpenInterest))
    }

    @Test
    fun `given a close exactly forty percent into the day range when gated then it passes and just under it fails`() {
        assertTrue(
            "40% displacement is the minimum conviction, so exactly-on must pass",
            ScreenedUniverseFilter.dayPasses(day(open = 100.0, close = 104.0, high = 110.0, low = 100.0))
        )
        assertFalse(
            ScreenedUniverseFilter.dayPasses(day(open = 100.0, close = 103.99, high = 110.0, low = 100.0))
        )
    }

    @Test
    fun `given a flat candle when gated then displacement is zero rather than a divide by zero`() {
        val flat = day(open = 100.0, close = 100.0, high = 100.0, low = 100.0)

        assertEquals(0.0, flat.convictionDisplacement, 0.0)
        assertFalse(ScreenedUniverseFilter.dayPasses(flat))
    }

    @Test
    fun `given today fails but eight of the last ten sessions pass when assessed then the coin qualifies through persistence`() {
        val failingToday = day(volume = 2_000_000.0, openInterest = 10_000_000.0, takerBuyRatio = 0.42)
        val history = List(8) { day() } + List(2) { failingToday }

        val assessment = ScreenedUniverseFilter.assess(failingToday, history)

        assertFalse(assessment.todayPasses)
        assertEquals(8, assessment.historicalPassDays)
        assertTrue(assessment.verified)
        assertTrue(assessment.verifiedByPersistence)
    }

    @Test
    fun `given today fails and only seven of the last ten sessions pass when assessed then the coin is rejected`() {
        // `daysPassedAll > 0` — the shortcut the repository used — admitted this coin after
        // a single good session. §65 requires eight.
        val failingToday = day(volume = 2_000_000.0, openInterest = 10_000_000.0, takerBuyRatio = 0.42)
        val history = List(7) { day() } + List(3) { failingToday }

        val assessment = ScreenedUniverseFilter.assess(failingToday, history)

        assertEquals(7, assessment.historicalPassDays)
        assertFalse(assessment.verified)
        assertFalse(assessment.verifiedByPersistence)
    }

    @Test
    fun `given today passes when assessed then the coin qualifies without needing its history`() {
        val assessment = ScreenedUniverseFilter.assess(day(), emptyList())

        assertTrue(assessment.todayPasses)
        assertTrue(assessment.verified)
        assertFalse(
            "A coin that passed today did not qualify through persistence",
            assessment.verifiedByPersistence
        )
    }

    @Test
    fun `given the documented loosened rule when today fails after a single historical pass then the opt-out admits it`() {
        val failingToday = day(volume = 2_000_000.0, openInterest = 10_000_000.0, takerBuyRatio = 0.42)
        val history = List(1) { day() } + List(9) { failingToday }

        assertTrue(
            ScreenedUniverseFilter.assess(
                failingToday,
                history,
                requireStrict8Of10PersistenceWhenTodayFails = false
            ).verified
        )
        assertFalse(
            "The strict rule is the default and must reject the same coin",
            ScreenedUniverseFilter.assess(failingToday, history).verified
        )
    }
}
