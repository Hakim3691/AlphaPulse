package com.aistudio.alphapulse.data.network

import com.aistudio.alphapulse.domain.pattern.PatternHorizon

/**
 * The network budget of one evaluation cycle, and the numbers that prove it.
 *
 * Why this exists
 * ---------------
 * The device was measured at ~300 MB in an hour. The cause was the ten-second cycle
 * re-downloading data that had not changed (C-30), and the fix was caching. Nothing,
 * however, asserted the *result*: a future edit could drop a TTL, un-gate the depth
 * fetch or re-add the `aggTrades` call and the only symptom would be the user's data
 * bill. This object is the single source of truth for the intervals the cycle uses and
 * for the payload assumptions the offline budget test multiplies them by, so the test
 * and the documentation cannot drift from the code.
 *
 * The bytes are documented assumptions, not measurements: each one approximates a
 * Binance response for the shape this app requests (see `docs/ci-incidents.md`,
 * "Data budget, proven offline"). Only [HTTP_OVERHEAD_BYTES] is a per-request constant
 * added to every response, covering status line and headers. The budget covers REST
 * traffic only; the WebSocket streams are socket traffic with market-dependent volume
 * and are deliberately not modelled here.
 *
 * Spec Ref: §24 Persistent Background Engine, §33 Venue Health, C-30/C-31
 */
object CycleNetworkBudget {

    /** The §24 evaluation loop's period (`AlphaPulseViewModel.startAssetCycle`). */
    const val CYCLE_INTERVAL_MS = 10_000L

    /** 1m candles are final once a minute; the intra-minute price comes from the stream. */
    const val KLINES_TTL_MS = 60_000L

    /** The 240-minute liquidation heatmap does not change every ten seconds. */
    const val HEATMAP_TTL_MS = 5L * 60L * 1000L

    /** A 1h strategy bar is final once an hour. */
    const val STRATEGY_TTL_MS = 60L * 60L * 1000L

    /** The timeframe the §3 strategy library is evaluated on (C-24). */
    const val STRATEGY_INTERVAL = "1h"

    /** Estimated per-request HTTP overhead: status line, headers, framing. */
    const val HTTP_OVERHEAD_BYTES = 300L

    const val HOUR_MS = 3_600_000L

    // ---------------------------------------------------- payload assumptions

    /** `/fapi/v1/ticker/24hr` for one symbol. */
    const val TICKER_BYTES = 600L

    /** `/fapi/v1/depth?limit=50` — 100 price tiers plus overhead. */
    const val DEPTH_BYTES = 6_000L

    /** SmartMoney liquidations heatmap, `window_minutes=240&price_buckets=100`. */
    const val HEATMAP_BYTES = 35_000L

    /** 40 × 1m klines, ~220 bytes of JSON per bar. */
    const val KLINES_1M_BYTES = 9_000L

    /** Pattern horizons request this many bars each. */
    const val PATTERN_BARS = 60

    /** 60 bars across a pattern horizon's interval. */
    const val PATTERN_KLINES_BYTES = 13_000L

    /** 500 strategy bars (C-24 lookback window). */
    const val STRATEGY_BARS = 500

    /** 500 hourly bars, ~220 bytes of JSON per bar. */
    const val STRATEGY_KLINES_BYTES = 110_000L

    // ------------------------------------------------------- request counts

    val cyclesPerHour: Int get() = (HOUR_MS / CYCLE_INTERVAL_MS).toInt()

    /**
     * Requests a TTL'd source issues in one hour of cycles.
     *
     * The cycle fetches on its first pass and again whenever the TTL has elapsed, so the
     * count is `1 + floor(lastCycleOffset / ttl)`. Deriving it here (rather than writing
     * 60) is what makes a shortened TTL fail the budget test.
     */
    fun requestsPerHour(ttlMs: Long): Int {
        require(ttlMs > 0) { "A cache TTL must be positive; 0 would fetch on every cycle" }
        val lastCycleOffset = (cyclesPerHour - 1).toLong() * CYCLE_INTERVAL_MS
        return (1 + lastCycleOffset / ttlMs).toInt()
    }

    val klinesPerHour: Int get() = requestsPerHour(KLINES_TTL_MS)
    val heatmapPerHour: Int get() = requestsPerHour(HEATMAP_TTL_MS)
    val strategySeriesPerHour: Int get() = requestsPerHour(STRATEGY_TTL_MS)

    /** One request per horizon per elapsed horizon interval (§73 pattern ledger). */
    val patternKlinesPerHour: Int
        get() = PatternHorizon.entries.sumOf { requestsPerHour(it.durationMs) }

    /** The cycle always asks for the 24hr ticker; it is the cycle's liveness probe. */
    val tickerPerHour: Int get() = cyclesPerHour

    // --------------------------------------------------------- byte estimates

    /** REST payload estimate for one hour, given how many REST depth books were needed. */
    fun estimateBytesPerHour(depthRequests: Int): Long {
        val requests = tickerPerHour + depthRequests + heatmapPerHour + klinesPerHour +
            strategySeriesPerHour + patternKlinesPerHour
        val payload = tickerPerHour * TICKER_BYTES +
            depthRequests * DEPTH_BYTES +
            heatmapPerHour * HEATMAP_BYTES +
            klinesPerHour * KLINES_1M_BYTES +
            strategySeriesPerHour * STRATEGY_KLINES_BYTES +
            patternKlinesPerHour * PATTERN_KLINES_BYTES
        return payload + requests * HTTP_OVERHEAD_BYTES
    }

    /** Bytes as mebibytes (MiB); the docs quote this figure. */
    fun mebibytes(bytes: Long): Double = bytes / (1024.0 * 1024.0)

    /**
     * The documented budget: what one hour may cost, REST only.
     *
     * Chosen slightly above the estimate so the assertion catches a regression (a dropped
     * TTL, an un-gated depth fetch, a second series per cycle) rather than jitter, while
     * still failing long before the measured ~300 MB/hour returns.
     */
    const val MAX_REST_REQUESTS_PER_HOUR_WITH_STREAM = 520
    const val MAX_REST_REQUESTS_PER_HOUR_WITHOUT_STREAM = 900
    const val MAX_REST_MIB_PER_HOUR_WITH_STREAM = 4.0

    /** The no-stream fallback adds one REST depth book per cycle; it is still bounded. */
    const val MAX_REST_MIB_PER_HOUR_WITHOUT_STREAM = 8.0

    /** Endpoint path fragments the budget test classifies a call by. */
    const val TICKER_PATH = "/fapi/v1/ticker/24hr"
    const val KLINES_PATH = "/fapi/v1/klines"
    const val DEPTH_PATH = "/fapi/v1/depth"
}
