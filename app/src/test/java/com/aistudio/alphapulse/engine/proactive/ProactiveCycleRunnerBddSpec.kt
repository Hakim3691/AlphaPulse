package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.LevelZone
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.data.model.ZoneType
import com.aistudio.alphapulse.engine.ExecutionEngine
import com.aistudio.alphapulse.engine.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §31/§38 / Contract C-48 — the boundary the Android evaluation cycle uses.
 *
 * Proves the loop pass records the paper evidence the §38 gate needs, and that the
 * verdict it produces is the one `ExecutionEngine.gateStaging` receives for Scaled
 * Live.
 *
 * Spec Ref: Phase 2 §31, §38
 */
class ProactiveCycleRunnerBddSpec {

    private val now = 2_000_000L

    private fun asset(lastPrice: Double = 95.0, priceChange24h: Double = -3.0) = CryptoAsset(
        symbol = "SOLUSDT",
        baseAsset = "SOL",
        lastPrice = lastPrice,
        priceChange24h = priceChange24h,
        quoteVolume24h = 120_000_000.0,
        orderBookSpreadPct = 0.04,
        fundingRatePct = 0.01,
        predictedFundingPct = 0.01,
        openInterestUsd = 60_000_000.0,
        basisPremiumPct = 0.02,
        atr5m = 1.5,
        atr1h = 3.0,
        atr4h = 5.0,
        regime = MarketRegime.BULLISH_CONTINUATION,
        tacticalBias = 50,
        strategicBias = 50,
        primaryPainPath = "Liquidity pool below"
    )

    private fun levelMap() = LevelMap(
        asset = "SOLUSDT",
        zones = listOf(
            LevelZone("s", ZoneType.HTF_SUPPORT, 99.0, 101.0, "4h", 80, 1, 70)
        ),
        dominantPivot = 100.0,
        damageScore = 20,
        confidenceSummary = 75,
        timestamp = now
    )

    private fun evidence(trap: Int = 80) = EvidenceFrame(
        timestamp = now,
        asset = "SOLUSDT",
        oiDelta1hPct = 1.20,
        fundingRateChangePct = 0.0,
        basisDeltaBps = 0.0,
        takerBuyRatio = 0.40,
        acceptanceQuality = 0.90,
        rejectionQuality = 0.90,
        trapProbabilityPct = trap,
        recentImpulseQuality = "normal"
    )

    private fun campaign() = Campaign(
        id = "SOLUSDT_SUPPORT_LONG",
        asset = "SOLUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 80.0,
        sizeBudgetUsd = 2_000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(100.0, 99.0, 98.0).mapIndexed { index, price ->
            LadderSlice(index, price, 1.0, 500.0, OrderType.PASSIVE_LIMIT)
        },
        stopLogic = StopLogic(hardStopPrice = 90.0),
        targets = emptyList(),
        invalidationScore = 5,
        topReasons = emptyList(),
        status = CampaignState.STAGED,
        createdAt = now
    )

    private fun frame(allowNewStaging: Boolean = false) = ProactiveLoopFrame(
        nowMillis = now,
        asset = asset(),
        levelMap = levelMap(),
        paths = emptyList(),
        decision = AlphaPulseDecision(DecisionState.LONG, 0.80),
        evidence = evidence(),
        campaigns = listOf(campaign()),
        envelope = RiskEnvelope(),
        venueHealth = VenueHealth(),
        mode = ExecutionMode.PAPER,
        oneMinuteCandleClosed = true,
        fiveMinuteCandleClosed = true,
        allowNewStaging = allowNewStaging
    )

    @Test
    fun `given a paper board hard invalidates when the cycle runs then a cancellation latency is measured`() {
        // The loop pass starts at 1_000ms and finishes at 1_040ms.
        val ticks = ArrayDeque(listOf(1_000L, 1_040L))
        val runner = ProactiveCycleRunner(clock = { ticks.removeFirst() })

        val result = runner.run(frame(), paperMode = true)

        val board = result.campaigns.single { it.id == "SOLUSDT_SUPPORT_LONG" }
        assertEquals(CampaignState.CANCELLED, board.status)
        assertEquals(40L, runner.evidence().cancellationLatencyMsP95)
    }

    @Test
    fun `given a live or shadow cycle when a board invalidates then no paper evidence is fabricated`() {
        val ticks = ArrayDeque(listOf(1_000L, 1_500L))
        val runner = ProactiveCycleRunner(clock = { ticks.removeFirst() })

        runner.run(frame(), paperMode = false)

        assertEquals(
            PaperTrialEvidenceTracker.NO_LATENCY_SAMPLE,
            runner.evidence().cancellationLatencyMsP95
        )
    }

    @Test
    fun `given the base app plans its own boards when the cycle requests no staging then the loop adds nothing`() {
        val runner = ProactiveCycleRunner()
        val input = frame(allowNewStaging = false)

        val result = runner.run(input, paperMode = true)

        // The single existing board may be cancelled by invalidation, but the loop
        // must not append newly planned campaigns the UI did not gate itself.
        assertEquals(1, result.campaigns.count { it.id == "SOLUSDT_SUPPORT_LONG" })
        assertTrue(result.stagedCampaigns.isEmpty())
    }

    @Test
    fun `given accumulated paper evidence when its verdict is passed to the staging gate then scaled live routes the board`() {
        val runner = ProactiveCycleRunner()
        repeat(100) { runner.recordCompletedPaperTrade() }
        repeat(100) { runner.recordCancellationLatency(60L) }
        repeat(100) { runner.recordMinimizeEvent(warnedAndWiped = true) }

        val verdict = runner.preLaunchVerdict()
        assertTrue(verdict.unlocked)

        val gated = ExecutionEngine(RiskEngine()).gateStaging(
            listOf(campaign()),
            ExecutionMode.SCALED_LIVE,
            RiskEnvelope(),
            VenueHealth(),
            hasActiveSession = true,
            preLaunchAcceptance = verdict
        )

        assertEquals(CampaignState.STAGED, gated.single().campaign.status)
        assertTrue(gated.single().note?.contains("full board routed") == true)
    }

    @Test
    fun `given no paper evidence when its verdict reaches the staging gate then scaled live holds the board as a plan`() {
        val runner = ProactiveCycleRunner()

        val gated = ExecutionEngine(RiskEngine()).gateStaging(
            listOf(campaign()),
            ExecutionMode.SCALED_LIVE,
            RiskEnvelope(),
            VenueHealth(),
            hasActiveSession = true,
            preLaunchAcceptance = runner.preLaunchVerdict()
        )

        assertEquals(CampaignState.PLANNED, gated.single().campaign.status)
        assertNotNull(gated.single().note)
        assertTrue(gated.single().note!!.contains("Scaled Live locked"))
    }
}
