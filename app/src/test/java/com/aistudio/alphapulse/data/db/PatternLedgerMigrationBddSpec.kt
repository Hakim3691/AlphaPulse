package com.aistudio.alphapulse.data.db

import com.aistudio.alphapulse.domain.pattern.PatternLedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §73 / Contract C-23 — the pattern ledger's schema and the v7 -> v8 migration.
 *
 * The directive mounts a second ledger *without* disturbing the first, so this spec proves
 * three things: the pattern fields are a fixed list, the migration creates every one of
 * them in its own table, and the price ledger's schema is left exactly as it was.
 *
 * Spec Ref: §73, C-23, Directive §1 Architectural Non-Interference Mandate
 */
class PatternLedgerMigrationBddSpec {

    @Test
    fun `given the directive fields when the schema is read then exactly those fields are present in contract order`() {
        assertEquals(
            "The pattern ledger is an 11-field contract; a shortened list is a different record",
            11,
            PatternLedgerSchema.LEDGER_FIELDS.size
        )
        assertEquals(
            listOf(
                "asset",
                "horizon",
                "targetTime",
                "predictedPatternId",
                "predictedPatternName",
                "predictedCategory",
                "confidence",
                "realizedPattern",
                "fitLoss",
                "status",
                "sgdAction"
            ),
            PatternLedgerSchema.LEDGER_FIELDS
        )
    }

    @Test
    fun `given the production 7 to 8 migration when its statement is read then it creates every pattern column plus the key`() {
        val ddl = PatternLedgerSchema.DDL

        assertTrue(
            "The migration must create the pattern table: $ddl",
            ddl.contains("CREATE TABLE IF NOT EXISTS `${PatternLedgerSchema.TABLE}`")
        )
        PatternLedgerSchema.LEDGER_FIELDS.forEach { field ->
            assertTrue("The migration must declare the `$field` column: $ddl", ddl.contains("`$field`"))
        }
        assertTrue("The row needs its key", ddl.contains("PRIMARY KEY(`${PatternLedgerSchema.KEY_COLUMN}`)"))
        assertTrue("The row needs its issue time", ddl.contains("`issuedTimestamp`"))
        assertTrue("The row needs its settlement flag", ddl.contains("`settled`"))

        assertTrue(
            "The SGD head needs its own table: $ddl",
            PatternLedgerSchema.WEIGHTS_DDL.contains("CREATE TABLE IF NOT EXISTS `${PatternLedgerSchema.WEIGHTS_TABLE}`")
        )
        for (column in listOf("compositeKey", "symbol", "horizonId", "weightsCsv", "learningRate", "steps", "lastLoss", "updatedAt")) {
            assertTrue(
                "The weights table must persist `$column`",
                PatternLedgerSchema.WEIGHTS_DDL.contains("`$column`")
            )
        }
    }

    @Test
    fun `given the production 8 to 9 migration when its statements are read then each TSFM column is added without touching the price ledger`() {
        val statements = PatternLedgerSchema.MIGRATION_8_9_SQL

        assertEquals(
            "One additive statement per evidence column: $statements",
            PatternLedgerSchema.TSFM_FIELDS.size,
            statements.size
        )
        PatternLedgerSchema.TSFM_FIELDS.forEach { field ->
            assertTrue(
                "The 8 -> 9 migration must add `$field`: $statements",
                statements.any { it.contains("ADD COLUMN `$field`") }
            )
        }
        assertTrue(
            "The migration must not drop or rewrite the directive's own fields: $statements",
            statements.none { it.contains("DROP") || it.contains("predictions") }
        )
        assertTrue(
            "The 7 -> 8 statement must stay the version-8 table; the evidence columns arrive in 8 -> 9",
            PatternLedgerSchema.TSFM_FIELDS.none { PatternLedgerSchema.DDL.contains("`$it`") }
        )
    }

    @Test
    fun `given the ledger entity when reflected then it declares the key and every directive field`() {
        val fields = PatternLedgerEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") || it.name == "Companion" }
            .map { it.name }
            .toSet()

        assertTrue(
            "Every directive field must be a column: missing " +
                PatternLedgerSchema.LEDGER_FIELDS.filterNot { it in fields },
            fields.containsAll(PatternLedgerSchema.LEDGER_FIELDS)
        )
        assertTrue("The entity needs its key", PatternLedgerSchema.KEY_COLUMN in fields)
        assertTrue("The entity needs its issue time", PatternLedgerSchema.ISSUED_COLUMN in fields)
        assertTrue("The entity needs its settlement flag", PatternLedgerSchema.SETTLED_COLUMN in fields)
        assertEquals(
            "The entity carries the key, two bookkeeping columns, the 11 fields and the 3 evidence columns",
            17,
            fields.size
        )
        assertTrue(
            "The entity must persist every TSFM evidence column: " +
                PatternLedgerSchema.TSFM_FIELDS.filterNot { it in fields },
            fields.containsAll(PatternLedgerSchema.TSFM_FIELDS)
        )
    }

    @Test
    fun `given the database when inspected then it is version 9 and the pattern tables are mounted beside the price ledger`() {
        assertEquals(7, AppDatabase.MIGRATION_7_8.startVersion)
        assertEquals(
            "The pattern ledger arrives as a schema version, not as nullable columns on `predictions`",
            8,
            AppDatabase.MIGRATION_7_8.endVersion
        )
        assertEquals(8, AppDatabase.MIGRATION_8_9.startVersion)
        assertEquals(
            "The TSFM evidence arrives as its own version, not as a rewrite of the ledger",
            9,
            AppDatabase.MIGRATION_8_9.endVersion
        )
        assertTrue(
            "The database must expose the pattern ledger DAO",
            AppDatabase::class.java.methods.any { it.name == "patternLedgerDao" }
        )
        assertTrue(
            "The price ledger table must still be part of the schema",
            AppDatabase::class.java.methods.any { it.name == "predictionDao" }
        )
    }

    @Test
    fun `given a ledger entry when it is persisted and read back then every field survives unchanged`() {
        val entry = PatternLedgerEntry(
            id = "VVVUSDT-1m-1763336700000",
            asset = "VVV",
            horizon = "1m",
            targetTime = 1_763_336_700_000L,
            issuedTimestamp = 1_763_336_641_000L,
            predictedPatternId = "bullish-engulfing",
            predictedPatternName = "Bullish Engulfing",
            predictedCategory = "Bullish Reversal",
            confidence = 0.82,
            realizedPattern = "Hammer",
            fitLoss = 0.41,
            status = "Settled",
            sgdAction = "SGD Step: +w_reinforce (Loss: 0.41)",
            settled = true,
            tsfmProvider = "chronos+timesfm",
            tsfmDriftPct = 0.12,
            tsfmAligned = true
        )

        val restored = PatternLedgerEntity.from(entry).toEntry()

        assertEquals(
            "The mapper must not drop or reorder a field on the way through Room",
            entry,
            restored
        )
    }
}
