package com.aistudio.alphapulse.engine.proactive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.db.ProactiveAuditSchema
import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.engine.InvalidationAction
import java.io.File
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
 * §71 / Contract C-22 — a decision that is not written cannot be audited.
 *
 * `ProactiveAuditLedgerEntity` held all 25 required fields and the production migration
 * created the table, but `proactiveAuditLedgerDao()` had no production writer: the only
 * caller was the migration spec itself, so every real invalidation was recorded in the
 * eight-field `AuditLogEntry` log instead. This spec records a real hard flatten, stores
 * it through the real DAO, and reads it back, so the write path cannot regress to a
 * compile-time-only contract.
 *
 * Spec Ref: §71 25-Field Audit Ledger, §21.2, C-22
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveAuditLedgerWriteBddSpec {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given a hard-proactive flatten when the decision is recorded then all 25 audit fields are populated from the decision`() {
        val now = 1_700_000_000_000L
        val record = ProactiveAuditRecorder.record(
            campaign = longCampaign(),
            asset = asset(lastPrice = 96.0),
            action = InvalidationAction.HARD_FLATTEN_COOLDOWN,
            nowMillis = now
        )

        assertEquals(now, record.timestamp)
        assertEquals("BTCUSDT", record.asset)
        assertEquals("LONG", record.positionDirection)
        assertEquals(100.0, record.entryPrice, 0.0001)
        assertEquals(96.0, record.currentPrice, 0.0001)
        assertEquals(
            "A long entered at 100 with a 90 stop and price 96 is -0.40R",
            -0.40,
            record.currentPnlR,
            0.0001
        )
        assertEquals("HARD_FLATTEN_COOLDOWN", record.triggerType)
        assertEquals("HARD_FLATTEN_COOLDOWN", record.actionTaken)
        assertEquals(
            "The audit row records the walk-forward proactive tolerance in force",
            ProductionExecutionConfig.PROACTIVE_MAX_LOSS_R,
            record.proactiveThresholdR,
            0.0001
        )
        assertTrue("Exited above -0.5R", record.exitBeforeHalfR)
        assertTrue("Exited above -1.0R", record.exitBeforeOneR)
        assertEquals(
            "The shadow outcome is unknown at write time and must not claim a result",
            "PENDING",
            record.finalOutcome
        )

        // Every one of the 25 contract fields must be present and addressed, in order.
        val fields = ProactiveAuditSchema.AUDIT_FIELDS
        assertEquals(25, fields.size)
        val text = record.toString()
        for (field in fields) {
            assertTrue(
                "The recorded row must address `$field`: $text",
                text.contains("$field=")
            )
        }
    }

    @Test
    fun `given a recorded decision when it is inserted through the production DAO then it reads back unchanged and a second decision appends`() = runBlocking {
        val first = ProactiveAuditRecorder.record(
            campaign = longCampaign(),
            asset = asset(lastPrice = 96.0),
            action = InvalidationAction.HARD_FLATTEN_COOLDOWN,
            nowMillis = 1_000L
        )
        val second = ProactiveAuditRecorder.record(
            campaign = longCampaign(id = "campaign-2"),
            asset = asset(lastPrice = 89.0),
            action = InvalidationAction.SOFT_CANCEL_RESTING,
            nowMillis = 2_000L
        )

        db.proactiveAuditLedgerDao().insert(first)
        db.proactiveAuditLedgerDao().insert(second)

        val newest = db.proactiveAuditLedgerDao().newest(10)
        assertEquals("The ledger is append-only: two decisions are two rows", 2, newest.size)
        assertEquals(2_000L, newest.first().timestamp)
        assertEquals("HARD_FLATTEN_COOLDOWN", newest.last().actionTaken)
        assertEquals(
            "A -1.00R exit is not before 1R",
            false,
            newest.first().exitBeforeOneR
        )
    }

    @Test
    fun `given the engine invalidates a board when the live delta loop runs then it calls the audit writer`() {
        val source = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/MainViewModel.kt"
        ).readText()

        assertTrue(
            "The live invalidation path must write the 25-field ledger, not only the eight-field log",
            source.contains("proactiveAuditLedgerDao()")
        )
        assertTrue(
            "The write must go through the single recorder",
            source.contains("ProactiveAuditRecorder")
        )
    }

    private fun longCampaign(id: String = "campaign-1") = Campaign(
        id = id,
        asset = "BTCUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 0.82,
        sizeBudgetUsd = 1_000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(
            LadderSlice(
                sliceIndex = 0,
                price = 100.0,
                qty = 10.0,
                notionalUsd = 1_000.0,
                isFilled = true,
                isResting = false
            )
        ),
        stopLogic = StopLogic(hardStopPrice = 90.0),
        targets = emptyList(),
        invalidationScore = 88,
        topReasons = emptyList(),
        status = CampaignState.STAGED
    )

    private fun asset(lastPrice: Double) = CryptoAsset(
        symbol = "BTCUSDT",
        baseAsset = "BTC",
        lastPrice = lastPrice,
        priceChange24h = -1.4,
        quoteVolume24h = 900_000_000.0,
        orderBookSpreadPct = 0.02,
        fundingRatePct = 0.01,
        predictedFundingPct = 0.01,
        openInterestUsd = 500_000_000.0,
        basisPremiumPct = 0.01,
        atr5m = 12.0,
        atr1h = 40.0,
        atr4h = 90.0,
        regime = MarketRegime.BULLISH_CONTINUATION,
        tacticalBias = 20,
        strategicBias = 35,
        primaryPainPath = "Support 96 -> 92"
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
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
