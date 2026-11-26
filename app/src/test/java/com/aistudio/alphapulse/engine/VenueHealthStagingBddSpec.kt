package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.data.model.TakeProfitTarget
import com.aistudio.alphapulse.data.model.VenueHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-63 — hardware and venue health block live routing, not paper planning.
 *
 * Every board was suppressed with `STAGING_GATE_PAPER / Venue or hardware health
 * guardrail tripped` because one optional feed answered slowly (CoinGlass 9999ms) and
 * Binance had a latency spike. Paper mode routes nothing, so those are advisories there;
 * a live mode still refuses. A stale market feed and the kill switch block everywhere.
 *
 * Spec Ref: §2, §22, §23, §36, C-63
 */
class VenueHealthStagingBddSpec {

    private val risk = RiskEngine()
    private val envelope = RiskEnvelope(currentGrossExposureUsd = 0.0, maxGrossExposureUsd = 1_000_000.0)

    private val latencySpike = VenueHealth(
        binancePingMs = 987,
        coinglassPingMs = 9_999,
        isLatencySpike = true
    )

    @Test
    fun `given a latency spike in Paper mode when assessed then it is an advisory and the board may stage`() {
        val verdict = VenueHealthVerdict.assess(latencySpike, ExecutionMode.PAPER)

        assertNull("Paper must not be suppressed by venue latency: ${verdict.hardBlock}", verdict.hardBlock)
        assertTrue("the latency still has to be reported", verdict.advisories.isNotEmpty())

        val (allowed, reason) = risk.validateCampaignStaging(
            campaign(), emptyList(), envelope, latencySpike, ExecutionMode.PAPER
        )
        assertTrue("paper staging must be allowed, refused: $reason", allowed)
    }

    @Test
    fun `given a latency spike in Shadow mode when assessed then it is an advisory`() {
        assertNull(VenueHealthVerdict.assess(latencySpike, ExecutionMode.SHADOW).hardBlock)
    }

    @Test
    fun `given a latency spike in a live mode when assessed then it blocks routing and names latency`() {
        val verdict = VenueHealthVerdict.assess(latencySpike, ExecutionMode.CAPPED_LIVE)

        assertNotNull(verdict.hardBlock)
        assertTrue(
            "the block must name the latency: ${verdict.hardBlock}",
            verdict.hardBlock!!.contains("atency")
        )

        val (allowed, reason) = risk.validateCampaignStaging(
            campaign(), emptyList(), envelope, latencySpike, ExecutionMode.SCALED_LIVE
        )
        assertFalse("live routing must stay blocked", allowed)
        assertTrue("the refusal must name the venue: $reason", reason!!.contains("Venue"))
    }

    @Test
    fun `given a stale market feed when assessed then no mode stages`() {
        val stale = VenueHealth(feedStalenessSec = 40, isFeedStale = true)

        for (mode in ExecutionMode.entries) {
            val verdict = VenueHealthVerdict.assess(stale, mode)
            assertNotNull(
                "a stale feed must block ${mode.name}",
                verdict.hardBlock
            )
        }

        val (allowed, _) = risk.validateCampaignStaging(
            campaign(), emptyList(), envelope, stale, ExecutionMode.PAPER
        )
        assertFalse(allowed)
    }

    @Test
    fun `given low power or thermal throttling in Paper mode when assessed then it is advisory, and in a live mode it blocks`() {
        val constrained = VenueHealth(isLowPowerMode = true, isThermallyThrottled = true)

        assertNull(VenueHealthVerdict.assess(constrained, ExecutionMode.PAPER).hardBlock)
        assertNotNull(VenueHealthVerdict.assess(constrained, ExecutionMode.SCALED_LIVE).hardBlock)
    }

    @Test
    fun `given a healthy venue when assessed then there is no block and no advisory`() {
        val verdict = VenueHealthVerdict.assess(VenueHealth(), ExecutionMode.SCALED_LIVE)

        assertNull(verdict.hardBlock)
        assertTrue(verdict.advisories.isEmpty())
        assertTrue(verdict.canStage)
    }

    private fun campaign() = Campaign(
        id = "SOL_LONG",
        asset = "SOLUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BALANCE,
        role = BoardRole.PRIMARY,
        priorityScore = 70.0,
        sizeBudgetUsd = 1_000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(
            LadderSlice(
                sliceIndex = 1,
                price = 100.0,
                qty = 10.0,
                notionalUsd = 1_000.0,
                orderType = OrderType.PASSIVE_LIMIT
            )
        ),
        stopLogic = StopLogic(hardStopPrice = 98.0),
        targets = listOf(
            TakeProfitTarget(levelName = "TP1", price = 104.0, percentClose = 100)
        ),
        invalidationScore = 10,
        topReasons = emptyList(),
        status = CampaignState.PLANNED
    )
}
