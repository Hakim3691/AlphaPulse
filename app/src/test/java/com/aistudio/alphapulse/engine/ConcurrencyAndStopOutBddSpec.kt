package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.data.model.TakeProfitTarget
import com.aistudio.alphapulse.data.model.VenueHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §23 / Contract C-50 — one owner per side, two boards per asset, and a stop-out
 * figure that nets the book.
 *
 * The risk engine already refused a full-size long and a full-size breakdown short on
 * the same symbol, but nothing stopped a second same-side board from stacking on that
 * symbol, and the published "worst-case stop-out" added every board's notional as if a
 * hedge were two independent losses. Both readings overstate what can actually be lost
 * and understate what can be opened.
 *
 * Spec Ref: Phase 2 §23, §20
 */
class ConcurrencyAndStopOutBddSpec {

    private val risk = RiskEngine()
    private val envelope = RiskEnvelope(currentGrossExposureUsd = 0.0, maxGrossExposureUsd = 1_000_000.0)
    private val healthyVenue = VenueHealth()

    @Test
    fun `given two live boards on one asset when a third is validated then it is refused by the per-asset cap`() {
        // Given: one long and one short already live on SOL.
        val long = campaign("SOL_LONG", "SOLUSDT", CampaignFamily.SUPPORT_LONG, CampaignState.STAGED)
        val short = campaign("SOL_SHORT", "SOLUSDT", CampaignFamily.RESISTANCE_SHORT, CampaignState.ACTIVE)

        // When: a breakout long is staged on the same asset.
        val candidate = campaign("SOL_ZBREAK", "SOLUSDT", CampaignFamily.BREAKOUT_LONG, CampaignState.PLANNED)
        val (allowed, reason) = risk.validateCampaignStaging(
            candidate,
            listOf(long, short, candidate),
            envelope,
            healthyVenue
        )

        // Then: the cap refuses it and names the shipped limit.
        assertFalse(allowed)
        assertTrue("reason should name the cap: $reason", reason!!.contains("2"))
        assertTrue("reason should name the asset: $reason", reason.contains("SOLUSDT"))
    }

    @Test
    fun `given a live long when a second long is validated then one side has a single owner`() {
        val live = campaign("SOL_LONG", "SOLUSDT", CampaignFamily.SUPPORT_LONG, CampaignState.STAGED)
        val candidate = campaign("SOL_RECLAIM", "SOLUSDT", CampaignFamily.RECLAIM_LONG, CampaignState.PLANNED)

        val (allowed, reason) = risk.validateCampaignStaging(
            candidate,
            listOf(live, candidate),
            envelope,
            healthyVenue
        )

        assertFalse(allowed)
        assertTrue("reason should say single owner: $reason", reason!!.contains("owner", ignoreCase = true))
        assertTrue("reason should name the owner: $reason", reason.contains("SOL_LONG"))
    }

    @Test
    fun `given a hedged board on one asset when a board on another asset is validated then it is allowed`() {
        val long = campaign("SOL_LONG", "SOLUSDT", CampaignFamily.SUPPORT_LONG, CampaignState.STAGED)
        val short = campaign("SOL_SHORT", "SOLUSDT", CampaignFamily.RESISTANCE_SHORT, CampaignState.STAGED)
        val candidate = campaign("ETH_LONG", "ETHUSDT", CampaignFamily.SUPPORT_LONG, CampaignState.PLANNED)

        val (allowed, reason) = risk.validateCampaignStaging(
            candidate,
            listOf(long, short, candidate),
            envelope,
            healthyVenue
        )

        assertTrue("expected allowed, refused: $reason", allowed)
    }

    @Test
    fun `given a fully hedged book when the worst-case stop-out is computed then the net exposure is zero`() {
        // Given: equal long and short budgets on the same symbol, both with 2% stops.
        val long = campaign("SOL_LONG", "SOLUSDT", CampaignFamily.SUPPORT_LONG, CampaignState.ACTIVE, budget = 10_000.0)
        val short = campaign("SOL_SHORT", "SOLUSDT", CampaignFamily.RESISTANCE_SHORT, CampaignState.ACTIVE, budget = 10_000.0)

        val riskBook = risk.computeStopOutRisk(listOf(long, short), accountEquity = 100_000.0)

        // Then: a hedge cannot produce a directional stop-out.
        assertEquals(0.0, riskBook.worstCaseR, 1e-9)
        assertEquals(0.0, riskBook.perAssetR.getValue("SOLUSDT"), 1e-9)
    }

    @Test
    fun `given two long boards on one asset when the worst-case stop-out is computed then their notional adds and the real stop distance is used`() {
        // Given: 10k + 10k long at 100 with hard stops at 98 (2%), equity 100k -> 1R = 500.
        val first = campaign("SOL_A", "SOLUSDT", CampaignFamily.SUPPORT_LONG, CampaignState.STAGED, budget = 10_000.0)
        val second = campaign("SOL_B", "SOLUSDT", CampaignFamily.RECLAIM_LONG, CampaignState.STAGED, budget = 10_000.0)

        val riskBook = risk.computeStopOutRisk(listOf(first, second), accountEquity = 100_000.0)

        // 20,000 notional x 2% = 400 USD = 0.8R.
        assertEquals(0.8, riskBook.worstCaseR, 1e-6)
    }

    @Test
    fun `given no live campaigns when the worst-case stop-out is computed then the book is riskless and names no asset`() {
        val closed = campaign("SOL_DONE", "SOLUSDT", CampaignFamily.SUPPORT_LONG, CampaignState.COMPLETED)

        val riskBook = risk.computeStopOutRisk(listOf(closed), accountEquity = 100_000.0)

        assertEquals(0.0, riskBook.worstCaseR, 1e-9)
        assertTrue(riskBook.perAssetR.isEmpty())
    }

    private fun campaign(
        id: String,
        asset: String,
        family: CampaignFamily,
        status: CampaignState,
        budget: Double = 1_000.0
    ) = Campaign(
        id = id,
        asset = asset,
        family = family,
        regime = MarketRegime.BALANCE,
        role = BoardRole.PRIMARY,
        priorityScore = 70.0,
        sizeBudgetUsd = budget,
        sizeMultiplier = 1.0,
        entryLadder = listOf(
            LadderSlice(
                sliceIndex = 1,
                price = 100.0,
                qty = budget / 100.0,
                notionalUsd = budget,
                orderType = OrderType.PASSIVE_LIMIT
            )
        ),
        stopLogic = StopLogic(hardStopPrice = 98.0),
        targets = listOf(TakeProfitTarget("Target", 104.0, 100)),
        invalidationScore = 10,
        topReasons = emptyList(),
        status = status
    )
}
