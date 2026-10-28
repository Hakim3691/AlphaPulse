package com.aistudio.alphapulse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Storage behaviour of the recorded §29 verdicts, specified Given/When/Then.
 *
 * The audit ledger has to survive a restart, so the verdict is written to Room
 * rather than kept in the view state. Two properties matter beyond a plain round
 * trip: re-evaluating one snapshot must replace its row instead of appending a
 * second verdict, and a 24/7 app evaluating every ten seconds must not grow this
 * table without limit.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, §37 Room Database
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DecisionRecordDaoBddSpec {

    private lateinit var db: AppDatabase
    private lateinit var dao: DecisionRecordDao

    private val issued = 1_700_000_000_000L

    private fun record(
        symbol: String = "BTCUSDT",
        issuedTimestamp: Long = issued,
        state: String = "LONG",
        confidence: Double = 0.88,
        source: String = "NEEDLE3"
    ) = DecisionRecordEntity(
        id = "decision-$symbol-$issuedTimestamp",
        symbol = symbol,
        issuedTimestamp = issuedTimestamp,
        state = state,
        confidence = confidence,
        source = source,
        referenceHorizon = "15m",
        reason = "Needle 3: decide_long (confidence 88%) - both forecasts positive, so a long."
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.decisionRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given a recorded verdict when the ledger is read then every field survives the round trip`() = runBlocking {
        val written = record()
        dao.insert(written)

        val stored = dao.getAllDecisionRecords().single()

        assertEquals(written.id, stored.id)
        assertEquals("BTCUSDT", stored.symbol)
        assertEquals(issued, stored.issuedTimestamp)
        assertEquals("LONG", stored.state)
        assertEquals(0.88, stored.confidence, 1e-9)
        assertEquals("NEEDLE3", stored.source)
        assertEquals("15m", stored.referenceHorizon)
        assertEquals(written.reason, stored.reason)
    }

    @Test
    fun `given the same snapshot evaluated twice when recorded then one row remains with the newer verdict`() = runBlocking {
        dao.insert(record(state = "NEUTRAL", confidence = 0.0, source = "DETERMINISTIC"))
        dao.insert(record(state = "STRONG_LONG", confidence = 0.91, source = "NEEDLE3"))

        val stored = dao.getAllDecisionRecords()

        assertEquals("A snapshot carries exactly one verdict", 1, stored.size)
        assertEquals("STRONG_LONG", stored.single().state)
        assertEquals("NEEDLE3", stored.single().source)
    }

    @Test
    fun `given more verdicts than the retention limit when pruned then only the newest are kept`() = runBlocking {
        // A ten-second evaluation cycle means unbounded history is not an option.
        val timestamps = (0 until 5).map { issued + it * 10_000L }
        timestamps.forEach { dao.insert(record(issuedTimestamp = it)) }

        dao.prune("BTCUSDT", keep = 3)

        val kept = dao.getAllDecisionRecords().map { it.issuedTimestamp }
        assertEquals(3, kept.size)
        assertEquals(timestamps.takeLast(3).toSet(), kept.toSet())
        assertTrue(
            "The oldest verdicts must be the ones dropped: $kept",
            !kept.contains(timestamps.first())
        )
    }

    @Test
    fun `given several verdicts for one symbol when its history is read then it is newest first and respects the limit`() = runBlocking {
        (0 until 3).forEach { offset -> dao.insert(record(issuedTimestamp = issued + offset * 10_000L)) }
        dao.insert(record(symbol = "ETHUSDT", issuedTimestamp = issued + 99_000L, state = "SHORT"))

        val history = dao.getDecisionHistoryFlow("BTCUSDT", limit = 2).first()

        assertEquals(2, history.size)
        assertEquals(issued + 20_000L, history.first().issuedTimestamp)
        assertTrue("Another asset's log must not leak in: $history", history.all { it.symbol == "BTCUSDT" })
    }

    @Test
    fun `given two symbols when one is pruned then the other keeps its whole history`() = runBlocking {
        (0 until 3).forEach { offset ->
            dao.insert(record(symbol = "BTCUSDT", issuedTimestamp = issued + offset * 10_000L))
            dao.insert(record(symbol = "ETHUSDT", issuedTimestamp = issued + offset * 10_000L))
        }

        dao.prune("BTCUSDT", keep = 1)

        assertEquals(1, dao.getAllDecisionRecords().count { it.symbol == "BTCUSDT" })
        assertEquals(3, dao.getAllDecisionRecords().count { it.symbol == "ETHUSDT" })
    }

    @Test
    fun `given a ledger of verdicts when it is cleared then the predictions keep their own rows`() = runBlocking {
        dao.insert(record())
        db.predictionDao().insert(
            PredictionEntity(
                id = "BTCUSDT-15m-1",
                symbol = "BTCUSDT",
                horizonId = "15m",
                horizonLabel = "15 Minutes",
                modelType = "Tri-Factor",
                minutes = 15,
                issuedTimestamp = issued,
                targetTimestamp = issued + 900_000L,
                basePrice = 60_000.0,
                predictedPrice = 60_100.0,
                upperBound = 60_500.0,
                lowerBound = 59_500.0,
                muK = 0.0,
                muP = 0.0,
                muAlpha = 0.0,
                settled = false
            )
        )

        dao.clearAll()

        assertTrue(dao.getAllDecisionRecords().isEmpty())
        assertEquals(
            "Clearing the decisions must not delete the forecast ledger",
            1,
            db.predictionDao().getAllPredictions().size
        )
    }
}
