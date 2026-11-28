package com.aistudio.alphapulse.domain.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-66 — the 44 catalogue setups are reachable from the ledgers they inform.
 *
 * The catalogue was a detached 44-row card at the bottom of the AlphaPulse scroll. Each
 * of the price, pattern and strategy ledgers now owns an info action, and their slices
 * must partition the taxonomy exactly so nothing is lost and nothing is listed twice.
 */
class CatalogueLedgerInfoBddSpec {

    @Test
    fun `given the catalogue when counted then it still carries exactly 44 setups`() {
        assertEquals(44, TradeIdeasCatalog.all.size)
        assertEquals(44, TradeIdeasCatalog.all.map { it.id }.distinct().size)
    }

    @Test
    fun `given the three ledgers when each slice is read then the counts are eight twenty seven and nine`() {
        assertEquals(8, CatalogueLedgerInfo.ideasFor(CatalogueLedger.PRICE).size)
        assertEquals(27, CatalogueLedgerInfo.ideasFor(CatalogueLedger.PATTERN).size)
        assertEquals(9, CatalogueLedgerInfo.ideasFor(CatalogueLedger.STRATEGY).size)
    }

    @Test
    fun `given the three ledger slices when unioned then they cover all 44 setups without overlap`() {
        val slices = CatalogueLedger.entries.map { ledger ->
            CatalogueLedgerInfo.ideasFor(ledger).map { it.id }.toSet()
        }

        assertEquals(44, slices.sumOf { it.size })
        assertEquals("No setup may appear under two ledgers", 44, slices.flatten().distinct().size)
        assertEquals(
            "Every catalogue setup must be reachable from one ledger",
            TradeIdeasCatalog.all.map { it.id }.toSet(),
            slices.flatten().toSet()
        )
    }

    @Test
    fun `given a ledger slice when read then it only contains its own catalogue categories`() {
        CatalogueLedger.entries.forEach { ledger ->
            val categories = CatalogueLedgerInfo.categoriesFor(ledger)
            assertTrue(
                "$ledger must read at least one category",
                categories.isNotEmpty()
            )
            assertTrue(
                "$ledger returned a setup outside $categories",
                CatalogueLedgerInfo.ideasFor(ledger).all { it.category in categories }
            )
        }
    }

    @Test
    fun `given the three ledgers when titled then each title names its ledger and its count`() {
        assertEquals("PRICE TRADE IDEAS · 8", CatalogueLedgerInfo.titleFor(CatalogueLedger.PRICE))
        assertEquals("PATTERN TRADE IDEAS · 27", CatalogueLedgerInfo.titleFor(CatalogueLedger.PATTERN))
        assertEquals("STRATEGY TRADE IDEAS · 9", CatalogueLedgerInfo.titleFor(CatalogueLedger.STRATEGY))
    }
}
