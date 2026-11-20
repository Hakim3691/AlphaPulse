package com.aistudio.alphapulse.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.repository.AlphaPulseRepository
import com.aistudio.alphapulse.domain.ml.TsfmInput
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecast
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecaster
import com.aistudio.alphapulse.domain.quant.DepthSnapshot
import com.aistudio.alphapulse.domain.quant.DepthTier
import com.aistudio.alphapulse.domain.quant.ModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One simulated hour of the ten-second evaluation cycle, counted.
 *
 * The device once used ~300 MB in an hour because the cycle re-downloaded data that had
 * not changed. `FetchThrottle`, the dropped `aggTrades` call and the stream-gated depth
 * fetch are the fix (C-30), but no test proved the *result*: without one, a future edit
 * could remove a TTL or re-add a duplicate fetch and only the user's data bill would
 * notice. This spec walks [CycleNetworkBudget.cyclesPerHour] real
 * [AlphaPulseRepository.executeAssetEvaluation] calls through a fake clock, with a client
 * that records every request, and asserts the hourly totals against the documented budget.
 *
 * Nothing here touches the network or a device: the exchange is JSON literals, the
 * WebSocket manager never opens a socket, and the clock is the one the test advances.
 *
 * The documented budget is the literal table in [DOCUMENTED_REQUESTS_PER_HOUR] and
 * [CycleNetworkBudget], and the payload assumptions are the `*_BYTES` constants there.
 * The test fails if a measured count drifts from the table, if a TTL-derived expectation
 * drifts from it (so shortening `KLINES_TTL_MS` or `HEATMAP_TTL_MS` fails), or if the
 * estimated payload exceeds the published per-hour ceiling.
 *
 * On-device traffic is *not* measured here and cannot be: see `docs/ci-incidents.md`,
 * "Data budget, proven offline".
 *
 * Spec Ref: §24 Persistent Background Engine, §52 Testing Requirements, C-30/C-31
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CycleNetworkBudgetBddSpec {

    private lateinit var db: AppDatabase
    private var clockNow = START_MS

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clockNow = START_MS
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------- with stream

    @Test
    fun `given a live book on the stream when an hour of cycles runs then REST stays inside the documented budget`() =
        runBlocking {
            val client = CountingClient()
            val repository = repository(client = client, wsManager = BookedStreamManager())

            val calls = runOneHour(repository, client)

            assertEquals(
                "The 24hr ticker is the cycle's only unconditional REST call",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("ticker24hr"),
                calls.count { it.startsWith(CycleNetworkBudget.TICKER_PATH) }
            )
            assertEquals(
                "A streamed book must make the REST depth fetch unnecessary",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("depth-with-stream"),
                calls.count { it.startsWith(CycleNetworkBudget.DEPTH_PATH) }
            )
            assertEquals(
                "1m klines are reused for a minute, not refetched every cycle",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1m"),
                calls.count { it == FORECAST_KLINES }
            )
            assertEquals(
                "The §73 pattern series requests one series per horizon interval",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("pattern-klines"),
                calls.count { it.startsWith("klines:") } - calls.count { it == FORECAST_KLINES } -
                    calls.count { it == STRATEGY_KLINES }
            )
            assertEquals(
                "The aggTrade tape must not be downloaded over REST on top of the stream",
                0,
                calls.count { it.startsWith("aggTrades") }
            )
            assertEquals(
                "The 240-minute heatmap is fetched once per five minutes",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("heatmap"),
                calls.count { it == "heatmap" }
            )
            assertEquals(
                "The strategy series is fetched once per hourly bar",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1h-500"),
                calls.count { it == STRATEGY_KLINES }
            )

            val expectedTotal = DOCUMENTED_REQUESTS_PER_HOUR.getValue("total-with-stream")
            assertEquals("Total REST requests for one simulated hour", expectedTotal, calls.size)

            val estimatedBytes = estimate(depthRequests = 0)
            val mib = CycleNetworkBudget.mebibytes(estimatedBytes)
            val mibText = String.format(java.util.Locale.US, "%.2f", mib)
            assertTrue(
                "Estimated REST payload $mibText MiB/hour must stay under " +
                    "${CycleNetworkBudget.MAX_REST_MIB_PER_HOUR_WITH_STREAM} MiB: $calls",
                mib <= CycleNetworkBudget.MAX_REST_MIB_PER_HOUR_WITH_STREAM
            )
            assertTrue(
                "Request count must stay under the published ceiling",
                calls.size <= CycleNetworkBudget.MAX_REST_REQUESTS_PER_HOUR_WITH_STREAM
            )
            println(
                "CycleNetworkBudget: ${calls.size} REST requests/hour, " +
                    "$mibText MiB/hour estimated (streamed book)"
            )
        }

    // ---------------------------------------------------------- without stream

    @Test
    fun `given no live book when an hour of cycles runs then depth is fetched per cycle and the budget still holds`() =
        runBlocking {
            val client = CountingClient()
            val repository = repository(client = client, wsManager = SilentStreamManager())

            val calls = runOneHour(repository, client)

            assertEquals(
                "Without a stream the cycle must fall back to REST depth once per cycle",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("depth-without-stream"),
                calls.count { it.startsWith(CycleNetworkBudget.DEPTH_PATH) }
            )
            assertEquals(
                "Only the depth fallback may differ from the streamed case",
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("total-without-stream"),
                calls.size
            )

            val mib = CycleNetworkBudget.mebibytes(
                estimate(
                    depthRequests = calls.count {
                        it.startsWith(CycleNetworkBudget.DEPTH_PATH)
                    }
                )
            )
            val mibText = String.format(java.util.Locale.US, "%.2f", mib)
            assertTrue(
                "Even the depth fallback must stay under the published ceiling: $mibText MiB",
                mib <= CycleNetworkBudget.MAX_REST_MIB_PER_HOUR_WITHOUT_STREAM
            )
            println(
                "CycleNetworkBudget: ${calls.size} REST requests/hour, " +
                    "$mibText MiB/hour estimated (REST depth fallback)"
            )
        }

    // ------------------------------------------------------------ duplicates

    @Test
    fun `given one cycle when its calls are listed then no endpoint is fetched twice in that cycle`() =
        runBlocking {
            val client = CountingClient()
            val repository = repository(client = client, wsManager = BookedStreamManager())

            repository.executeAssetEvaluation(symbol = "BTCUSDT", modelState = ModelState())

            val duplicates = client.calls.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue("One cycle must not fetch the same endpoint twice: $duplicates", duplicates.isEmpty())
        }

    // ------------------------------------------------- the documented budget

    @Test
    fun `given the documented intervals when the hour is derived then they match the published table`() {
        assertEquals(
            "The cycle period is the ten-second §24 loop",
            360,
            CycleNetworkBudget.cyclesPerHour
        )
        assertEquals(360, CycleNetworkBudget.tickerPerHour)
        assertEquals(60, CycleNetworkBudget.klinesPerHour)
        assertEquals(12, CycleNetworkBudget.heatmapPerHour)
        assertEquals(1, CycleNetworkBudget.strategySeriesPerHour)
        assertEquals(79, CycleNetworkBudget.patternKlinesPerHour)

        // The published table and the constants the production code uses must agree, in
        // both directions: a shortened TTL fails here before it reaches a device.
        assertEquals(DOCUMENTED_REQUESTS_PER_HOUR.getValue("ticker24hr"), CycleNetworkBudget.tickerPerHour)
        assertEquals(DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1m"), CycleNetworkBudget.klinesPerHour)
        assertEquals(DOCUMENTED_REQUESTS_PER_HOUR.getValue("heatmap"), CycleNetworkBudget.heatmapPerHour)
        assertEquals(
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1h-500"),
            CycleNetworkBudget.strategySeriesPerHour
        )
        assertEquals(
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("pattern-klines"),
            CycleNetworkBudget.patternKlinesPerHour
        )
    }

    @Test
    fun `given a shortened kline ttl when its hour is derived then the budget test would reject it`() {
        // Regression guard for the bug itself: the old behaviour was a kline fetch on every
        // cycle. It must be visibly over the published budget rather than quietly allowed.
        val everyCycle = CycleNetworkBudget.requestsPerHour(CycleNetworkBudget.CYCLE_INTERVAL_MS)
        assertEquals(360, everyCycle)
        assertTrue(
            "A per-cycle kline fetch (360/hour) must exceed the published ${
                DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1m")
            }/hour budget",
            everyCycle > DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1m")
        )
    }

    // ---------------------------------------------------------------- harness

    /** Runs the real cycle 360 times, advancing the fake clock by one cycle period. */
    private suspend fun runOneHour(
        repository: AlphaPulseRepository,
        client: CountingClient
    ): List<String> {
        repeat(CycleNetworkBudget.cyclesPerHour) {
            repository.executeAssetEvaluation(symbol = SYMBOL, modelState = ModelState())
            clockNow += CycleNetworkBudget.CYCLE_INTERVAL_MS
        }
        return client.calls.toList()
    }

    private fun repository(
        client: CountingClient,
        wsManager: BinanceWebSocketManager
    ): AlphaPulseRepository = AlphaPulseRepository(
        client = client,
        database = db,
        wsManager = wsManager,
        tsfmForecaster = NoTsfmForecaster(),
        clock = { clockNow }
    )

    private fun estimate(depthRequests: Int): Long {
        // Counts come from the documented table; bytes come from CycleNetworkBudget's
        // documented payload assumptions. Keeping this arithmetic explicit is the point:
        // the MB/hour figure in the docs is this expression.
        val requests = DOCUMENTED_REQUESTS_PER_HOUR.getValue("ticker24hr") +
            depthRequests +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("heatmap") +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1m") +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1h-500") +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("pattern-klines")
        val payload = DOCUMENTED_REQUESTS_PER_HOUR.getValue("ticker24hr") * CycleNetworkBudget.TICKER_BYTES +
            depthRequests * CycleNetworkBudget.DEPTH_BYTES +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("heatmap") * CycleNetworkBudget.HEATMAP_BYTES +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1m") * CycleNetworkBudget.KLINES_1M_BYTES +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("klines-1h-500") * CycleNetworkBudget.STRATEGY_KLINES_BYTES +
            DOCUMENTED_REQUESTS_PER_HOUR.getValue("pattern-klines") * CycleNetworkBudget.PATTERN_KLINES_BYTES
        return payload + requests * CycleNetworkBudget.HTTP_OVERHEAD_BYTES
    }

    /**
     * The published budget: requests one simulated hour may issue, REST only.
     *
     * Every figure is derived from the intervals in `CycleNetworkBudget` and asserted
     * against them, so the table cannot drift from the code:
     * `1 + floor(3590 s / TTL)` fetches for a cached source, one per horizon interval for
     * the §73 pattern series (60+12+4+2+1), one per cycle for the ticker, and one per
     * cycle for depth only when no WebSocket book is up.
     */
    private companion object {
        const val SYMBOL = "BTCUSDT"

        /** `/fapi/v1/klines?interval=1m&limit=40`, the 40-bar forecast snapshot. */
        const val FORECAST_KLINES = "klines:1m:40"

        /** The §74 board's 500 hourly bars (C-24). */
        const val STRATEGY_KLINES = "klines:1h:500"

        /** 2023-11-14T22:15:00Z, aligned to both a minute and a five-minute boundary. */
        const val START_MS = 1_700_000_100_000L

        val DOCUMENTED_REQUESTS_PER_HOUR: Map<String, Int> = mapOf(
            "ticker24hr" to 360,
            "klines-1m" to 60,
            "heatmap" to 12,
            "klines-1h-500" to 1,
            "pattern-klines" to 79,
            "depth-with-stream" to 0,
            "depth-without-stream" to 360,
            "total-with-stream" to 512,
            "total-without-stream" to 872
        )
    }

    // ---------------------------------------------------------------- fixtures

    /** A client whose exchange is JSON literals, recording the path of every call. */
    private class CountingClient : ResilientBinanceClient() {

        val calls: MutableList<String> = mutableListOf()

        val depthRequests: Int get() = calls.count { it.startsWith(CycleNetworkBudget.DEPTH_PATH) }

        override suspend fun getJsonObject(path: String): JSONObject {
            calls += path
            require(path.startsWith(CycleNetworkBudget.TICKER_PATH)) { "Unexpected object path: $path" }
            return JSONObject(
                """{"lastPrice":"60000.0","priceChangePercent":"0.85",
                   "highPrice":"60500.0","lowPrice":"59200.0"}""".trimIndent()
            )
        }

        override suspend fun getJsonArray(path: String): JSONArray {
            calls += klineKey(path)
            val limit = path.substringAfter("limit=").toInt()
            return klines(limit)
        }

        override suspend fun fetchDepth(symbol: String, limit: Int): DepthSnapshot {
            calls += "${CycleNetworkBudget.DEPTH_PATH}?symbol=$symbol&limit=$limit"
            return DepthSnapshot(
                bids = (0 until 10).map { DepthTier(price = 60_000.0 - it * 5.0, quantity = 2.0) },
                asks = (0 until 10).map { DepthTier(price = 60_005.0 + it * 5.0, quantity = 2.0) }
            )
        }

        override suspend fun fetchAggTrades(symbol: String, limit: Int): List<Double> {
            calls += "aggTrades:$limit"
            return emptyList()
        }

        override suspend fun fetchLiquidityHeatmap(symbol: String, spotPrice: Double): Pair<Double, Double> {
            calls += "heatmap"
            return 1.0 to 1.0
        }

        /** `klines:<interval>:<limit>`, so one bucket per distinct series request. */
        private fun klineKey(path: String): String {
            val interval = path.substringAfter("interval=").substringBefore("&")
            val limit = path.substringAfter("limit=")
            return "klines:$interval:$limit"
        }

        private fun klines(limit: Int): JSONArray {
            val array = JSONArray()
            var close = 59_000.0
            repeat(limit) { index ->
                val open = close
                close = open * 1.0005
                val openTime = 1_700_000_000_000L + index * 60_000L
                array.put(
                    JSONArray().apply {
                        put(openTime)
                        put(open)
                        put(close * 1.0005)
                        put(open * 0.9995)
                        put(close)
                        put(120.0)
                        put(openTime + 59_999L)
                        put(7_200_000.0)
                        put(300)
                        put(60.0)
                        put(3_600_000.0)
                        put(0.0)
                    }
                )
            }
            return array
        }
    }

    /** No socket is opened; there is no book, so the cycle must ask REST for depth. */
    private class SilentStreamManager : BinanceWebSocketManager() {
        override fun startStreaming(symbol: String) = Unit
    }

    /** No socket is opened, but the cycle sees the book a live stream would have provided. */
    private class BookedStreamManager : BinanceWebSocketManager() {
        override fun startStreaming(symbol: String) = Unit

        override val telemetryFlow: StateFlow<LiveStreamTelemetry?> = MutableStateFlow(
            LiveStreamTelemetry(
                symbol = "BTCUSDT",
                spotPrice = 60_000.0,
                bids = (0 until 10).map { DepthTier(price = 59_995.0 - it * 5.0, quantity = 2.0) },
                asks = (0 until 10).map { DepthTier(price = 60_005.0 + it * 5.0, quantity = 2.0) },
                empiricalShock = 1_000.0,
                lambdaTurnover = 0.035,
                lastUpdateTimestamp = START_MS
            )
        )
    }

    private class NoTsfmForecaster : TsfmForecaster {
        override suspend fun syncMountedModels(modelIds: List<String>) = Unit
        override suspend fun forecast(
            modelId: String,
            tfmInput: TsfmInput,
            closes: DoubleArray,
            steps: Int
        ): TsfmForecast? = null
    }
}
