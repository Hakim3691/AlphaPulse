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
 * Binance had a latency spike. The optional probe is now never a staging block, Paper
 * treats execution latency as an advisory, a stale Binance feed / thermal throttling /
 * the RAM floor block every mode, and a live mode still refuses to route while latency
 * or low power is in force. The kill switch and daily stop stay in `RiskEngine`.
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
        assertTrue("the refusal must name the latency: $reason", reason!!.contains("Binance"))
    }

    @Test
    fun `given only the optional CoinGlass feed is unreachable when assessed then no mode is blocked and the advisory names it`() {
        val optionalFeedDown = VenueHealth(
            binancePingMs = 30,
            coinglassPingMs = 9_999,
            isFeedStale = false,
            isLatencySpike = false
        )

        for (mode in ExecutionMode.entries) {
            val verdict = VenueHealthVerdict.assess(optionalFeedDown, mode)
            assertNull(
                "an optional feed must not block ${mode.name}: ${verdict.hardBlock}",
                verdict.hardBlock
            )
            assertTrue(
                "the optional feed is still reported to ${mode.name}",
                verdict.advisories.any { it.contains("CoinGlass") }
            )
        }

        val (paperAllowed, paperReason) = risk.validateCampaignStaging(
            campaign(), emptyList(), envelope, optionalFeedDown, ExecutionMode.PAPER
        )
        assertTrue("paper staging must be allowed: $paperReason", paperAllowed)
        val (liveAllowed, liveReason) = risk.validateCampaignStaging(
            campaign(), emptyList(), envelope, optionalFeedDown, ExecutionMode.CAPPED_LIVE
        )
        assertTrue("live staging must not be refused for the optional feed: $liveReason", liveAllowed)
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
    fun `given thermal throttling when assessed then every mode is blocked, including Paper`() {
        val throttled = VenueHealth(isThermallyThrottled = true)

        for (mode in ExecutionMode.entries) {
            assertNotNull(
                "thermal throttling must block ${mode.name}",
                VenueHealthVerdict.assess(throttled, mode).hardBlock
            )
        }
    }

    @Test
    fun `given the memory floor is breached when assessed then every mode is blocked`() {
        val starved = VenueHealth(availableRamMb = 256, isRamConstrained = true)

        for (mode in ExecutionMode.entries) {
            assertNotNull(
                "the RAM floor must block ${mode.name}",
                VenueHealthVerdict.assess(starved, mode).hardBlock
            )
        }
    }

    @Test
    fun `given low power in Paper mode when assessed then it is advisory, and in a live mode it blocks`() {
        val lowPower = VenueHealth(isLowPowerMode = true)

        assertNull(VenueHealthVerdict.assess(lowPower, ExecutionMode.PAPER).hardBlock)
        assertNotNull(VenueHealthVerdict.assess(lowPower, ExecutionMode.SCALED_LIVE).hardBlock)
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
