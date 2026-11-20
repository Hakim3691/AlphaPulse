package com.aistudio.alphapulse.domain.screener

import kotlin.math.abs

/**
 * §65 / Contract C-17 — the one place the institutional flow gate is defined.
 *
 * Why this exists
 * ---------------
 * The gate used to exist twice: once as a pure object the guard spec drove, and once
 * inline inside `AlphaPulseRepository.runInstitutionalScreening`. The copy drifted in
 * the way that matters most — it admitted a coin on **one** historical pass
 * (`daysPassedAll > 0`) where §65 requires eight of the last ten sessions — and it
 * fabricated open interest (`liveOiVal ?: 1.0`) so a coin whose OI could not be read
 * still cleared the velocity leg. Neither defect was visible from the UI: the badge
 * simply said "10D Rule".
 *
 * Thresholds, the persistence window and the verdict now live here. The app's screening
 * path and [UnifiedUniverseScreener] both call [assess]; `ScreenedUniverseFilterBddSpec`
 * pins the boundaries and `UnifiedUniverseScreenerBddSpec` fails the build if the
 * repository grows a private copy again.
 *
 * Spec Ref: §65 Unified Universe Screener, C-17
 */

/**
 * One day of the per-coin flow the gate reads.
 *
 * `takerBuyRatio` is a fraction (0.50 = 50%), `quoteVolume24hUsdt` and
 * `openInterestUsdt` are both quote-denominated so the velocity leg compares like
 * with like.
 */
data class DailyFilterSnapshot(
    val quoteVolume24hUsdt: Double,
    val takerBuyRatio: Double,
    val openInterestUsdt: Double,
    val openPrice: Double,
    val closePrice: Double,
    val highPrice: Double,
    val lowPrice: Double
) {
    /**
     * How many times the open interest turned over today; above 1.0 is new money.
     *
     * Unknown open interest is 0.0, never a substitute value: a coin cannot be
     * verified as receiving new money from a number the app made up.
     */
    val volumeToOiVelocity: Double
        get() = if (openInterestUsdt > 0.0) quoteVolume24hUsdt / openInterestUsdt else 0.0

    /** Where the close landed inside the day's range, 0.0 (at the low) to 1.0 (at the high). */
    val convictionDisplacement: Double
        get() {
            val range = highPrice - lowPrice
            return if (range > 0.0) abs(closePrice - openPrice) / range else 0.0
        }

    /** The four intraday filters of §65, all of which must hold together. */
    fun passesFourFilterGate(): Boolean = ScreenedUniverseFilter.dayPasses(this)
}

/** What the four-filter gate decided for one coin, today and over its history. */
data class UniverseFilterAssessment(
    /** Every leg of today's candle cleared its threshold. */
    val todayPasses: Boolean,
    /** How many of the supplied historical sessions cleared every leg. */
    val historicalPassDays: Int,
    /** The §65 verdict: today passed, or the persistence window did. */
    val verified: Boolean,
    /** True when the coin qualified through history because today did not. */
    val verifiedByPersistence: Boolean
)

object ScreenedUniverseFilter {

    /** The absolute floor below which a ladder cannot be staged unnoticed. */
    const val MIN_DAILY_QUOTE_VOLUME_USDT = 5_000_000.0

    /** Aggressive flow is buying, not selling: taker-buy at or above one half. */
    const val MIN_TAKER_BUY_RATIO = 0.50

    /** Volume turns open interest over more than once: new money, not rolling positions. */
    const val MIN_VOLUME_TO_OI_VELOCITY = 1.0

    /** The close finished away from the open relative to the day's range. */
    const val MIN_CONVICTION_DISPLACEMENT = 0.40

    /** §65 persistence rule: eight of the last ten sessions. */
    const val PERSISTENCE_DAYS_REQUIRED = 8

    /** How many sessions the persistence window holds. */
    const val PERSISTENCE_WINDOW_DAYS = 10

    /** True when every leg of one session clears its threshold. */
    fun dayPasses(day: DailyFilterSnapshot): Boolean {
        return day.quoteVolume24hUsdt >= MIN_DAILY_QUOTE_VOLUME_USDT &&
            day.takerBuyRatio >= MIN_TAKER_BUY_RATIO &&
            day.volumeToOiVelocity > MIN_VOLUME_TO_OI_VELOCITY &&
            day.convictionDisplacement >= MIN_CONVICTION_DISPLACEMENT
    }

    /**
     * Applies §65 to today's candle and the sessions before it.
     *
     * @param requireStrict8Of10PersistenceWhenTodayFails when true (the §65 rule), a
     *   coin that fails today still qualifies if eight of the supplied sessions passed;
     *   when false, a single historical pass is enough.
     */
    fun assess(
        today: DailyFilterSnapshot,
        last10Days: List<DailyFilterSnapshot>,
        requireStrict8Of10PersistenceWhenTodayFails: Boolean = true
    ): UniverseFilterAssessment {
        val todayPasses = dayPasses(today)
        val historicalPassDays = last10Days.count { dayPasses(it) }
        val requiredDays = if (requireStrict8Of10PersistenceWhenTodayFails) {
            PERSISTENCE_DAYS_REQUIRED
        } else {
            1
        }
        val verifiedByPersistence = !todayPasses && historicalPassDays >= requiredDays
        return UniverseFilterAssessment(
            todayPasses = todayPasses,
            historicalPassDays = historicalPassDays,
            verified = todayPasses || verifiedByPersistence,
            verifiedByPersistence = verifiedByPersistence
        )
    }
}
