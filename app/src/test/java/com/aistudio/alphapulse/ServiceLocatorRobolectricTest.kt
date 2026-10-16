package com.aistudio.alphapulse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.network.BinanceWebSocketManager
import com.aistudio.alphapulse.data.network.ResilientBinanceClient
import com.aistudio.alphapulse.data.repository.AlphaPulseRepository
import com.aistudio.alphapulse.di.ServiceLocator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ServiceLocator (Phase 0 DI seam) behaviour under Robolectric: lazy
 * production graph is stable, test override takes precedence, and reset
 * restores production wiring for subsequent components.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServiceLocatorRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.reset()
        ServiceLocator.clearProduction()
    }

    @After
    fun tearDown() {
        val graph = try {
            ServiceLocator.graph(context)
        } catch (e: Exception) {
            null
        }
        graph?.database?.close()
        ServiceLocator.reset()
        ServiceLocator.clearProduction()
    }

    @Test
    fun `app name resolves from resources`() {
        assertEquals("AlphaPulse", context.getString(R.string.app_name))
    }

    @Test
    fun `production graph is built lazily and shared`() {
        val graphA = ServiceLocator.graph(context)
        val graphB = ServiceLocator.graph(context)

        assertSame(graphA, graphB)
        assertSame(graphA.repository, graphB.repository)
        assertSame(graphA.database, graphB.database)
        assertSame(graphA.client, graphB.client)
        assertSame(graphA.wsManager, graphB.wsManager)
    }

    @Test
    fun `production graph wires a real Room database with the ledger tables`() {
        val graph = ServiceLocator.graph(context)
        val db = graph.database.openHelper.writableDatabase
        val registeredTables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            val names = mutableSetOf<String>()
            while (c.moveToNext()) names.add(c.getString(0))
            names
        }
        assertTrue("predictions table missing", "predictions" in registeredTables)
        assertTrue("screened_coins table missing", "screened_coins" in registeredTables)
    }

    @Test
    fun `test override takes precedence over production wiring`() {
        val fakeDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val fakeRepo = AlphaPulseRepository(
            client = ResilientBinanceClient(),
            database = fakeDb,
            cacheDir = null,
            wsManager = BinanceWebSocketManager()
        )
        val fakeGraph = ServiceLocator.Graph(
            client = ResilientBinanceClient(),
            database = fakeDb,
            wsManager = BinanceWebSocketManager(),
            repository = fakeRepo,
            cacheDir = null
        )

        ServiceLocator.overrideForTesting(fakeGraph)

        val resolved = ServiceLocator.graph(context)
        assertSame(fakeGraph, resolved)
        assertSame(fakeRepo, resolved.repository)
        assertSame(fakeDb, resolved.database)

        fakeDb.close()
    }

    @Test
    fun `reset drops the test override`() {
        val fakeGraph = ServiceLocator.Graph(
            client = ResilientBinanceClient(),
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries().build(),
            wsManager = BinanceWebSocketManager(),
            repository = AlphaPulseRepository(
                ResilientBinanceClient(),
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                    .allowMainThreadQueries().build(),
                null,
                BinanceWebSocketManager()
            ),
            cacheDir = null
        )
        ServiceLocator.overrideForTesting(fakeGraph)
        assertSame(fakeGraph, ServiceLocator.graph(context))

        ServiceLocator.reset()

        // After reset the resolved graph is production again (different instance).
        val prod = ServiceLocator.graph(context)
        assertTrue(prod !== fakeGraph)
    }

}
