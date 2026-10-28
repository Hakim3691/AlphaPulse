package com.aistudio.alphapulse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * The Room 5 -> 6 upgrade, specified Given/When/Then (spec §20, §37).
 *
 * Why a real file instead of `<inMemoryDatabaseBuilder>`
 * ------------------------------------------------------
 * An in-memory database is always created at the current version, so it can
 * never run a migration. This spec writes a version-5 file, lowers its
 * `user_version` to 5, and reopens it with the production database and the
 * production [AppDatabase.MIGRATION_5_6] - the same object `getDatabase` adds on
 * a device - so the upgrade is executed, not simulated.
 *
 * Why the version-5 file is built from the current schema minus the new table:
 * the app ships `exportSchema = false`, so there is no exported 5 schema to load.
 * Version 5 and version 6 differ by exactly one table (the calibration weights),
 * so removing that table from a current-schema file reproduces the version-5
 * schema byte for byte as far as Room's validation is concerned. Room then
 * validates the *whole* schema after the migration, which is what makes this a
 * real upgrade test rather than a SQL smoke test.
 *
 * No migration-testing dependency is added: `room-testing` is not in the project,
 * and this uses only Room, Robolectric and the Framework SQLite database already
 * on the test classpath.
 *
 * Spec Ref: §20 Adaptive Ensemble Learning, §37 Room Database,
 * §52 Testing Requirements
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnsembleWeightsMigrationBddSpec {

    private val databaseName = "ensemble-weights-migration.db"
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

    @Test
    fun `given the migration object when read then it maps version 5 to version 6`() {
        assertEquals(5, AppDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, AppDatabase.MIGRATION_5_6.endVersion)
    }

    @Test
    fun `given a version 5 database when opened at version 6 then the weights table is created and existing rows survive`() = runBlocking {
        writeVersion5Database()

        val upgraded = openVersion6(addMigration = true)
        val raw = upgraded.openHelper.writableDatabase

        assertEquals(
            "Room must record the upgraded version",
            6,
            queryInt(raw, "PRAGMA user_version")
        )
        assertEquals(
            "The seeded verdict must survive the upgrade",
            1,
            upgraded.decisionRecordDao().getAllDecisionRecords().size
        )

        val columns = tableColumns(raw, "ensemble_weights")
        assertEquals(
            "The migration must create every column EnsembleWeightsEntity maps",
            listOf(
                "compositeKey", "symbol", "horizonId",
                "weightK", "weightP", "weightAlpha", "weight4", "weight5",
                "lastUpdated", "modelVersion"
            ).toSet(),
            columns.keys
        )

        // The primary key is what makes a recalibration replace rather than append.
        assertEquals("TEXT", columns.getValue("compositeKey").first)
        assertEquals("compositeKey must be the primary key", 1, columns.getValue("compositeKey").second)
        for (notNull in columns.keys) {
            assertTrue("$notNull must be NOT NULL; got ${columns[notNull]}", columns.getValue(notNull).third)
        }

        upgraded.close()
    }

    @Test
    fun `given an upgraded database when calibration weights are written then they survive a reopen`() = runBlocking {
        writeVersion5Database()

        val upgraded = openVersion6(addMigration = true)
        val expected = EnsembleWeightsEntity(
            compositeKey = "BTCUSDT#15m",
            symbol = "BTCUSDT",
            horizonId = "15m",
            weightK = 0.24,
            weightP = 0.28,
            weightAlpha = 0.28,
            weight4 = 0.12,
            weight5 = 0.08,
            lastUpdated = 1_700_000_000_000L,
            modelVersion = "chronos,timesfm"
        )
        upgraded.ensembleWeightsDao().upsert(expected)
        assertEquals(1, upgraded.ensembleWeightsDao().getForSymbol("BTCUSDT").size)
        upgraded.close()

        val reopened = openVersion6(addMigration = true)
        val rows = reopened.ensembleWeightsDao().getForSymbol("BTCUSDT")

        assertEquals("The calibration must still be there after a restart", 1, rows.size)
        val row = rows.single()
        assertEquals("BTCUSDT#15m", row.compositeKey)
        assertEquals(0.24, row.weightK, 1e-9)
        assertEquals(0.28, row.weightP, 1e-9)
        assertEquals(0.28, row.weightAlpha, 1e-9)
        assertEquals(0.12, row.weight4, 1e-9)
        assertEquals(0.08, row.weight5, 1e-9)
        assertEquals("chronos,timesfm", row.modelVersion)
        assertEquals(1_700_000_000_000L, row.lastUpdated)
        reopened.close()
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Create the file at the current version, then remove the one table version 6
     * adds and lower `user_version` to 5, which is the schema version 5 shipped.
     */
    private fun writeVersion5Database() {
        val current = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val raw = current.openHelper.writableDatabase

        raw.execSQL(
            "INSERT INTO decision_records " +
                "(id, symbol, issuedTimestamp, state, confidence, source, referenceHorizon, reason) " +
                "VALUES ('seed-1', 'BTCUSDT', 1700000000000, 'LONG', 0.5, 'DETERMINISTIC', '15m', 'seeded')"
        )
        raw.execSQL("DROP TABLE `ensemble_weights`")
        raw.execSQL("PRAGMA user_version = 5")

        assertEquals(5, queryInt(raw, "PRAGMA user_version"))
        assertTrue(
            "The version-5 fixture must not already contain the version-6 table",
            tableColumns(raw, "ensemble_weights").isEmpty()
        )
        current.close()
    }

    /**
     * Open at version 6. With [addMigration] false the production migration is
     * withheld, which is how the pre-upgrade assertion proves the file really is
     * version 5 rather than a database that silently appeared at 6.
     */
    private fun openVersion6(addMigration: Boolean): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries()
        if (addMigration) builder.addMigrations(AppDatabase.MIGRATION_5_6)
        return builder.build()
    }

    /**
     * `name -> (type, primary-key position, not-null)` for one table.
     *
     * Empty when the table does not exist, which is how the version-5 fixture is
     * asserted to lack it before the migration runs.
     */
    private fun tableColumns(
        raw: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): Map<String, Triple<String, Int, Boolean>> {
        val columns = linkedMapOf<String, Triple<String, Int, Boolean>>()
        raw.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val primaryIndex = cursor.getColumnIndexOrThrow("pk")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIndex)] = Triple(
                    cursor.getString(typeIndex),
                    cursor.getInt(primaryIndex),
                    cursor.getInt(notNullIndex) == 1
                )
            }
        }
        return columns
    }

    private fun queryInt(
        raw: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String
    ): Int = raw.query(sql).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else -1
    }
}
