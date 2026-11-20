package com.aistudio.alphapulse.domain.quality

import kotlin.math.min

/**
 * §35 / C-39 — one verdict on a reconstructed order book, before anything quotes it.
 *
 * The bid share is the most quotable number in the workspace: it goes into the regime
 * agent, the decision router's book leg, the executive brief and the depth panel. It was
 * also the easiest number to fake, because both the evaluation cycle and the depth stream
 * initialised it to `50.0` and left it there when no depth arrived. A coin with no book at
 * all therefore read as a perfectly balanced one.
 *
 * This gate refuses a reading instead of inventing one. It applies the §35 checks that
 * matter for depth — stale-data age, order-book sequence integrity (a crossed book is a
 * broken reconstruction) and book depth — and reports why, so callers can say "no book"
 * rather than print a split nobody observed.
 *
 * Spec Ref: §35 Data Quality Engine, §7 FR-006
 */

/** What the guard made of the book it was handed. */
enum class BookHealth {
    /** Deep, fresh, uncrossed: the reading may be quoted. */
    LIVE,
    /** No depth arrived, or the book cannot be dated. */
    ABSENT,
    /** The newest depth message is older than the freshness limit. */
    STALE,
    /** Best bid at or above the best ask: the reconstruction is wrong. */
    CROSSED,
    /** Fewer levels than the L2 reconstruction needs to mean anything. */
    SHALLOW
}

/**
 * The guard's answer.
 *
 * @property health what the book was judged to be
 * @property bidRatioPct share of quoted notional resting on the bid, 0..100, or null when
 *   the book is not [BookHealth.LIVE] — null means "not observed", never "balanced"
 * @property netImbalanceUsdt bid notional minus ask notional, 0 when refused
 * @property bidNotionalUsdt notional across the priced bid levels
 * @property askNotionalUsdt notional across the priced ask levels
 * @property spreadBps top-of-book spread in basis points, 0 when refused
 * @property levels levels priced per side
 */
data class BookReading(
    val health: BookHealth,
    val bidRatioPct: Double?,
    val netImbalanceUsdt: Double,
    val bidNotionalUsdt: Double,
    val askNotionalUsdt: Double,
    val spreadBps: Double,
    val levels: Int
) {
    /** True only when the reading describes a book that could actually be trusted. */
    val isUsable: Boolean
        get() = health == BookHealth.LIVE && bidRatioPct != null
}

object MicrostructureGuard {

    /**
     * A 250 ms depth stream is fresh to within a cycle; 30 s is three missed evaluation
     * cycles, beyond which a keep-alive socket that stopped delivering would still look
     * live if the age were ignored.
     */
    const val MAX_BOOK_AGE_MS: Long = 30_000L

    /** Ten levels a side is the minimum for the L2 reconstruction to mean anything (§35). */
    const val MIN_BOOK_LEVELS: Int = 10

    /** The same top-20 window the stream and the REST fallback quote. */
    const val MAX_LEVELS_PER_SIDE: Int = 20

    /**
     * Assesses one book.
     *
     * @param bids depths as (price, quantity), best first
     * @param asks depths as (price, quantity), best first
     * @param bookTimestampMs when the book was received; 0 means it cannot be dated
     * @param nowMs the clock the age is measured against
     */
    fun assess(
        bids: List<Pair<Double, Double>>,
        asks: List<Pair<Double, Double>>,
        bookTimestampMs: Long,
        nowMs: Long,
        maxAgeMs: Long = MAX_BOOK_AGE_MS,
        minLevels: Int = MIN_BOOK_LEVELS
    ): BookReading {
        val levels = min(MAX_LEVELS_PER_SIDE, min(bids.size, asks.size))

        fun refused(health: BookHealth) = BookReading(
            health = health,
            bidRatioPct = null,
            netImbalanceUsdt = 0.0,
            bidNotionalUsdt = 0.0,
            askNotionalUsdt = 0.0,
            spreadBps = 0.0,
            levels = levels
        )

        if (bids.isEmpty() || asks.isEmpty() || bookTimestampMs <= 0L) {
            return refused(BookHealth.ABSENT)
        }

        val ageMs = (nowMs - bookTimestampMs).coerceAtLeast(0L)
        if (ageMs > maxAgeMs) return refused(BookHealth.STALE)

        val bestBid = bids.maxOf { it.first }
        val bestAsk = asks.minOf { it.first }
        if (bestBid >= bestAsk) return refused(BookHealth.CROSSED)

        if (levels < minLevels) return refused(BookHealth.SHALLOW)

        var bidNotional = 0.0
        var askNotional = 0.0
        for (i in 0 until levels) {
            bidNotional += bids[i].first * bids[i].second
            askNotional += asks[i].first * asks[i].second
        }

        val totalDepth = bidNotional + askNotional
        if (!totalDepth.isFinite() || totalDepth <= 0.0) return refused(BookHealth.ABSENT)

        val mid = (bestBid + bestAsk) / 2.0
        val spreadBps = if (mid > 0.0) ((bestAsk - bestBid) / mid) * 10_000.0 else 0.0

        return BookReading(
            health = BookHealth.LIVE,
            bidRatioPct = (bidNotional / totalDepth) * 100.0,
            netImbalanceUsdt = bidNotional - askNotional,
            bidNotionalUsdt = bidNotional,
            askNotionalUsdt = askNotional,
            spreadBps = spreadBps,
            levels = levels
        )
    }
}
