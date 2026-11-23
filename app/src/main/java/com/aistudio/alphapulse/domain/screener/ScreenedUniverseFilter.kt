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

/**
 * Which of the four legs one session cleared, evaluated once so the verdict and the
 * funnel counters cannot disagree about the same candle.
 */
data class ScreeningLegs(
    val volume: Boolean,
    val taker: Boolean,
    val velocity: Boolean,
    val displacement: Boolean
) {
    /** Every leg held: this session passes the gate. */
    val all: Boolean get() = volume && taker && velocity && displacement

    /**
     * True when every leg up to and including [index] held, so a funnel can count the
     * survivors of each step from one evaluation of the same candle.
     *
     * Legs are ordered as the gate applies them: 0 volume, 1 taker, 2 velocity,
     * 3 displacement.
     */
    fun survivedThrough(index: Int): Boolean = when (index) {
        0 -> volume
        1 -> volume && taker
        2 -> volume && taker && velocity
        else -> all
    }
}

/**
 * What a screen did to the pairs it read, leg by leg, in the order the gate applies them.
 *
 * Why this exists
 * ---------------
 * A universe that shrinks says only that it shrank. The operator (and the log) could not
 * tell whether the venue returned fewer pairs, or the volume floor rejected them, or the
 * open-interest leg could not be read — the three failures have nothing in common and
 * two of them are bugs. Each counter is the number of pairs still standing after that
 * leg, so the last step is the number admitted.
 *
 * Spec Ref: §65, C-37
 */
data class UniverseFunnel(
    val pairsRead: Int = 0,
    val volumeLeg: Int = 0,
    val takerLeg: Int = 0,
    val velocityLeg: Int = 0,
    val displacementLeg: Int = 0,
    val admittedToday: Int = 0,
    val admittedByPersistence: Int = 0,
    /** Pairs whose open interest neither Binance nor CoinGlass could price. */
    val openInterestUnread: Int = 0
) {
    /** Coins this run admitted, whether on today's candle or on its history. */
    val admitted: Int get() = admittedToday + admittedByPersistence

    /**
     * The one-line account of the run: `298 pairs → ... → 5 admitted`.
     *
     * Deliberately a single line: it sits in the pipeline subtitle and in the notice the
     * workspace shows when it keeps a cached universe.
     */
    fun summary(): String =
        "${pairsRead}p → ${volumeLeg}v → ${takerLeg}t → ${velocityLeg}oi → " +
            "${displacementLeg}d → $admitted" +
            (if (openInterestUnread > 0) " (${openInterestUnread} OI unread)" else "")
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

    /**
     * §65 persistence rule: six of the last ten sessions.
     *
     * Relaxed from eight of ten by C-37: with the honest, strict gate in place a coin that
     * is merely quiet today was being evicted after two bad sessions, and the shipped
     * universe collapsed to a handful of pairs. Six still requires a majority of the
     * window and still rejects one lucky day.
     */
    const val PERSISTENCE_DAYS_REQUIRED = 6

    /** How many sessions the persistence window holds. */
    const val PERSISTENCE_WINDOW_DAYS = 10

    /** Each of the four legs of one session, in the order the funnel counts them. */
    fun legs(day: DailyFilterSnapshot): ScreeningLegs = ScreeningLegs(
        volume = day.quoteVolume24hUsdt >= MIN_DAILY_QUOTE_VOLUME_USDT,
        taker = day.takerBuyRatio >= MIN_TAKER_BUY_RATIO,
        velocity = day.volumeToOiVelocity > MIN_VOLUME_TO_OI_VELOCITY,
        displacement = day.convictionDisplacement >= MIN_CONVICTION_DISPLACEMENT
    )

    /** True when every leg of one session clears its threshold. */
    fun dayPasses(day: DailyFilterSnapshot): Boolean = legs(day).all

    /**
     * Today's snapshot with the venue's trailing 24-hour volume instead of the partial one.
     *
     * Why this exists
     * ---------------
     * `/fapi/v1/klines?interval=1d` returns the UTC day that is still forming, so its
     * `quoteVolume` is however much of a day has elapsed — at 06:00 UTC it is a quarter of
     * a day's turnover. Dividing that by the *live* open interest understated the velocity
     * leg by up to twelve times, and the universe therefore depended on the time of day:
     * the same coin was admitted at midnight and rejected at dawn.
     *
     * The ticker's `quoteVolume` is a rolling 24 hours, so it is comparable with live open
     * interest whenever the screen runs. The candle's own fields stay: the taker-buy ratio
     * and the displacement are ratios of that candle and do not shrink with the day.
     *
     * @param rollingQuoteVolume24h the venue's trailing 24-hour quote volume, or 0.0 when
     *   the ticker gave none — in which case the candle's own volume is the honest fallback
     */
    fun todayFromKline(
        kline: DailyFilterSnapshot,
        rollingQuoteVolume24h: Double
    ): DailyFilterSnapshot = if (rollingQuoteVolume24h > 0.0) {
        kline.copy(quoteVolume24hUsdt = rollingQuoteVolume24h)
    } else {
        kline
    }

    /**
     * Applies §65 to today's candle and the sessions before it.
     *
     * @param requireStrict8Of10PersistenceWhenTodayFails when true (the §65 rule), a
     *   coin that fails today still qualifies if six of the supplied sessions passed
     *   ([PERSISTENCE_DAYS_REQUIRED]);
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
