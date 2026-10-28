package com.aistudio.alphapulse.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.network.BinanceWebSocketManager
import com.aistudio.alphapulse.data.network.ResilientBinanceClient
import com.aistudio.alphapulse.domain.ensemble.CalibrationHorizons
import com.aistudio.alphapulse.domain.ensemble.CalibrationPlanSource
import com.aistudio.alphapulse.domain.ml.TsfmInput
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecast
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecaster
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persisted half of the 30-day calibration, specified Given/When/Then.
 *
 * [CoinCalibrationBddSpec] proves the arithmetic and the loop; this spec proves
 * the producer writes what it computed into real Room storage: one row per asset
 * and horizon, keyed so a recalibration replaces instead of appending, and
 * normalised so `btcusdt` and `BTCUSDT` can never become two different assets.
 *
 * Exchange history is JSON literals and the forecast is a deterministic
 * stand-in, so no network and no ONNX session is involved. Needle 3 is not
 * mounted here, which is exactly the fallback case: the deterministic plan must
 * still calibrate every horizon.
 *
 * Spec Ref: §20 Adaptive Ensemble Learning, §30 Needle / Agent Orchestration,
 * §37 Room Database, §52 Testing Requirements
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoinCalibrationPersistenceBddSpec {

    private lateinit var db: AppDatabase
    private lateinit var repository: AlphaPulseRepository

    private val bothModels = listOf("chronos", "timesfm")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AlphaPulseRepository(
            client = FixtureKlineClient(),
            database = db,
            wsManager = OfflineCalibrationStreams(),
            tsfmForecaster = DriftingCalibrationForecaster()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- one run

    @Test
    fun `given two mounted models when a coin is calibrated then one weighted row per horizon is persisted`() = runBlocking {
        val report = repository.calibrateCoin("btcusdt", bothModels, windowDays = 30)

        assertNotNull("The fixture supplies history, so calibration must run", report)
        val calibration = report!!
        assertEquals("The symbol is normalised before it is read or written", "BTCUSDT", calibration.symbol)
        assertEquals(CalibrationPlanSource.DETERMINISTIC, calibration.dispatchedBy)
        assertEquals(
            CalibrationHorizons.ALL.map { it.id },
            calibration.horizons.map { it.horizonId }
        )

        val rows = db.ensembleWeightsDao().getForSymbol("BTCUSDT")
        assertEquals("One row per horizon, no more", CalibrationHorizons.ALL.size, rows.size)

        for (row in rows) {
            assertEquals("BTCUSDT", row.symbol)
            assertEquals("${row.symbol}#${row.horizonId}", row.compositeKey)
            assertEquals(
                "ΣW must stay 1.000 for ${row.horizonId}",
                1.0,
                row.weightK + row.weightP + row.weightAlpha + row.weight4 + row.weight5,
                1e-9
            )
            assertEquals(
                "Both mounted models keep the mount budget at ${row.horizonId}",
                0.20,
                row.weight4 + row.weight5,
                1e-9
            )
            assertTrue(
                "Chronos tracked the fixture's drift better on ${row.horizonId} " +
                    "(w4=${row.weight4}, w5=${row.weight5})",
                row.weight4 > row.weight5
            )
            assertTrue("The row records which models were scored", row.modelVersion.isNotBlank())
        }
    }

    // ------------------------------------------------------- replace, not grow

    @Test
    fun `given an existing calibration when the coin is calibrated again then the rows are replaced not duplicated`() = runBlocking {
        repository.calibrateCoin("BTCUSDT", bothModels)
        val first = db.ensembleWeightsDao().getForSymbol("BTCUSDT")

        repository.calibrateCoin("BTCUSDT", bothModels)
        val second = db.ensembleWeightsDao().getForSymbol("BTCUSDT")

        assertEquals(first.size, second.size)
        assertEquals(CalibrationHorizons.ALL.size, second.size)
        assertEquals(
            "The same asset and horizon must occupy the same key",
            first.map { it.compositeKey }.toSet(),
            second.map { it.compositeKey }.toSet()
        )
        assertTrue(
            "A recalibration must be the newest write",
            second.all { row ->
                val previous = first.first { it.compositeKey == row.compositeKey }
                row.lastUpdated >= previous.lastUpdated
            }
        )
    }

    // ------------------------------------------------- mixed provenance, apart

    @Test
    fun `given two assets calibrated with different mounted models when read back then neither borrows the other's weights`() = runBlocking {
        repository.calibrateCoin("BTCUSDT", bothModels)
        repository.calibrateCoin("ETHUSDT", listOf("chronos"))

        val btc = db.ensembleWeightsDao().getForSymbol("BTCUSDT")
        val eth = db.ensembleWeightsDao().getForSymbol("ETHUSDT")

        assertEquals(CalibrationHorizons.ALL.size, btc.size)
        assertEquals(CalibrationHorizons.ALL.size, eth.size)
        assertTrue("BTC's rows are BTC's", btc.all { it.symbol == "BTCUSDT" })
        assertTrue("ETH's rows are ETH's", eth.all { it.symbol == "ETHUSDT" })
        assertTrue("BTC mounted both members", btc.all { it.weight5 > 0.0 })
        assertTrue(
            "TimesFM was never mounted for ETH, so its share must stay zero",
            eth.all { it.weight5 == 0.0 && it.weight4 > 0.0 }
        )
    }

    @Test
    fun `given no mounted forecasting model when a coin is calibrated then nothing is written`() = runBlocking {
        val report = repository.calibrateCoin("BTCUSDT", emptyList())

        assertEquals(null, report)
        assertTrue(db.ensembleWeightsDao().getForSymbol("BTCUSDT").isEmpty())
    }

    // ----------------------------------------------------------------- helpers

    /**
     * A client whose exchange is JSON literals.
     *
     * Every interval returns 240 rising candles, so the fixture has a real trend
     * for the rolling evaluation to score.
     */
    private class FixtureKlineClient : ResilientBinanceClient() {

        override suspend fun getJsonArray(path: String): JSONArray {
            require(path.contains("klines")) { "Unexpected array path: $path" }
            val interval = path.substringAfter("interval=", "1m").substringBefore("&")
            val stepMs = when (interval) {
                "5m" -> 5 * 60_000L
                "15m" -> 15 * 60_000L
                "30m" -> 30 * 60_000L
                "1h" -> 60 * 60_000L
                else -> 60_000L
            }
            val count = 240
            val start = System.currentTimeMillis() - count * stepMs
            var price = 100.0
            val candles = JSONArray()
            repeat(count) { index ->
                price *= 1.001
                val openTime = start + index * stepMs
                candles.put(
                    JSONArray().apply {
                        put(openTime)
                        put(price)
                        put(price * 1.0002)
                        put(price * 0.9998)
                        put(price)
                        put(10.0)
                        put(openTime + stepMs - 1)
                        put(price * 10.0)
                        put(120)
                        put(5.0)
                        put(price * 5.0)
                        put(0.0)
                    }
                )
            }
            return candles
        }

        override suspend fun getJsonObject(path: String): JSONObject =
            JSONObject("""{"lastPrice":"100.0","priceChangePercent":"0.1","highPrice":"101.0","lowPrice":"99.0"}""")
    }

    /** Never opens a WebSocket; calibration must not depend on one. */
    private class OfflineCalibrationStreams : BinanceWebSocketManager() {
        override fun startStreaming(symbol: String) = Unit
    }

    /** Chronos follows the fixture's 0.1% drift; TimesFM is deliberately far off. */
    private class DriftingCalibrationForecaster : TsfmForecaster {

        override suspend fun syncMountedModels(modelIds: List<String>) = Unit

        override suspend fun forecast(
            modelId: String,
            tfmInput: TsfmInput,
            closes: DoubleArray,
            steps: Int
        ): TsfmForecast? {
            val last = closes.lastOrNull() ?: return null
            val predicted = last * if (modelId == "chronos") 1.0012 else 0.995
            return TsfmForecast(
                modelId = modelId,
                median = doubleArrayOf(predicted),
                lower = doubleArrayOf(predicted * 0.99),
                upper = doubleArrayOf(predicted * 1.01)
            )
        }
    }
}
