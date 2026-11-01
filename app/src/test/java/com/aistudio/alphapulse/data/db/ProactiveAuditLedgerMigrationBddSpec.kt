package com.aistudio.alphapulse.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §71 / Contract C-22 — the 25-field ProactiveMS audit ledger and the v6 -> v7 schema
 * that carries it.
 *
 * The schema statement, the field list and the entity live in production
 * (`data/db/ProactiveAuditLedger.kt`) and the migration runs that same statement, so a
 * field dropped from the contract, a column missing from the migration, or a database
 * that forgets to version the table all fail here.
 *
 * Spec Ref: §71, §21.2, C-22
 */
class ProactiveAuditLedgerMigrationBddSpec {

    @Test
    fun `given the 25 mandatory audit fields when the schema is read then exactly those fields are present in contract order`() {
        assertEquals(
            "The audit ledger is a 25-field contract; a shortened list is a different record",
            25,
            ProactiveAuditSchema.AUDIT_FIELDS.size
        )
        assertEquals(
            listOf(
                "timestamp",
                "asset",
                "positionDirection",
                "entryPrice",
                "currentPrice",
                "initialRiskR",
                "currentPnlR",
                "coneCentre",
                "coneBoundary",
                "coneConfidencePct",
                "breachMagnitude",
                "sgdRankBefore",
                "sgdRankAfter",
                "rerankingMagnitude",
                "triggerType",
                "proactiveThresholdR",
                "actionTaken",
                "exitBeforeHalfR",
                "exitBeforeOneR",
                "subsequentlyReachedMinus2R",
                "finalOutcome",
                "walkForwardCycleId",
                "trainingWindowId",
                "outOfSampleWindowId",
                "parameterSetVersion"
            ),
            ProactiveAuditSchema.AUDIT_FIELDS
        )
    }

    @Test
    fun `given the production 6 to 7 migration when its statement is read then it creates a column for every audit field plus the key`() {
        val ddl = ProactiveAuditSchema.DDL

        assertTrue(
            "The migration must create the ledger table: $ddl",
            ddl.contains("CREATE TABLE IF NOT EXISTS `${ProactiveAuditSchema.TABLE}`")
        )
        ProactiveAuditSchema.AUDIT_FIELDS.forEach { field ->
            assertTrue("The migration must declare the `$field` column: $ddl", ddl.contains("`$field`"))
        }
        assertTrue(
            "The ledger needs a primary key to be append-only and addressable: $ddl",
            ddl.contains("PRIMARY KEY(`${ProactiveAuditSchema.KEY_COLUMN}`)")
        )
    }

    @Test
    fun `given the ledger entity when reflected then it declares the key and every one of the 25 audit fields`() {
        val fields = ProactiveAuditLedgerEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()

        assertTrue(
            "Every audit field must be a column on the entity: missing " +
                ProactiveAuditSchema.AUDIT_FIELDS.filterNot { it in fields },
            fields.containsAll(ProactiveAuditSchema.AUDIT_FIELDS)
        )
        assertTrue(
            "The entity needs its key column",
            ProactiveAuditSchema.KEY_COLUMN in fields
        )
        assertEquals(
            "The entity carries the key plus exactly the 25 contract fields",
            26,
            fields.size
        )
    }

    @Test
    fun `given the database when inspected then it is version 7 and the ledger table is part of it`() {
        // Room's @Database carries build-time retention, so the version is proved through
        // the migration the app actually installs rather than through reflection on it.
        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)
        assertEquals(
            "The ledger arrives as a schema version, not as a nullable column on an older table",
            7,
            AppDatabase.MIGRATION_6_7.endVersion
        )
        assertTrue(
            "The database must expose the ledger DAO",
            AppDatabase::class.java.methods.any { it.name == "proactiveAuditLedgerDao" }
        )
    }
}
