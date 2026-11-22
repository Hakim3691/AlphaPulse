package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.data.model.TakeProfitTarget
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.domain.campaign.ComponentAvailability
import com.aistudio.alphapulse.domain.campaign.StagingComponent
import com.aistudio.alphapulse.domain.campaign.StagingPrecondition
import com.aistudio.alphapulse.engine.ExecutionEngine
import com.aistudio.alphapulse.engine.RiskEngine
import com.aistudio.alphapulse.engine.proactive.ProactiveLoopFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §22 / C-54 — what the board card actually renders when staging is held.
 *
 * The four-component precondition was only ever asserted as a string on
 * `ExecutionEngine.GatedCampaign.note`, so a board could have been held with the reason
 * dropped on the floor by the card and every test would still pass. These scenarios
 * render the real `CampaignCard` under Robolectric and read the note off the composable:
 * an unmet component is named in words a trader can act on, an all-ready board shows no
 * refusal at all, and a cold loop frame (no observations yet) cannot stage and says so.
 * The note is also measured, not assumed: its text layout must not overflow the card.
 *
 * Spec Ref: AlphaPulse §22 Per-Horizon Readiness, C-54
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
// Native graphics measure text with real font metrics, so "does it fit the card" is a
// real measurement instead of the legacy stub-font approximation.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CampaignCardStagingGateBddSpec {

    @get:Rule
    val compose = createComposeRule()

    private fun board() = Campaign(
        id = "SOLUSDT_SUPPORT_LONG",
        asset = "SOLUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BALANCE,
        role = BoardRole.PRIMARY,
        priorityScore = 62.0,
        sizeBudgetUsd = 1_000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(
            LadderSlice(1, 100.0, 5.0, 500.0, OrderType.PASSIVE_LIMIT),
            LadderSlice(2, 99.0, 5.0, 500.0, OrderType.PASSIVE_LIMIT)
        ),
        stopLogic = StopLogic(hardStopPrice = 97.0),
        targets = listOf(TakeProfitTarget("Target", 104.0, 100)),
        invalidationScore = 10,
        topReasons = emptyList(),
        status = CampaignState.STAGED
    )

    private fun gate(precondition: com.aistudio.alphapulse.domain.campaign.StagingPreconditionVerdict) =
        ExecutionEngine(RiskEngine()).gateStaging(
            campaigns = listOf(board()),
            mode = ExecutionMode.PAPER,
            envelope = RiskEnvelope(),
            venueHealth = VenueHealth(),
            hasActiveSession = false,
            stagingPrecondition = precondition,
            preLaunchAcceptance = null
        ).single()

    private fun render(campaign: Campaign) {
        compose.setContent {
            CampaignCard(
                campaign = campaign,
                isSelected = false,
                onSelect = {},
                onOpenOverride = {},
                onSoftCancel = {},
                onHardFlatten = {}
            )
        }
    }

    /** Reads the note's own text layout, so "it fits" is measured rather than assumed. */
    private fun assertNoteFitsOnTheCard(id: String, minimumLines: Int = 1) {
        val node = compose.onNodeWithTag("gate_note_text_$id", useUnmergedTree = true)
            .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("The gate note must expose its text layout", layouts.isNotEmpty())
        val layout = layouts.first()
        val detail = "(size=${layout.size}, maxWidth=${layout.layoutInput.constraints.maxWidth}, " +
            "lines=${layout.lineCount}, text=${layout.layoutInput.text.text})"
        assertTrue(
            "The gate note must stay inside the card's width $detail",
            layout.size.width <= layout.layoutInput.constraints.maxWidth
        )
        assertFalse(
            "The gate note must not be ellipsized $detail",
            layout.isLineEllipsized(layout.lineCount - 1)
        )
        assertTrue(
            "The note must have room for $minimumLines line(s), not be squeezed $detail",
            layout.lineCount >= minimumLines
        )
    }

    @Test
    fun `given an unmet stage precondition when the board card renders then the refusal names the missing components in words`() {
        // Given: nothing was observed for this symbol, so all four components are unmet.
        val gated = gate(StagingPrecondition.unobserved())

        // When: the card draws that board.
        render(gated.campaign)

        // Then: the refusal is visible on the card and names each component.
        compose.onNodeWithTag("gate_note_SOLUSDT_SUPPORT_LONG", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("TSFM Ready not observed", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Neural LOB Ready not observed", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Liquidity Heatmap Ready not observed", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Ensemble Ready not observed", substring = true).assertCountEquals(1)
        // Raw enum text belongs in the log, not on the board.
        compose.onAllNodesWithText("UNOBSERVED", substring = true).assertCountEquals(0)
        // Four named components have to wrap; two lines is the floor, not a wish.
        assertNoteFitsOnTheCard("SOLUSDT_SUPPORT_LONG", minimumLines = 2)
    }

    @Test
    fun `given a measured but stale component when the board card renders then the note says unavailable rather than not observed`() {
        val gated = gate(
            StagingPrecondition.evaluate(
                mapOf(
                    StagingComponent.TSFM to ComponentAvailability.READY,
                    StagingComponent.NEURAL_LOB to ComponentAvailability.READY,
                    StagingComponent.LIQUIDITY_HEATMAP to ComponentAvailability.UNAVAILABLE,
                    StagingComponent.ENSEMBLE to ComponentAvailability.READY
                )
            )
        )

        render(gated.campaign)

        compose.onAllNodesWithText("Liquidity Heatmap Ready unavailable", substring = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("not observed", substring = true).assertCountEquals(0)
        assertNoteFitsOnTheCard("SOLUSDT_SUPPORT_LONG")
    }

    @Test
    fun `given all four components observed when the fully routed board card renders then no refusal strip is drawn`() {
        val gated = gate(
            StagingPrecondition.evaluate(
                StagingComponent.entries.associateWith { ComponentAvailability.READY }
            )
        )

        assertEquals(CampaignState.STAGED, gated.campaign.status)
        render(gated.campaign)

        compose.onNodeWithTag("gate_note_SOLUSDT_SUPPORT_LONG", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onAllNodesWithText("not observed", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("unavailable", substring = true).assertCountEquals(0)
    }

    @Test
    fun `given a cold loop frame with no observations when its board is gated and rendered then it cannot stage and the card says why`() {
        // Given: a loop frame built with nothing observed — the shipped default.
        val coldFrame = ProactiveLoopFrame(
            nowMillis = 1_000L,
            asset = CryptoAsset(
                symbol = "SOLUSDT",
                baseAsset = "SOL",
                lastPrice = 100.0,
                priceChange24h = 0.5,
                quoteVolume24h = 120_000_000.0,
                orderBookSpreadPct = 0.04,
                fundingRatePct = 0.01,
                predictedFundingPct = 0.01,
                openInterestUsd = 50_000_000.0,
                basisPremiumPct = 0.02,
                atr5m = 0.4,
                atr1h = 0.9,
                atr4h = 1.8,
                regime = MarketRegime.BALANCE,
                tacticalBias = 0,
                strategicBias = 0,
                primaryPainPath = "NONE"
            ),
            levelMap = LevelMap(
                asset = "SOLUSDT",
                zones = emptyList(),
                dominantPivot = 100.0,
                damageScore = 0,
                confidenceSummary = 0,
                timestamp = 1_000L
            ),
            paths = emptyList(),
            evidence = EvidenceFrame(
                timestamp = 1_000L,
                asset = "SOLUSDT",
                oiDelta1hPct = 0.0,
                fundingRateChangePct = 0.0,
                basisDeltaBps = 0.0,
                takerBuyRatio = 0.5,
                acceptanceQuality = 0.5,
                rejectionQuality = 0.5,
                trapProbabilityPct = 0,
                recentImpulseQuality = "NONE"
            ),
            campaigns = emptyList()
        )
        assertEquals(
            "A frame nobody has filled in must carry no readiness evidence",
            StagingPrecondition.unobserved(),
            coldFrame.stagingPrecondition
        )

        // When: that frame's verdict reaches the real gate and the card.
        val gated = gate(coldFrame.stagingPrecondition)
        render(gated.campaign)

        // Then: nothing rests, and the card explains the hold.
        assertEquals(CampaignState.PLANNED, gated.campaign.status)
        assertTrue(gated.campaign.entryLadder.none { it.isResting })
        compose.onNodeWithTag("gate_note_SOLUSDT_SUPPORT_LONG", useUnmergedTree = true)
            .assertExists()
        compose.onAllNodesWithText("TSFM Ready not observed", substring = true).assertCountEquals(1)
        assertNoteFitsOnTheCard("SOLUSDT_SUPPORT_LONG", minimumLines = 2)
    }
}
