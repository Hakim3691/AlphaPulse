package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.MarketRegime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C-22 / C-58 — a universe refresh must never repoint the workspace at another coin.
 *
 * The dormant poll rebuilt the campaign universe every twenty seconds and, when the coin
 * under inspection was not in the refreshed list, selected the list's first row instead.
 * Once an unproven coin stopped entering the campaign universe (C-58), that fallback fired
 * on the next poll: the operator selected `USDBRLUSDT` in AlphaPulse and the Dashboard
 * quietly showed `SOLUSDT`. The fallback picks a coin, and picking a coin is the
 * operator's job.
 *
 * Spec Ref: Phase 2 §72, C-22, C-58
 */
class UniverseSelectionBddSpec {

    @Test
    fun `given the inspected coin is still in the refreshed universe when it is applied then the refreshed row is used`() {
        val refreshed = listOf(asset("SOLUSDT", 72.9), asset("USDBRLUSDT", 5.92))

        val chosen = UniverseSelection.keepInspected(asset("USDBRLUSDT", 5.90), refreshed)

        assertEquals("USDBRLUSDT", chosen?.symbol)
        assertEquals(5.92, chosen?.lastPrice ?: 0.0, 1e-9)
    }

    @Test
    fun `given the inspected coin left the refreshed universe when it is applied then the inspected coin is kept`() {
        val chosen = UniverseSelection.keepInspected(
            current = asset("USDBRLUSDT", 5.90),
            refreshed = listOf(asset("SOLUSDT", 72.9))
        )

        assertEquals("USDBRLUSDT", chosen?.symbol)
    }

    @Test
    fun `given nothing inspected yet when a universe arrives then its first coin is selected`() {
        val chosen = UniverseSelection.keepInspected(
            current = null,
            refreshed = listOf(asset("SOLUSDT", 72.9), asset("PAXGUSDT", 2400.0))
        )

        assertEquals("SOLUSDT", chosen?.symbol)
    }

    @Test
    fun `given nothing inspected and an empty universe when it is applied then nothing is invented`() {
        assertNull(UniverseSelection.keepInspected(current = null, refreshed = emptyList()))
    }

    @Test
    fun `given the inspected coin with no refreshed rows at all when it is applied then the inspected coin survives`() {
        val chosen = UniverseSelection.keepInspected(
            current = asset("USDBRLUSDT", 5.90),
            refreshed = emptyList()
        )

        assertEquals("USDBRLUSDT", chosen?.symbol)
    }

    private fun asset(symbol: String, price: Double) = CryptoAsset(
        symbol = symbol,
        baseAsset = symbol.removeSuffix("USDT"),
        lastPrice = price,
        priceChange24h = 0.0,
        quoteVolume24h = 0.0,
        orderBookSpreadPct = 0.0,
        fundingRatePct = 0.0,
        predictedFundingPct = 0.0,
        openInterestUsd = 0.0,
        basisPremiumPct = 0.0,
        atr5m = 0.1,
        atr1h = 0.2,
        atr4h = 0.3,
        regime = MarketRegime.BALANCE,
        tacticalBias = 0,
        strategicBias = 0,
        primaryPainPath = "test"
    )
}
