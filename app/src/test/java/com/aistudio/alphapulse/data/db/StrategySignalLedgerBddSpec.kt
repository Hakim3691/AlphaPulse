package com.aistudio.alphapulse.data.db

import com.aistudio.alphapulse.domain.strategy.SignalDirection
import com.aistudio.alphapulse.domain.strategy.StrategySignal
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §74 / Contract C-56 — the strategy signal ledger's record and its 11 -> 12 migration.
 *
 * Why this exists
 * ---------------
 * The nine quantitative models were executable but their output lived only inside one
 * evaluation: the board painted the last cycle's call and the previous one was gone, so
 * the §74 library could not be audited or graded after the fact. This ledger keeps one
 * row per strategy per bar, in its own table so the price ledger's schema is untouched.
 *
 * These scenarios pin the record's fields, the migration, the bar-keyed identity that
 * stops a ten-second cycle inflating the table, and the read-back onto the domain shape.
 *
 * Spec Ref: §74, C-56
 */
class StrategySignalLedgerBddSpec {

    @Test
    fun `given the schema when read then the twelve recorded fields are present in contract order`() {
        assertEquals(
            listOf(
                "asset",
                "strategyId",
                "strategyName",
                "direction",
                "entry",
                "exit",
                "invalidation",
                "distanceAtr",
                "zoneTouches",
                "detail",
                "barOpenTime",
                "issuedTimestamp"
            ),
            StrategySignalSchema.LEDGER_FIELDS
        )
    }

    @Test
    fun `given the production eleven to twelve migration when its statement is read then it creates the table with every column and a primary key`() {
        val ddl = StrategySignalSchema.DDL

        assertTrue(
            "The migration must create the ledger table: $ddl",
            ddl.contains("CREATE TABLE IF NOT EXISTS `${StrategySignalSchema.TABLE}`")
        )
        StrategySignalSchema.LEDGER_FIELDS.forEach { field ->
            assertTrue("The migration must declare the `$field` column: $ddl", ddl.contains("`$field`"))
        }
        assertTrue("The row needs its key", ddl.contains("PRIMARY KEY(`${StrategySignalSchema.KEY_COLUMN}`)"))
        assertTrue(
            "The levels are optional: a rule that is not active defines none",
            ddl.contains("`entry` REAL, ") && ddl.contains("`invalidation` REAL, ")
        )
    }

    @Test
    fun `given the database when its version is read then the strategy ledger table is part of schema twelve`() {
        val database = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/data/db/AppDatabase.kt"
        ).readText()

        assertTrue(
            "The entity must be registered, or the table is never created",
            database.contains("StrategySignalLedgerEntity::class")
        )
        assertTrue("The schema must be version 12", database.contains("version = 12,"))
        assertTrue(
            "The 11 -> 12 migration must be registered, not only declared",
            database.contains("MIGRATION_11_12") && database.contains("MIGRATION_10_11, MIGRATION_11_12")
        )
        assertTrue(
            "The DAO must be reachable from the database",
            database.contains("strategySignalLedgerDao()")
        )
    }

    @Test
    fun `given two strategies evaluated on one bar when recorded then each gets its own row on that bar`() {
        val bar = 1_700_000_000_000L
        val turtle = signal("turtle-breakout", "Turtle Breakout", SignalDirection.BULLISH)
        val connors = signal("connors-rsi", "Connors RSI", SignalDirection.BEARISH)

        val rows = listOf(turtle, connors).map { it.toLedgerRow("BTCUSDT", bar, issuedTimestamp = bar) }

        assertEquals(2, rows.size)
        assertEquals("BTCUSDT|turtle-breakout|$bar", rows[0].id)
        assertEquals("BTCUSDT|connors-rsi|$bar", rows[1].id)
        assertNotEquals("One bar holds one row per strategy", rows[0].id, rows[1].id)
        assertEquals(bar, rows[0].barOpenTime)
    }

    @Test
    fun `given the same bar is evaluated twice when recorded then the key is identical so the row is replaced not appended`() {
        val bar = 1_700_000_060_000L
        val first = signal("donchian", "Donchian Channel", SignalDirection.BULLISH)
            .toLedgerRow("ETHUSDT", bar, issuedTimestamp = bar)
        val second = first.copy(issuedTimestamp = bar + 10_000L)

        assertEquals(
            "A ten-second cycle re-reading the same bar must not grow the ledger",
            first.id,
            second.id
        )
    }

    @Test
    fun `given a neutral reading when recorded then it is stored rather than omitted`() {
        // "This rule saw nothing on this bar" is evidence; leaving it out would make the
        // ledger's coverage look better than it is.
        val row = signal("vwap-reversion", "VWAP Reversion", SignalDirection.NEUTRAL)
            .toLedgerRow("SOLUSDT", barOpenTime = 1L, issuedTimestamp = 2L)

        assertEquals(SignalDirection.NEUTRAL.name, row.direction)
        assertEquals(SignalDirection.NEUTRAL, row.toSignal().direction)
    }

    @Test
    fun `given a stored row when read back then the domain signal carries the same rule, direction and levels`() {
        val original = StrategySignal(
            id = "triple-screen",
            name = "Triple Screen",
            direction = SignalDirection.BEARISH,
            entry = 2.10,
            exit = 1.98,
            invalidation = 2.16,
            detail = "EMA20 < EMA50, RSI 38, prior low broken",
            distanceAtr = 1.2,
            zoneTouches = 2
        )

        val restored = original
            .toLedgerRow("SUIUSDT", barOpenTime = 5L, issuedTimestamp = 6L)
            .toSignal()

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.direction, restored.direction)
        assertEquals(original.entry, restored.entry)
        assertEquals(original.exit, restored.exit)
        assertEquals(original.invalidation, restored.invalidation)
        assertEquals(original.distanceAtr, restored.distanceAtr)
        assertEquals(original.zoneTouches, restored.zoneTouches)
    }

    @Test
    fun `given a row whose direction text is unknown when read back then it degrades to neutral instead of crashing`() {
        val row = signal("orb", "Opening Range Breakout", SignalDirection.BULLISH)
            .toLedgerRow("ARBUSDT", barOpenTime = 1L, issuedTimestamp = 1L)
            .copy(direction = "SIDEWAYS")

        assertEquals(SignalDirection.NEUTRAL, row.toSignal().direction)
        assertFalse(row.toSignal().isActive)
    }

    private fun signal(id: String, name: String, direction: SignalDirection) = StrategySignal(
        id = id,
        name = name,
        direction = direction,
        entry = 100.0,
        exit = 104.0,
        invalidation = 98.0,
        detail = "$name reading for the test",
        distanceAtr = 0.8,
        zoneTouches = 1
    )

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
