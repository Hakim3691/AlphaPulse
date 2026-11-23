package com.aistudio.alphapulse.domain.screener

import com.aistudio.alphapulse.data.model.ScreenedCoin

/**
 * C-37 — the screened universe as it is cached, with its age.
 *
 * Why this exists
 * ---------------
 * A universe that outlives one process needs to say two things about itself: when it was
 * taken, and how much of the venue it saw. Without the first, a warm launch cannot tell
 * whether re-running a 250-pair scan is necessary and the UI cannot tell the user the
 * dropdown is aged. Without the second, a screen whose ticker call failed is
 * indistinguishable from a screen where nothing qualified.
 *
 * Spec Ref: §65, C-37
 */
data class ScreenedUniverseSnapshot(
    val coins: List<ScreenedCoin>,
    val fetchedAtMillis: Long,
    /** USDT perpetuals the venue's ticker returned for the run that produced [coins]. */
    val screenedPairs: Int,
    /** How many of them cleared the gate; equals the size of [coins] for a fresh run. */
    val admittedPairs: Int
) {
    /** Age in milliseconds, never negative even if the clock stepped backwards. */
    fun ageMillis(nowMillis: Long): Long = (nowMillis - fetchedAtMillis).coerceAtLeast(0L)

    /** True while the universe is young enough that a warm launch can reuse it as-is. */
    fun isFresh(nowMillis: Long): Boolean =
        ScreenedUniverseRevalidation.isFresh(nowMillis - fetchedAtMillis)

    /**
     * The age as the UI states it: `just now`, `4m ago`, `3h ago`, `2d ago`.
     *
     * Deliberately coarse — the exact second is not the point, "is this from this session"
     * is.
     */
    fun ageLabel(nowMillis: Long): String {
        val minutes = ageMillis(nowMillis) / 60_000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "${minutes}m ago"
            minutes < 60L * 24L -> "${minutes / 60L}h ago"
            else -> "${minutes / (60L * 24L)}d ago"
        }
    }
}

/** What a refresh did to the cache. */
enum class UniverseRefreshOutcome {
    /** The run produced a usable universe and replaced the cache. */
    REFRESHED,

    /**
     * The run read no ticker at all — the exchange never answered — and the previous
     * universe was kept. This is a failed refresh and the UI may say so.
     */
    KEPT_PREVIOUS,

    /**
     * The venue answered and every pair it returned failed the gate, so the previous
     * universe was kept. Nothing is broken: this is a real screen with no qualifiers,
     * and it must not be reported as a failed refresh.
     */
    NOTHING_QUALIFIED,

    /**
     * The venue answered and admitted no coin, and there was nothing cached either — the
     * screen spoke, and it spoke about the gate rather than about the feed.
     */
    NOTHING_QUALIFIED_NO_CACHE,

    /** The run read no ticker and there was nothing cached either: a failed refresh. */
    NO_DATA
}

/**
 * The universe to show, why it is the one being shown, and what the run did to get there.
 *
 * [funnel] is deliberately not persisted: it describes one run, not the cache, so adding
 * it needs no schema migration and an aged cache cannot claim a fresh run's counters.
 */
data class ScreenedUniverseRefresh(
    val snapshot: ScreenedUniverseSnapshot?,
    val outcome: UniverseRefreshOutcome,
    val funnel: UniverseFunnel? = null
)

/**
 * C-37 — stale-while-revalidate for the screened universe.
 *
 * The policy is deliberately small and has no network in it:
 *
 *  - a cache younger than [TTL_MILLIS] is fresh, and a warm launch may use it without
 *    paying for a full scan;
 *  - a run that read no ticker, or that admitted no coin, is *not* a universe — the
 *    previous cache is kept, so a failed refresh can never empty the dropdown, and the
 *    two cases stay distinguishable: a silent ticker is a failure, a venue that answered
 *    and admitted nothing is a screen with no qualifiers — with or without a cache to
 *    fall back on, since an empty screen and a broken feed are different things to say;
 *  - a run that admitted at least one coin replaces the cache outright, even if it
 *    admitted fewer coins than before: that is a real screen, and the gate is allowed to
 *    reject.
 */
object ScreenedUniverseRevalidation {

    /**
     * How old a cached universe may be before a launch revalidates it.
     *
     * Ten minutes: the gate reads daily candles, whose live session is still moving, so
     * the list is worth refreshing within the hour — but not on every resume, which would
     * re-run the 250-pair scan each time the app came forward.
     */
    const val TTL_MILLIS = 10L * 60L * 1000L

    /** True when [ageMillis] is inside the reuse window. */
    fun isFresh(ageMillis: Long): Boolean = ageMillis <= TTL_MILLIS

    /** Whether a just-finished run is a universe at all. */
    fun isUsable(run: ScreenedUniverseSnapshot): Boolean =
        run.screenedPairs > 0 && run.coins.isNotEmpty()

    /**
     * Chooses the universe to show after a refresh.
     *
     * @param previous the cache as it was before the run, or null on a first launch
     * @param run the run's own result, including how much of the venue it read
     */
    fun decide(
        previous: ScreenedUniverseSnapshot?,
        run: ScreenedUniverseSnapshot
    ): ScreenedUniverseRefresh {
        if (isUsable(run)) {
            return ScreenedUniverseRefresh(run, UniverseRefreshOutcome.REFRESHED)
        }
        // Nothing admitted is only a failure when the venue was never read. A run that
        // screened pairs and admitted none is a verdict on the gate, not a broken feed —
        // and that holds whether or not there was a cache to fall back on.
        val tickerSilent = run.screenedPairs == 0
        val usablePrevious = previous?.takeIf { it.coins.isNotEmpty() }
            ?: return ScreenedUniverseRefresh(
                null,
                if (tickerSilent) UniverseRefreshOutcome.NO_DATA
                else UniverseRefreshOutcome.NOTHING_QUALIFIED_NO_CACHE
            )
        val outcome = if (tickerSilent) {
            UniverseRefreshOutcome.KEPT_PREVIOUS
        } else {
            UniverseRefreshOutcome.NOTHING_QUALIFIED
        }
        return ScreenedUniverseRefresh(usablePrevious, outcome)
    }
}
