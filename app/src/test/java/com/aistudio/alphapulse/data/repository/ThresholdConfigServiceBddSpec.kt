package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.local.ThresholdStore
import com.aistudio.alphapulse.domain.campaign.ThresholdConfig
import com.aistudio.alphapulse.domain.campaign.ThresholdField
import com.aistudio.alphapulse.domain.campaign.Thresholds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-59 — a threshold change is one auditable decision, and a refused change is none.
 *
 * The tab could otherwise persist quietly and leave the ledger unable to explain why a
 * coin's gate moved. Every accepted change writes exactly one decision row with its scope,
 * field and both values; a rejected candidate writes nothing and changes nothing.
 *
 * Spec Ref: Phase 2 C-59, C-29, §21
 */
class ThresholdConfigServiceBddSpec {

    private class FakeStore(var config: ThresholdConfig = ThresholdConfig.DEFAULT) : ThresholdStore {
        var saves = 0
        override fun load(): ThresholdConfig = config
        override fun save(config: ThresholdConfig) {
            this.config = config
            saves++
        }
    }

    private val clock = 1_700_000_123_000L
    private val ledger = mutableListOf<com.aistudio.alphapulse.data.db.DecisionRecordEntity>()

    private fun service(store: ThresholdStore, id: String = "change-1") = ThresholdConfigService(
        store = store,
        writeLedger = { record -> ledger += record },
        now = { clock },
        newId = { id }
    )

    @After
    fun restoreDefaults() = Thresholds.install(ThresholdConfig.DEFAULT)

    @Test
    fun `given the floor is raised when the change is applied then it is installed persisted and recorded once`() =
        runBlocking {
            val store = FakeStore()
            val outcome = service(store).apply(
                candidate = ThresholdConfig(floorPct = 90.0),
                field = ThresholdField.FLOOR,
                scope = "GLOBAL",
                symbol = "BTCUSDT"
            )

            assertTrue(outcome.accepted)
            assertNull(outcome.rejection)
            assertEquals(90.0, Thresholds.current().floorPct, 1e-9)
            assertEquals(90.0, store.config.floorPct, 1e-9)
            assertEquals(1, store.saves)

            val row = ledger.single()
            assertEquals("BTCUSDT", row.symbol)
            assertEquals(ThresholdAudit.STATE, row.state)
            assertEquals(ThresholdAudit.SOURCE, row.source)
            assertEquals(ThresholdAudit.HORIZON, row.referenceHorizon)
            assertEquals(clock, row.issuedTimestamp)
            assertTrue("scope must be recorded: ${row.reason}", row.reason.contains("scope=GLOBAL"))
            assertTrue("the layer/field must be recorded: ${row.reason}", row.reason.contains("FLOOR"))
            assertTrue("the old value must be recorded: ${row.reason}", row.reason.contains("85.0"))
            assertTrue("the new value must be recorded: ${row.reason}", row.reason.contains("90.0"))
        }

    @Test
    fun `given a change that would leave PRICE unproven but unblocking when it is applied then it is refused and nothing is written`() =
        runBlocking {
            val store = FakeStore()
            val outcome = service(store).apply(
                candidate = ThresholdConfig(minSettledSlow = 0),
                field = ThresholdField.SLOW_SAMPLES,
                scope = "GLOBAL",
                symbol = "ETHUSDT"
            )

            assertFalse(outcome.accepted)
            assertTrue(outcome.rejection!!.contains("PRICE_UNPROVEN_BUT_UNBLOCKING"))
            assertEquals(ThresholdConfig.DEFAULT, Thresholds.current())
            assertEquals(0, store.saves)
            assertTrue("a refused change is not a decision", ledger.isEmpty())
        }

    @Test
    fun `given changed thresholds when they are reset then the shipped defaults are persisted and recorded as a decision`() =
        runBlocking {
            val store = FakeStore(
                ThresholdConfig(
                    floorPct = 92.0,
                    minSettledFast = 30,
                    minSettledSlow = 8,
                    minSettledStrategy = 44
                )
            )
            Thresholds.install(store.config)

            val outcome = service(store).reset(scope = "GLOBAL", symbol = "BTCUSDT")

            assertTrue(outcome.accepted)
            assertEquals(85.0, Thresholds.current().floorPct, 1e-9)
            assertEquals(20, Thresholds.current().minSettledFast)
            assertEquals(4, Thresholds.current().minSettledSlow)
            assertEquals(20, Thresholds.current().minSettledStrategy)
            assertEquals(ThresholdConfig.DEFAULT, store.config)
            assertEquals("RESET", ledger.single().state.let { ThresholdAudit.STATE })
            assertTrue(ledger.single().reason.contains("RESET"))
        }

    @Test
    fun `given two consecutive accepted changes then each is its own ledger row`() = runBlocking {
        val store = FakeStore()
        val service = service(store)
        service.apply(ThresholdConfig(floorPct = 88.0), ThresholdField.FLOOR, "GLOBAL", "BTCUSDT")
        service.apply(ThresholdConfig(floorPct = 91.0), ThresholdField.FLOOR, "GLOBAL", "BTCUSDT")

        assertEquals(2, ledger.size)
        assertTrue(ledger[1].reason.contains("91.0"))
    }
}
