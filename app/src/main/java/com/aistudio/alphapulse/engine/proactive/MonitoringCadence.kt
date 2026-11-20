package com.aistudio.alphapulse.engine.proactive

/**
 * §25 — the four synchronised monitoring cadences.
 *
 * The Phase 2 monitoring loop is not one timer: each cadence owns a different class
 * of evidence and a different engine action, and a closed candle is an event rather
 * than an elapsed interval. [MonitoringScheduler] decides which cadences are due for
 * a frame so the loop evaluates the expensive evidence only when its cadence allows,
 * instead of on every WebSocket delta.
 *
 * Spec Ref: Phase 2 §25
 */
enum class MonitoringCadence(val intervalMillis: Long, val specInterval: String) {
    /** Top-of-book spread, queue depth and fill notifications, on every delta. */
    PER_TICK(0L, "per tick / delta"),

    /** OI delta, basis, funding and liquidation bursts; recompute the score. */
    FAST_TELEMETRY(1_000L, "every 1-5 seconds"),

    /** Micro-absorption, wick rejection and 1m close vs zone; acceptance invalidation. */
    CLOSED_1M(60_000L, "closed 1m candle"),

    /** Zone integrity, ATR refresh and forecast update; promotion or suppression. */
    CLOSED_5M_15M(300_000L, "closed 5m / 15m candle")
}

/** What a cadence orders the engine to do. */
enum class MonitoringAction {
    RECONCILE_PARTIAL_FILLS,
    SPREAD_BLOWOUT_SAFETY,
    RECOMPUTE_INVALIDATION_SCORE,
    EVALUATE_ACCEPTANCE_INVALIDATION,
    CANCEL_UNFILLED_ON_ACCEPTANCE,
    REFRESH_STRUCTURE_AND_ATR,
    EVALUATE_BOARD_PROMOTION,
    SUPPRESS_SECONDARY_OR_COOLDOWN
}

/** The actions §25 assigns to each cadence, in the order the loop must run them. */
fun MonitoringCadence.actions(): List<MonitoringAction> = when (this) {
    MonitoringCadence.PER_TICK -> listOf(
        MonitoringAction.RECONCILE_PARTIAL_FILLS,
        MonitoringAction.SPREAD_BLOWOUT_SAFETY
    )

    MonitoringCadence.FAST_TELEMETRY -> listOf(
        MonitoringAction.RECOMPUTE_INVALIDATION_SCORE
    )

    MonitoringCadence.CLOSED_1M -> listOf(
        MonitoringAction.EVALUATE_ACCEPTANCE_INVALIDATION,
        MonitoringAction.CANCEL_UNFILLED_ON_ACCEPTANCE
    )

    MonitoringCadence.CLOSED_5M_15M -> listOf(
        MonitoringAction.REFRESH_STRUCTURE_AND_ATR,
        MonitoringAction.EVALUATE_BOARD_PROMOTION,
        MonitoringAction.SUPPRESS_SECONDARY_OR_COOLDOWN
    )
}

/**
 * Decides which §25 cadences are due.
 *
 * Interval cadences run when their interval has elapsed; candle cadences run only
 * when the caller reports the candle closed, so a quiet market still evaluates the
 * 1m/5m/15m evidence exactly once per bar and never twice within it.
 */
class MonitoringScheduler {

    private val lastRunMillis = mutableMapOf<MonitoringCadence, Long>()

    fun dueCadences(
        nowMillis: Long,
        oneMinuteCandleClosed: Boolean,
        fiveMinuteCandleClosed: Boolean,
        fifteenMinuteCandleClosed: Boolean
    ): List<MonitoringCadence> {
        val due = mutableListOf(MonitoringCadence.PER_TICK)

        if (intervalElapsed(MonitoringCadence.FAST_TELEMETRY, nowMillis)) {
            due += MonitoringCadence.FAST_TELEMETRY
        }
        if (oneMinuteCandleClosed) {
            due += MonitoringCadence.CLOSED_1M
        }
        val structuralCandleClosed = fiveMinuteCandleClosed || fifteenMinuteCandleClosed
        if (structuralCandleClosed &&
            intervalElapsed(MonitoringCadence.CLOSED_5M_15M, nowMillis)
        ) {
            due += MonitoringCadence.CLOSED_5M_15M
        }
        return due
    }

    fun markRun(cadence: MonitoringCadence, nowMillis: Long) {
        lastRunMillis[cadence] = nowMillis
    }

    fun lastRun(cadence: MonitoringCadence): Long? = lastRunMillis[cadence]

    private fun intervalElapsed(cadence: MonitoringCadence, nowMillis: Long): Boolean {
        val last = lastRunMillis[cadence] ?: return true
        return nowMillis - last >= cadence.intervalMillis
    }
}
