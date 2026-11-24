package com.aistudio.alphapulse.domain.campaign

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
import com.aistudio.alphapulse.engine.ExecutionEngine
import com.aistudio.alphapulse.engine.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §22 / Contract C-54 — the four-component staging precondition.
 *
 * `AlphaPulse_Android_SPEC_V14-3.md` §22 keeps the LTF/MTF/HTF readiness framework and
 * adds four named components — `TSFM Ready`, `Neural LOB Ready`, `Liquidity Heatmap
 * Ready`, `Ensemble Ready` — each "based on actual validation availability". The staging
 * rule (C-19) is that a campaign may not leave `PLANNED` until readiness holds. This spec
 * pins the four components as one all-or-nothing precondition: every component must be
 * observed `READY`, a missing observation is `UNOBSERVED` (never a pass), an explicit
 * failure is `UNAVAILABLE`, and the refusal names each unmet component in a stable order
 * so the gate's note can say exactly which input is missing. The precondition does not
 * replace LTF/MTF/HTF or the cone hit-rate floor; it is additional.
 *
 * Spec Ref: AlphaPulse §22 Per-Horizon Readiness, ProactiveMS §22 Order Staging, C-19
 */
class StagingPreconditionBddSpec {

    /** Pinned clock: these scenarios measure their evidence now (C-61). */
    private val NOW = 1_800_000_000_000L

    @Test
    fun `given all four components observed ready when evaluated then staging is permitted and nothing is named`() {
        val verdict = StagingPrecondition.evaluate(
            mapOf(
                StagingComponent.TSFM to ComponentAvailability.READY,
                StagingComponent.NEURAL_LOB to ComponentAvailability.READY,
                StagingComponent.LIQUIDITY_HEATMAP to ComponentAvailability.READY,
                StagingComponent.ENSEMBLE to ComponentAvailability.READY
            )
        )

        assertTrue(verdict.canStage)
        assertTrue(verdict.unmet.isEmpty())
        assertTrue(verdict.reasons.isEmpty())
    }

    @Test
    fun `given each component in turn is missing when evaluated then every one of the four blocks staging`() {
        for (component in StagingComponent.entries) {
            val verdict = StagingPrecondition.evaluate(
                StagingComponent.entries.associateWith { other ->
                    if (other == component) ComponentAvailability.UNOBSERVED
                    else ComponentAvailability.READY
                }
            )

            assertFalse("$component must be load-bearing", verdict.canStage)
            assertEquals(listOf(component), verdict.unmet)
            assertTrue(
                "reason should name $component: ${verdict.reasons}",
                verdict.reasons.any { it.startsWith(component.name) }
            )
        }
    }

    @Test
    fun `given a component absent from the evidence map when evaluated then it is unobserved rather than ready`() {
        // Given: only the TSFM component was ever observed.
        val verdict = StagingPrecondition.evaluate(
            mapOf(StagingComponent.TSFM to ComponentAvailability.READY)
        )

        // Then: silence is a failure, never a pass.
        assertFalse(verdict.canStage)
        assertEquals(
            listOf(
                StagingComponent.NEURAL_LOB,
                StagingComponent.LIQUIDITY_HEATMAP,
                StagingComponent.ENSEMBLE
            ),
            verdict.unmet
        )
        assertEquals(
            listOf(
                "NEURAL_LOB_UNOBSERVED",
                "LIQUIDITY_HEATMAP_UNOBSERVED",
                "ENSEMBLE_UNOBSERVED"
            ),
            verdict.reasons
        )
    }

    @Test
    fun `given an explicitly unavailable component when evaluated then the reason distinguishes it from a missing observation`() {
        val verdict = StagingPrecondition.evaluate(
            mapOf(
                StagingComponent.TSFM to ComponentAvailability.UNAVAILABLE,
                StagingComponent.NEURAL_LOB to ComponentAvailability.READY,
                StagingComponent.LIQUIDITY_HEATMAP to ComponentAvailability.READY,
                StagingComponent.ENSEMBLE to ComponentAvailability.READY
            )
        )

        assertFalse(verdict.canStage)
        assertEquals(listOf("TSFM_UNAVAILABLE"), verdict.reasons)
        assertTrue(verdict.note().contains("TSFM_UNAVAILABLE"))
    }

    @Test
    fun `given the readiness gate with four components ready but no settled structure when read then the existing framework still refuses`() {
        val gate = CampaignReadinessGate()
        val telemetry = telemetry(
            settled1m = 0,
            settled5m = 0,
            settled15m = 0,
            settled30m = 0,
            settled1h = 0,
            components = allReady()
        )

        val result = gate.evaluate(telemetry, governingHorizons = listOf("5m"), nowMs = NOW)

        assertFalse(result.canArmOrders)
        assertTrue(result.rejectionReasons.contains("LTF_NOT_READY"))
    }

    @Test
    fun `given the readiness gate with the four components unmet when read then the gate names every missing component`() {
        val gate = CampaignReadinessGate()
        val telemetry = telemetry(components = emptyMap())

        val result = gate.evaluate(telemetry, governingHorizons = listOf("5m"), nowMs = NOW)

        assertFalse(result.canArmOrders)
        assertTrue(result.rejectionReasons.contains("TSFM_UNOBSERVED"))
        assertTrue(result.rejectionReasons.contains("NEURAL_LOB_UNOBSERVED"))
        assertTrue(result.rejectionReasons.contains("LIQUIDITY_HEATMAP_UNOBSERVED"))
        assertTrue(result.rejectionReasons.contains("ENSEMBLE_UNOBSERVED"))
    }

