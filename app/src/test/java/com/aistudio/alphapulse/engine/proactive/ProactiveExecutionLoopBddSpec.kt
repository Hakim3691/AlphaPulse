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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §31 / Contract C-44 — the event-driven automation loop.
 *
 * Spec Ref: Phase 2 §31, §25, §28, §29, §38
 */
class ProactiveExecutionLoopBddSpec {

    private val now = 1_000_000L

    private fun asset(
        lastPrice: Double = 100.0,
        priceChange24h: Double = 0.5,
        spreadPct: Double = 0.04
    ) = CryptoAsset(
        symbol = "SOLUSDT",
        baseAsset = "SOL",
        lastPrice = lastPrice,
        priceChange24h = priceChange24h,
        quoteVolume24h = 120_000_000.0,
        orderBookSpreadPct = spreadPct,
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

    private fun levelMap(): LevelMap = LevelMap(
        asset = "SOLUSDT",
        zones = listOf(
            LevelZone("s", ZoneType.HTF_SUPPORT, 99.0, 101.0, "4h", 80, 1, 70),
            LevelZone("r", ZoneType.HTF_RESISTANCE, 105.0, 107.0, "4h", 78, 1, 68),
            LevelZone("p", ZoneType.LTF_PIVOT, 100.0, 100.5, "15m", 70, 2, 60),
            LevelZone("rc", ZoneType.RECLAIM_ZONE, 102.0, 103.0, "1h", 65, 1, 55),
            LevelZone("bd", ZoneType.BREAKDOWN_ZONE, 97.0, 98.0, "1h", 65, 1, 55)
        ),
        dominantPivot = 100.0,
        damageScore = 20,
        confidenceSummary = 75,
        timestamp = now
    )

    private fun evidence(
        acceptanceQuality: Double = 0.90,
        rejectionQuality: Double = 0.90,
        trapProbabilityPct: Int = 0,
        oiDelta1hPct: Double = 0.20,
        takerBuyRatio: Double = 0.50
    ) = EvidenceFrame(
        timestamp = now,
        asset = "SOLUSDT",
        oiDelta1hPct = oiDelta1hPct,
        fundingRateChangePct = 0.0,
        basisDeltaBps = 0.0,
        takerBuyRatio = takerBuyRatio,
        acceptanceQuality = acceptanceQuality,
        rejectionQuality = rejectionQuality,
        trapProbabilityPct = trapProbabilityPct,
        recentImpulseQuality = "normal"
    )

    private fun campaign(
        id: String,
        role: BoardRole = BoardRole.PRIMARY,
        family: CampaignFamily = CampaignFamily.SUPPORT_LONG,
        status: CampaignState = CampaignState.STAGED,
        ladderPrices: List<Double> = listOf(100.0, 99.0, 98.0),
        priority: Double = 80.0,
        createdAt: Long = now,
        filled: Boolean = false
    ) = Campaign(
        id = id,
        asset = "SOLUSDT",
        family = family,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = role,
        priorityScore = priority,
        sizeBudgetUsd = 2_000.0,
        sizeMultiplier = 1.0,
        entryLadder = ladderPrices.mapIndexed { index, price ->
            LadderSlice(
                sliceIndex = index,
                price = price,
                qty = 1.0,
                notionalUsd = 500.0,
                orderType = OrderType.PASSIVE_LIMIT,
                isFilled = filled,
                isResting = true
            )
        },
        stopLogic = StopLogic(hardStopPrice = 90.0),
        targets = emptyList(),
        invalidationScore = 5,
        topReasons = emptyList(),
        status = status,
        createdAt = createdAt
    )

    private fun frame(
        asset: CryptoAsset = asset(),
        evidence: EvidenceFrame = evidence(),
        decision: AlphaPulseDecision = AlphaPulseDecision(DecisionState.LONG, 0.80),
        campaigns: List<Campaign> = emptyList(),
        mode: ExecutionMode = ExecutionMode.PAPER,
        hasActiveSession: Boolean = false,
        correlation: Double = 0.0,
        oneMinuteClosed: Boolean = false,
        fiveMinuteClosed: Boolean = false,
        fifteenMinuteClosed: Boolean = false,
        preLaunch: PreLaunchVerdict? = null,
        envelope: RiskEnvelope = RiskEnvelope(),
        venueHealth: VenueHealth = VenueHealth()
    ) = ProactiveLoopFrame(
        nowMillis = now,
        asset = asset,
        levelMap = levelMap(),
        paths = emptyList(),
        decision = decision,
        evidence = evidence,
        campaigns = campaigns,
        envelope = envelope,
        venueHealth = venueHealth,
        mode = mode,
        hasActiveSession = hasActiveSession,
        marketCorrelation = correlation,
        oneMinuteCandleClosed = oneMinuteClosed,
        fiveMinuteCandleClosed = fiveMinuteClosed,
        fifteenMinuteCandleClosed = fifteenMinuteClosed,
        preLaunch = preLaunch
    )

    @Test
    fun `given an AlphaPulse NEUTRAL decision when the loop runs then no automated staging is planned or routed`() {
        val loop = ProactiveExecutionLoop()

        val result = loop.step(
            frame(
                decision = AlphaPulseDecision(DecisionState.NEUTRAL, 0.42),
                fiveMinuteClosed = true
            )
        )

        assertTrue(result.stagingSuppressed)
        assertTrue(result.suppressionReason!!.contains("NEUTRAL"))
        assertTrue(result.stagedCampaigns.isEmpty())
        assertTrue(result.campaigns.isEmpty())
    }

    @Test
    fun `given acceptance trap and flow evidence scoring above 70 when the loop runs then the board is flattened and locked out for 30 minutes`() {
        val loop = ProactiveExecutionLoop()
        val board = campaign(id = "SOLUSDT_SUPPORT_LONG")

        val result = loop.step(
            frame(
                asset = asset(lastPrice = 95.0, priceChange24h = -3.0),
                evidence = evidence(
                    trapProbabilityPct = 80,
                    oiDelta1hPct = 1.20,
                    takerBuyRatio = 0.40
                ),
                campaigns = listOf(board)
            )
        )

        val flattened = result.campaigns.single { it.id == board.id }
        assertEquals(90, flattened.invalidationScore)
        assertEquals(CampaignState.CANCELLED, flattened.status)
        assertEquals(now + 30L * 60_000L, flattened.cooldownUntilTs)
        assertTrue(flattened.entryLadder.none { it.isResting })
        assertTrue(result.actions.contains(MonitoringAction.RECOMPUTE_INVALIDATION_SCORE))
    }

    @Test
    fun `given soft invalidation between 45 and 70 when the loop runs then unfilled slices are cancelled without a hard flatten`() {
        val loop = ProactiveExecutionLoop()
        val board = campaign(id = "SOLUSDT_SUPPORT_LONG")

        val result = loop.step(
            frame(
                asset = asset(lastPrice = 95.0, priceChange24h = -2.0),
                evidence = evidence(oiDelta1hPct = 1.20, takerBuyRatio = 0.40),
                campaigns = listOf(board)
            )
        )

        val soft = result.campaigns.single { it.id == board.id }
        assertEquals(60, soft.invalidationScore)
        assertEquals(CampaignState.CANCELLED, soft.status)
        assertEquals(0L, soft.cooldownUntilTs)
        assertTrue(soft.entryLadder.none { it.isResting })
    }

    @Test
    fun `given market correlation above 0_85 on the structural cadence when the loop runs then secondary and defensive boards are cancelled first`() {
        val loop = ProactiveExecutionLoop()
        val primary = campaign("SOL_PRIMARY", BoardRole.PRIMARY, priority = 92.0)
        val secondary = campaign(
            "SOL_SECONDARY",
            BoardRole.SECONDARY,
            family = CampaignFamily.RESISTANCE_SHORT,
            ladderPrices = listOf(101.0, 100.0, 99.0),
            priority = 70.0
        )
        val defensive = campaign(
            "SOL_DEFENSIVE",
            BoardRole.DEFENSIVE,
            priority = 30.0
        )

        val result = loop.step(
            frame(
                campaigns = listOf(primary, secondary, defensive),
                correlation = 0.91,
                fiveMinuteClosed = true
            )
        )

        val byId = result.campaigns.associateBy { it.id }
        assertEquals(CampaignState.STAGED, byId.getValue("SOL_PRIMARY").status)
        assertEquals(CampaignState.CANCELLED, byId.getValue("SOL_SECONDARY").status)
        assertEquals(CampaignState.CANCELLED, byId.getValue("SOL_DEFENSIVE").status)
        assertTrue(result.actions.contains(MonitoringAction.SUPPRESS_SECONDARY_OR_COOLDOWN))
    }

    @Test
    fun `given fast telemetry already ran when the next delta arrives within the cadence then the score is not recomputed`() {
        val loop = ProactiveExecutionLoop()
        val first = loop.step(frame())
        assertTrue(first.cadences.contains(MonitoringCadence.FAST_TELEMETRY))

        val second = loop.step(frame().copy(nowMillis = now + 400L))

        assertEquals(listOf(MonitoringCadence.PER_TICK), second.cadences)
        assertFalse(second.actions.contains(MonitoringAction.RECOMPUTE_INVALIDATION_SCORE))
    }

    @Test
    fun `given scaled live without the section 38 verdict when the loop runs then boards stay planned until the gate passes`() {
        val locked = ProactiveExecutionLoop().step(
            frame(
                mode = ExecutionMode.SCALED_LIVE,
                hasActiveSession = true,
                fiveMinuteClosed = true,
                preLaunch = PreLaunchVerdict(unlocked = false, failures = listOf("paper trades 99/100"))
            )
        )
        assertTrue(
            "Scaled live must route nothing before the pre-launch evidence passes",
            locked.stagedCampaigns.isEmpty()
        )

        val unlocked = ProactiveExecutionLoop().step(
            frame(
                mode = ExecutionMode.SCALED_LIVE,
                hasActiveSession = true,
                fiveMinuteClosed = true,
                preLaunch = PreLaunchVerdict(unlocked = true, failures = emptyList())
            )
        )
        assertNotEquals(0, unlocked.stagedCampaigns.size)
        assertTrue(
            unlocked.stagedCampaigns.all { it.status == CampaignState.STAGED }
        )
    }
}
