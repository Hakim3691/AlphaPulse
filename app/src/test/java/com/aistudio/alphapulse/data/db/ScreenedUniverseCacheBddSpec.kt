package com.aistudio.alphapulse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.model.FilterBadgeData
import com.aistudio.alphapulse.data.model.ScreenedCoin
import com.aistudio.alphapulse.domain.screener.ScreenedUniverseRevalidation
import com.aistudio.alphapulse.domain.screener.ScreenedUniverseSnapshot
import com.aistudio.alphapulse.domain.screener.UniverseRefreshOutcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-37 — the screened universe survives a failed refresh, remembers its age, and swaps
 * atomically.
 *
 * Why this spec exists
 * --------------------
 * Three separate ways the cache could quietly lie: a `clearAll()` followed by an insert
 * that threw left no universe at all; a stored universe had no age, so a three-day-old
 * screen was presented as current; and a screen whose ticker call failed produced an empty
 * list that was indistinguishable from "nothing qualified today". The policy scenarios
 * below drive the production decision function, and the migration scenario runs the real
 * 10 -> 11 upgrade against a real file.
 *
 * Spec Ref: §37 Room Database, §65, C-37
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenedUniverseCacheBddSpec {

    private val databaseName = "screened-universe-cache.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    // ---------------------------------------------------------------- schema

    @Test
    fun `given the production 10 to 11 migration when read then it maps ten to eleven and creates every state column`() {
        assertEquals(10, AppDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, AppDatabase.MIGRATION_10_11.endVersion)

        val ddl = UniverseCacheSchema.DDL
        assertTrue(
            "The migration must create the state table: $ddl",
            ddl.contains("CREATE TABLE IF NOT EXISTS `${UniverseCacheSchema.TABLE}`")
        )
        UniverseCacheSchema.COLUMNS.forEach { column ->
            assertTrue("The migration must declare `$column`: $ddl", ddl.contains("`$column`"))
        }
        assertTrue(
            "The state is a single row, so the table needs its fixed key: $ddl",
            ddl.contains("PRIMARY KEY(`id`)")
        )
    }

    @Test
    fun `given the state entity when reflected then it declares exactly the columns the migration creates`() {
        val fields = ScreenedUniverseStateEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            // The entity's own companion holds the row key; it is not a column.
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            "The entity and the migration must agree, or Room rejects the schema on device",
            UniverseCacheSchema.COLUMNS.toSet(),
            fields
        )
    }

    @Test
    fun `given a version ten database with a cached universe when opened at the current version then the table appears and the coins survive`() = runBlocking {
        writeVersion10Database()

        val upgraded = openAtCurrentVersion()
        val raw = upgraded.openHelper.writableDatabase

        assertEquals(
            "Room must record the upgraded version",
            11,
            queryInt(raw, "PRAGMA user_version")
        )
        assertEquals(
            "The upgrade must not cost the user their universe",
            listOf("BTCUSDT", "PAXGUSDT"),
            upgraded.screenedCoinDao().getAllScreenedCoins().map { it.symbol }
        )
        assertNull(
            "A cache written before the state table has no age; it must read as unknown, not fresh",
            upgraded.screenedUniverseStateDao().get()
        )
        upgraded.close()
    }

    // ------------------------------------------------------------- persistence

    @Test
    fun `given a persisted universe when read back then its age and coverage survive a reopen`() = runBlocking {
        val first = openAtCurrentVersion()
        first.screenedCoinDao().replaceAll(listOf(entity("BTCUSDT", rank = 1)))
        first.screenedUniverseStateDao().upsert(
            ScreenedUniverseStateEntity(
                fetchedAtMillis = 1_700_000_000_000L,
                screenedPairs = 312,
                admittedPairs = 1
            )
        )
        first.close()

        val reopened = openAtCurrentVersion()
        val state = reopened.screenedUniverseStateDao().get()

        assertEquals(1_700_000_000_000L, state?.fetchedAtMillis)
        assertEquals(312, state?.screenedPairs)
        assertEquals(1, state?.admittedPairs)
        assertEquals(1, reopened.screenedCoinDao().getAllScreenedCoins().size)
        reopened.close()
    }

    @Test
    fun `given an existing universe when a new one is swapped in then the old rows are replaced rather than merged`() = runBlocking {
        val database = openAtCurrentVersion()

        database.screenedCoinDao().replaceAll(
            listOf(entity("BTCUSDT", rank = 1), entity("PAXGUSDT", rank = 2))
        )
        database.screenedCoinDao().replaceAll(listOf(entity("ETHUSDT", rank = 1)))

        assertEquals(
            "A swapped-in universe is the whole universe, so a coin the new run rejected is gone",
            listOf("ETHUSDT"),
            database.screenedCoinDao().getAllScreenedCoins().map { it.symbol }
        )
        database.close()
    }

    @Test
    fun `given the swap when the source is read then it runs in one transaction and the repository no longer clears first`() {
        val daos = repoFile("app/src/main/java/com/aistudio/alphapulse/data/db/Daos.kt").readText()
        assertTrue(
            "The swap must be a single @Transaction, or a failed insert leaves an empty cache",
            Regex("""@Transaction\s+suspend fun replaceAll""").containsMatchIn(daos)
        )

        val repository = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/data/repository/AlphaPulseRepository.kt"
        ).readText()
        assertFalse(
            "The screening path must not clear the cache before its replacement is ready",
            repository.contains("screenedCoinDao().clearAll()")
        )
        assertTrue(
            "The coin rows and the state that describes them must be written together",
            Regex("""withTransaction\s*\{[\s\S]{0,200}replaceAll\(""").containsMatchIn(repository)
        )
    }

    // ----------------------------------------------------------------- policy

    @Test
    fun `given a cache inside the reuse window when asked then it is fresh, and at the boundary it still is`() {
        assertTrue(ScreenedUniverseRevalidation.isFresh(0L))
        assertTrue(
            ScreenedUniverseRevalidation.isFresh(ScreenedUniverseRevalidation.TTL_MILLIS)
        )
        assertFalse(
            ScreenedUniverseRevalidation.isFresh(ScreenedUniverseRevalidation.TTL_MILLIS + 1L)
        )
    }

    @Test
    fun `given a snapshot when its age is rendered then it is coarse but never negative`() {
        val now = 1_700_000_000_000L
        val snapshot = snapshot(fetchedAtMillis = now - 4L * 60_000L)

        assertEquals("4m ago", snapshot.ageLabel(now))
        assertEquals("2h ago", snapshot.copy(fetchedAtMillis = now - 2L * 3_600_000L).ageLabel(now))
        assertEquals("just now", snapshot.copy(fetchedAtMillis = now - 30_000L).ageLabel(now))
        assertEquals(
            "A clock that steps backwards must not produce a negative age",
            0L,
            snapshot.copy(fetchedAtMillis = now + 60_000L).ageMillis(now)
        )
        assertEquals(1_700_000_000_000L - 4L * 60_000L, snapshot.fetchedAtMillis)
        assertTrue(snapshot.isFresh(now))
    }

    @Test
    fun `given a run that admitted coins when revalidated then it replaces the cache`() {
        val previous = snapshot(fetchedAtMillis = 1_000L, coins = listOf("BTCUSDT"))
        val run = snapshot(fetchedAtMillis = 2_000L, coins = listOf("ETHUSDT"), screenedPairs = 300)

        val refresh = ScreenedUniverseRevalidation.decide(previous, run)

        assertEquals(UniverseRefreshOutcome.REFRESHED, refresh.outcome)
        assertEquals(2_000L, refresh.snapshot?.fetchedAtMillis)
        assertEquals(listOf("ETHUSDT"), refresh.snapshot?.coins?.map { it.symbol })
    }

    @Test
    fun `given a run whose ticker never answered when revalidated then the cached universe is kept and named as cached`() {
        val previous = snapshot(fetchedAtMillis = 1_000L, coins = listOf("BTCUSDT"))
        val failedRun = snapshot(fetchedAtMillis = 2_000L, coins = emptyList(), screenedPairs = 0)

        val refresh = ScreenedUniverseRevalidation.decide(previous, failedRun)

        assertEquals(UniverseRefreshOutcome.KEPT_PREVIOUS, refresh.outcome)
        assertEquals(
            "The cached universe's own timestamp is what the UI reports, not the failed run's",
            1_000L,
            refresh.snapshot?.fetchedAtMillis
        )
        assertEquals(listOf("BTCUSDT"), refresh.snapshot?.coins?.map { it.symbol })
    }

    @Test
    fun `given a run that screened the venue and admitted nothing when revalidated then the cached universe is kept too`() {
        val previous = snapshot(fetchedAtMillis = 1_000L, coins = listOf("BTCUSDT"))
        val admittedNothing = snapshot(fetchedAtMillis = 2_000L, coins = emptyList(), screenedPairs = 298)

        assertEquals(
            UniverseRefreshOutcome.KEPT_PREVIOUS,
            ScreenedUniverseRevalidation.decide(previous, admittedNothing).outcome
        )
    }

    @Test
    fun `given a failed run and no cache when revalidated then there is no universe to fabricate`() {
        val refresh = ScreenedUniverseRevalidation.decide(
            null,
            snapshot(fetchedAtMillis = 2_000L, coins = emptyList(), screenedPairs = 0)
        )

        assertEquals(UniverseRefreshOutcome.NO_DATA, refresh.outcome)
        assertNull(refresh.snapshot)
    }

    // ---------------------------------------------------------------- helpers

    private fun snapshot(
        fetchedAtMillis: Long,
        coins: List<String> = listOf("BTCUSDT"),
        screenedPairs: Int = 300
    ) = ScreenedUniverseSnapshot(
        coins = coins.map { ScreenedCoin(
            symbol = it,
            lastPrice = 1.0,
            highPrice = 1.0,
            lowPrice = 1.0,
            openPrice = 1.0,
            volume = 1.0,
            realTakerBuyRatio = 0.5,
            displacement = 0.5,
            exactOI = 1.0,
            volToOiRatio = 1.0,
            priceChangePercent = 0.0,
            calmarRatio = 0.0,
            sharpeRatio = 0.0,
            annualizedVol = 0.0,
            maxDrawdown = 0.0,
            daysPassedAll = 0,
            passedTodayAll = true,
            passed10DRule = false,
            filter1 = FilterBadgeData(true, "1M Vol"),
            filter2 = FilterBadgeData(true, "50.0%"),
            filter3 = FilterBadgeData(true, "1.00x"),
            filter4 = FilterBadgeData(true, "40.0% Ext")
        ) },
        fetchedAtMillis = fetchedAtMillis,
        screenedPairs = screenedPairs,
        admittedPairs = coins.size
    )

    private fun entity(symbol: String, rank: Int) = ScreenedCoinEntity(
        symbol = symbol,
        lastPrice = 64_000.0,
        highPrice = 65_000.0,
        lowPrice = 63_000.0,
        openPrice = 63_500.0,
        volume = 4_200_000_000.0,
        realTakerBuyRatio = 54.0,
        displacement = 0.5,
        exactOI = 1_200_000_000.0,
        volToOiRatio = 3.5,
        priceChangePercent = 1.2,
        calmarRatio = 2.4,
        sharpeRatio = 1.8,
        annualizedVol = 0.45,
        maxDrawdown = 0.22,
        daysPassedAll = 10,
        passedTodayAll = true,
        passed10DRule = false,
        filter1Passed = true,
        filter1Val = "4200M Vol",
        filter2Passed = true,
        filter2Val = "54.0%",
        filter3Passed = true,
        filter3Val = "3.50x",
        filter4Passed = true,
        filter4Val = "50.0% Ext",
        rank = rank
    )

    /** A version-10 file: the current schema minus the table 11 adds. */
    private suspend fun writeVersion10Database() {
        val current = openAtCurrentVersion()
        val raw = current.openHelper.writableDatabase

        current.screenedCoinDao().insertAll(
            listOf(entity("BTCUSDT", rank = 1), entity("PAXGUSDT", rank = 2))
        )
        raw.execSQL("DROP TABLE `${UniverseCacheSchema.TABLE}`")
        raw.execSQL("PRAGMA user_version = 10")

        assertEquals(10, queryInt(raw, "PRAGMA user_version"))
        current.close()
    }

    private fun openAtCurrentVersion(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            // The fixture files are all version 10 or the current one, so this is the
            // only production migration they can walk. Listing it is what makes the
            // upgrade test run the app's own migration object.
            .addMigrations(AppDatabase.MIGRATION_10_11)
            .build()

    private fun queryInt(raw: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        raw.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }

    /** Locates a repository file from whichever directory tests run in. */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