    @Test
    fun `given the readiness gate with all components and structure ready when read then the board may arm`() {
        val gate = CampaignReadinessGate()
        val telemetry = telemetry(components = allReady())

        val result = gate.evaluate(telemetry, governingHorizons = listOf("5m"), nowMs = NOW)

        assertTrue(result.rejectionReasons.toString(), result.canArmOrders)
        assertEquals(CampaignLifecycleState.STAGED, result.nextState)
    }

    @Test
    fun `given an unmet precondition when the execution gate stages a live board then the board is held planned with the missing components named`() {
        // Given: a paper-mode board that would otherwise rest, and an unmet precondition.
        val engine = ExecutionEngine(RiskEngine())
        val board = campaign()
        val blocked = StagingPrecondition.evaluate(emptyMap())

        // When: the real staging gate admits it.
        val gated = engine.gateStaging(
            campaigns = listOf(board),
            mode = ExecutionMode.PAPER,
            envelope = RiskEnvelope(),
            venueHealth = VenueHealth(),
            hasActiveSession = false,
            stagingPrecondition = blocked,
            preLaunchAcceptance = null
        ).single()

        // Then: nothing rests until the four components are observed.
        assertEquals(CampaignState.PLANNED, gated.campaign.status)
        assertTrue(gated.campaign.entryLadder.none { it.isResting })
        assertTrue("note should name the components: ${gated.note}", gated.note!!.contains("TSFM_UNOBSERVED"))
    }

    @Test
    fun `given the four components ready when the execution gate stages a paper board then it rests as before`() {
        val engine = ExecutionEngine(RiskEngine())
        val board = campaign()

        val gated = engine.gateStaging(
            campaigns = listOf(board),
            mode = ExecutionMode.PAPER,
            envelope = RiskEnvelope(),
            venueHealth = VenueHealth(),
            hasActiveSession = false,
            stagingPrecondition = StagingPrecondition.evaluate(allReady()),
            preLaunchAcceptance = null
        ).single()

        assertEquals(CampaignState.STAGED, gated.campaign.status)
        assertTrue(gated.campaign.entryLadder.any { it.isResting })
    }

    // ------------------------------------------------- from real observations

    @Test
    fun `given a tri factor ladder a two sided book a fresh heatmap and three horizons when derived then only the tsfm component is unmet`() {
        val now = 1_700_000_000_000L
        val verdict = StagingPrecondition.fromObservations(
            observations = StagingObservations(
                forecastModelTypes = listOf("Tri-Factor"),
                forecastHorizons = setOf("5m", "15m", "1h"),
                bookSides = 2,
                heatmapObservedAtMs = now - 60_000L
            ),
            nowMs = now
        )

        assertFalse(verdict.canStage)
        assertEquals(listOf(StagingComponent.TSFM), verdict.unmet)
        assertEquals(listOf("TSFM_UNOBSERVED"), verdict.reasons)
    }

    @Test
    fun `given a quad factor ladder when derived then the tsfm component is the one carrying it`() {
        val now = 1_700_000_000_000L
        val verdict = StagingPrecondition.fromObservations(
            observations = StagingObservations(
                forecastModelTypes = listOf("Quad-Factor"),
                forecastHorizons = setOf("5m", "15m", "1h"),
                bookSides = 2,
                heatmapObservedAtMs = now - 60_000L
            ),
            nowMs = now
        )

        assertTrue(verdict.reasons.toString(), verdict.canStage)
    }

    @Test
    fun `given a heatmap older than its window and a one sided book when derived then both are unavailable rather than unobserved`() {
        val now = 1_700_000_000_000L
        val verdict = StagingPrecondition.fromObservations(
            observations = StagingObservations(
                forecastModelTypes = listOf("Penta-Factor"),
                forecastHorizons = setOf("5m", "15m", "1h"),
                bookSides = 1,
                heatmapObservedAtMs = now - 25L * 60L * 60L * 1000L
            ),
            nowMs = now
        )

        assertFalse(verdict.canStage)
        assertEquals(listOf("NEURAL_LOB_UNOBSERVED", "LIQUIDITY_HEATMAP_UNAVAILABLE"), verdict.reasons)
    }

    @Test
    fun `given no heatmap row and only two horizons when derived then both are unobserved`() {
        val now = 1_700_000_000_000L
        val verdict = StagingPrecondition.fromObservations(
            observations = StagingObservations(
                forecastModelTypes = listOf("Penta-Factor"),
                forecastHorizons = setOf("5m", "15m"),
                bookSides = 2,
                heatmapObservedAtMs = null
            ),
            nowMs = now
        )

        assertEquals(
            listOf("LIQUIDITY_HEATMAP_UNOBSERVED", "ENSEMBLE_UNOBSERVED"),
            verdict.reasons
        )
    }

    private fun allReady(): Map<StagingComponent, ComponentAvailability> =
        StagingComponent.entries.associateWith { ComponentAvailability.READY }

    private fun telemetry(
        settled1m: Int = 2,
        settled5m: Int = 1,
        settled15m: Int = 2,
        settled30m: Int = 2,
        settled1h: Int = 1,
        components: Map<StagingComponent, ComponentAvailability>
    ) = AlphaPulseReadinessTelemetry(
        settled1mCount = settled1m,
        settled5mCount = settled5m,
        settled15mCount = settled15m,
        settled30mCount = settled30m,
        settled1hCount = settled1h,
        readyEnsembleModels = 5,
        totalEnsembleModels = 5,
        readyTsfmDockModels = 2,
        requiredTsfmDockModels = 2,
        horizonConeHitRatePct = mapOf("5m" to 92.0),
        horizonConeMeasuredAt = mapOf("5m" to NOW),
        componentAvailability = components
    )

    private fun campaign() = Campaign(
        id = "SOLUSDT_SUPPORT_LONG",
        asset = "SOLUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BALANCE,
        role = BoardRole.PRIMARY,
        priorityScore = 40.0,
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
}
