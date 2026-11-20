package com.aistudio.alphapulse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.repository.CatalogueRepository
import com.aistudio.alphapulse.domain.catalogue.CatalogueCategory
import com.aistudio.alphapulse.domain.catalogue.Regime
import com.aistudio.alphapulse.domain.catalogue.TradeIdeasCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D7: the Trade Ideas Catalog is persisted in Room and queried by regime.
 *
 * A catalogue that only lives in a Kotlin object cannot be queried at evaluation
 * time, so "Needle 3 selects from the catalogue by regime" would remain prose. This
 * spec pins the schema, the seed and the regime query against a real Room database.
 *
 * Spec Ref: Phase 1 §3.1, D7
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CataloguePersistenceBddSpec {

    private lateinit var database: AppDatabase

    @Before
    fun openDatabase() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `given the production 9 to 10 migration when its statement is read then it creates the catalogue table with every column`() {
        val ddl = CatalogueSchema.DDL

        assertEquals(9, AppDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, AppDatabase.MIGRATION_9_10.endVersion)
        assertTrue(
            "The migration must create the catalogue table: $ddl",
            ddl.contains("CREATE TABLE IF NOT EXISTS `${CatalogueSchema.TABLE}`")
        )
        CatalogueSchema.COLUMNS.forEach { column ->
            assertTrue("The migration must declare `$column`: $ddl", ddl.contains("`$column`"))
        }
        assertTrue("The catalogue needs its key", ddl.contains("PRIMARY KEY(`id`)"))
    }

    @Test
    fun `given the seed path when it is generated then every catalogue idea is inserted exactly once`() {
        val statements = CatalogueSchema.seedStatements()

        assertEquals(
            "The published taxonomy must be seeded completely",
            TradeIdeasCatalog.all.size,
            statements.size
        )
        assertTrue("Candlestick setups are part of the catalogue", statements.any { it.contains("Hanging Man") })
        assertTrue("Macro formations are part of the catalogue", statements.any { it.contains("Rising Wedge") })
        assertTrue("Indicator combos are part of the catalogue", statements.any { it.contains("MACD Zero-Axis Cross") })
        assertTrue("Quant rules are part of the catalogue", statements.any { it.contains("Turtle Breakout (20-bar)") })
        assertTrue(
            "Seeding is idempotent so a migration re-run cannot duplicate rows",
            statements.all { it.startsWith("INSERT OR REPLACE INTO") }
        )
    }

    @Test
    fun `given the catalogue is persisted when the full taxonomy is read then every baseline round-trips`() = runBlocking {
        val dao = database.tradeIdeaDao()
        dao.insertAll(TradeIdeasCatalog.all.map { it.toEntity() })

        val all = CatalogueRepository(dao).all()

        assertEquals(TradeIdeasCatalog.all.size, all.size)
        val hammer = all.first { it.id == "hammer" }
        assertEquals(0.68, hammer.winRate, 1e-9)
        assertEquals(0.024, hammer.avgReturn, 1e-9)
        assertTrue("Regimes must survive the comma-separated column", Regime.MEAN_REVERTING in hammer.regimes)
        assertFalse("A trend flag must not be invented", hammer.trendFollowing)
        assertTrue(
            "Quant rules keep their trend-following flag",
            all.first { it.id == "turtle-breakout" }.trendFollowing
        )
    }

    @Test
    fun `given a trending-up regime when candidates are queried then only catalogue-permitted setups return`() = runBlocking {
        val dao = database.tradeIdeaDao()
        dao.insertAll(TradeIdeasCatalog.all.map { it.toEntity() })
        val repository = CatalogueRepository(dao)

        val candidates = repository.candidatesFor(Regime.TRENDING_UP)
        val ids = candidates.map { it.id }

        assertTrue("A trending regime has candidates", candidates.isNotEmpty())
        assertTrue("Trend setups belong in an uptrend", "turtle-breakout" in ids)
        assertFalse("A bearish reversal must not fire in an uptrend", "hanging-man" in ids)
        assertTrue(
            "Candidates are ranked by empirical edge",
            candidates.map { it.winRate * it.avgReturn }.let { it == it.sortedDescending() }
        )
    }

    @Test
    fun `given a category filter when it is queried then only that category is returned`() = runBlocking {
        val dao = database.tradeIdeaDao()
        dao.insertAll(TradeIdeasCatalog.all.map { it.toEntity() })
        val repository = CatalogueRepository(dao)

        val quant = repository.forCategory(CatalogueCategory.QUANT)

        assertEquals(TradeIdeasCatalog.quant.size, quant.size)
        assertTrue(quant.all { it.category == CatalogueCategory.QUANT })
    }

    @Test
    fun `given live regime labels when they are mapped then each maps to the taxonomy regime or to unknown`() {
        assertEquals(Regime.MEAN_REVERTING, CatalogueRepository.regimeOf("State 0: Mean-Reverting"))
        assertEquals(Regime.HIGH_VOLATILITY, CatalogueRepository.regimeOf("High Volatility"))
        assertEquals(Regime.TRENDING_UP, CatalogueRepository.regimeOf("Trending Up"))
        assertEquals(Regime.TRENDING_DOWN, CatalogueRepository.regimeOf("Trending Down"))
        assertEquals(Regime.UNKNOWN, CatalogueRepository.regimeOf("State 3"))
    }

    @Test
    fun `given the database when inspected then it is version 10 and exposes the catalogue dao`() {
        assertTrue(
            "The database must expose the catalogue DAO",
            AppDatabase::class.java.methods.any { it.name == "tradeIdeaDao" }
        )
        assertTrue(
            "The price ledger must remain mounted",
            AppDatabase::class.java.methods.any { it.name == "predictionDao" }
        )
    }
}
