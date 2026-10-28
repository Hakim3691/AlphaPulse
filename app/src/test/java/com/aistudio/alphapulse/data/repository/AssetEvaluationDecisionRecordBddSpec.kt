package com.aistudio.alphapulse.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.network.BinanceWebSocketManager
import com.aistudio.alphapulse.data.network.ResilientBinanceClient
import com.aistudio.alphapulse.domain.decision.DecisionSource
import com.aistudio.alphapulse.domain.decision.DecisionState
import com.aistudio.alphapulse.domain.ml.TsfmInput
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecast
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecaster
import com.aistudio.alphapulse.domain.quant.DepthSnapshot
import com.aistudio.alphapulse.domain.quant.DepthTier
import com.aistudio.alphapulse.domain.quant.ModelState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live evaluate-and-record path, specified Given/When/Then.
 *
 * `DecisionRecordDaoBddSpec` proves the table can store a verdict it is handed;
 * this spec proves the *producer*: [AlphaPulseRepository.executeAssetEvaluation]
 * — the method the 10-second loop calls — takes a market snapshot, routes it and
 * writes the verdict, all the way into real Room storage.
 *
 * Exchange data comes from [FixtureClient], a [ResilientBinanceClient] whose
 * "network" is JSON literals, and [OfflineStreamManager] which never opens a
 * socket. No checkpoint is mounted, so the router must record a
 * `DETERMINISTIC` verdict; nothing here exercises the on-device model.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router,
 * §37 Room Database, §52 Testing Requirements
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssetEvaluationDecisionRecordBddSpec {

    private lateinit var db: AppDatabase
    private lateinit var repository: AlphaPulseRepository

    private val referenceHorizon = "15m"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AlphaPulseRepository(
            client = FixtureClient(),
            database = db,
            wsManager = OfflineStreamManager(),
            tsfmForecaster = NoTsfmForecaster()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The one call the asset cycle makes. */
    private suspend fun evaluate(symbol: String) =
        repository.executeAssetEvaluation(symbol = symbol, modelState = ModelState())

    // ------------------------------------------------------- one row per cycle

    @Test
    fun `given a live snapshot when one evaluation runs then exactly one verdict is recorded with the expected fields`() = runBlocking {
        val result = evaluate("BTCUSDT")

        // The fixture really did supply a snapshot; otherwise NO_TRADE would prove
        // nothing about the recording path.
        assertTrue("The fixture must produce a spot price: ${result.spotPrice}", result.spotPrice > 0.0)
        assertEquals("All five horizons must be forecast", 5, result.forecastTargets.size)

        val rows = db.decisionRecordDao().getAllDecisionRecords()
        assertEquals("One evaluation must record exactly one verdict", 1, rows.size)

        val row = rows.single()
        assertEquals("BTCUSDT", row.symbol)
        assertEquals(referenceHorizon, row.referenceHorizon)
        // No checkpoint is mounted here, so the rules must be credited, not the model.
        assertEquals(DecisionSource.DETERMINISTIC.name, row.source)
        assertEquals("The rules carry no calibrated score", 0.0, row.confidence, 0.0)
        assertEquals(
            "The stored verdict must be the one the caller was shown",
            result.decision.state.name,
            row.state
        )
        assertTrue(
            "The state must be one of the six §29 outcomes: ${row.state}",
            row.state in DecisionState.values().map { it.name }
        )
        assertEquals("The router's reason is stored verbatim", result.decision.reason, row.reason)
        assertTrue("A verdict must explain itself", row.reason.isNotBlank())
        assertEquals(DecisionSource.DETERMINISTIC, result.decision.source)
    }

    // ------------------------------------------ the flow the UI actually reads

    @Test
    fun `given a subscribed history flow when an evaluation runs then the new verdict arrives without a re-subscription`() = runBlocking {
        // The UI collects this exact flow. Subscribing once and then writing must be
        // enough: a fresh query afterwards would hide a broken Flow wiring.
        val emissions = Channel<List<DecisionRecordEntity>>(Channel.UNLIMITED)
        val collector = launch {
            repository.getDecisionHistoryFlow("BTCUSDT", limit = 5).collect { emissions.send(it) }
        }

        val initial = withTimeout(10_000) { emissions.receive() }
        assertTrue("The ledger starts empty: $initial", initial.isEmpty())

        evaluate("BTCUSDT")

        val updated = withTimeout(10_000) { emissions.receive() }
        assertEquals("The subscription must emit the new verdict on its own", 1, updated.size)
        assertEquals("BTCUSDT", updated.single().symbol)
        assertEquals(referenceHorizon, updated.single().referenceHorizon)
        collector.cancel()
    }

    // ------------------------------------------------ no cross-symbol leakage

    @Test
    fun `given two assets evaluated in one run when each history is read then neither sees the other's verdict`() = runBlocking {
        evaluate("BTCUSDT")
        evaluate("ETHUSDT")

        val btc = repository.getDecisionHistoryFlow("BTCUSDT", limit = 10).first()
        val eth = repository.getDecisionHistoryFlow("ETHUSDT", limit = 10).first()

        assertEquals(1, btc.size)
        assertEquals(1, eth.size)
        assertTrue("BTC history leaked: $btc", btc.all { it.symbol == "BTCUSDT" })
        assertTrue("ETH history leaked: $eth", eth.all { it.symbol == "ETHUSDT" })
        assertNotEquals(
            "Each asset must get its own record, not a shared one",
            btc.single().id,
            eth.single().id
        )
    }

    // ------------------------------------------------- append, never replace

    @Test
    fun `given an asset already evaluated when it is evaluated again then the newer verdict is appended`() = runBlocking {
        evaluate("BTCUSDT")
        val first = repository.getDecisionHistoryFlow("BTCUSDT", limit = 10).first().single()

        evaluate("BTCUSDT")

        val rows = repository.getDecisionHistoryFlow("BTCUSDT", limit = 10).first()
        assertEquals("A second evaluation appends rather than replacing", 2, rows.size)
        assertNotEquals("The two snapshots must not collide on one id", first.id, rows.first().id)
        assertEquals("The first verdict is still there", first.id, rows.last().id)
        assertTrue(
            "Newest first: ${rows.map { it.issuedTimestamp }}",
            rows.first().issuedTimestamp >= rows.last().issuedTimestamp
        )
        assertEquals(DecisionSource.DETERMINISTIC.name, rows.first().source)
        assertTrue(
            "Every stored state must be a real §29 outcome",
            rows.all { it.state in DecisionState.values().map { state -> state.name } }
        )
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A client whose exchange is JSON literals.
     *
     * Each 1m candle closes 0.1% above its open, so the snapshot has a direction,
     * and the book is quoted either side of spot so depth-driven inputs are real.
     */
    private class FixtureClient(
        private val lastPrice: Double = 60_000.0,
        private val klineCount: Int = 40
    ) : ResilientBinanceClient() {

        override suspend fun getJsonObject(path: String): JSONObject {
            require(path.contains("ticker/24hr")) { "Unexpected object path: $path" }
            return JSONObject(
                """
                {"lastPrice":"$lastPrice","priceChangePercent":"0.85",
                 "highPrice":"60500.00","lowPrice":"59200.00"}
                """.trimIndent()
            )
        }

        override suspend fun getJsonArray(path: String): JSONArray {
            require(path.contains("klines")) { "Unexpected array path: $path" }
            val candles = JSONArray()
            var close = lastPrice * 0.98
            repeat(klineCount) { index ->
                val open = close
                close = open * 1.001
                val openTime = 1_700_000_000_000L + index * 60_000L
                candles.put(
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
            return candles
        }

        override suspend fun fetchDepth(symbol: String, limit: Int): DepthSnapshot = DepthSnapshot(
            bids = (0 until 10).map { DepthTier(price = lastPrice - it * 5.0, quantity = 2.0) },
            asks = (0 until 10).map { DepthTier(price = lastPrice + 5.0 + it * 5.0, quantity = 2.0) }
        )

        override suspend fun fetchAggTrades(symbol: String, limit: Int): List<Double> = emptyList()

        override suspend fun fetchLiquidityHeatmap(
            symbol: String,
            spotPrice: Double
        ): Pair<Double, Double>? = null
    }

    /** Never opens a WebSocket, so no test reaches the exchange. */
    private class OfflineStreamManager : BinanceWebSocketManager() {
        override fun startStreaming(symbol: String) = Unit
    }

    /** No model is mounted in this spec, so the ensemble stays kinematic. */
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
