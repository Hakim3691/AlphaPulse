package com.aistudio.alphapulse.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.local.SharedPreferencesPaperTrialEvidenceStore
import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.domain.campaign.StagingComponent
import com.aistudio.alphapulse.engine.proactive.ProactiveEvidenceRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §38 / C-49 — Scaled Live unlocks on the shipped auto-trader surface.
 *
 * `TraderHostActivity` (the `:proactive_trader` dynamic feature reachable from the
 * app's trader tab) mounts [MainViewModel], which owns the paper-trade loop and the
 * single [com.aistudio.alphapulse.engine.ExecutionEngine.gateStaging] call. These
 * scenarios construct that same view model, not a test-only harness: evidence is
 * written through the process store, the process tracker is dropped to simulate a
 * relaunch, and the reloaded verdict must be the value the shipped staging call acts on.
 *
 * Spec Ref: Phase 2 §38
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShippedScaledLiveSurfaceBddSpec {

    private lateinit var application: Application
    private lateinit var store: SharedPreferencesPaperTrialEvidenceStore

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        ProactiveEvidenceRuntime.reset()
        store = SharedPreferencesPaperTrialEvidenceStore(application)
        store.clear()
    }

    @After
    fun tearDown() {
        ProactiveEvidenceRuntime.reset()
        store.clear()
    }

    private fun campaign() = Campaign(
        id = "SOLUSDT_SUPPORT_LONG",
        asset = "SOLUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 80.0,
        sizeBudgetUsd = 2_000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(LadderSlice(0, 100.0, 1.0, 500.0, OrderType.PASSIVE_LIMIT)),
        stopLogic = StopLogic(hardStopPrice = 90.0),
        targets = emptyList(),
        invalidationScore = 5,
        topReasons = emptyList(),
        status = CampaignState.STAGED
    )

    /** A prior paper-trading session: 100 clean trades, latency, intercepts, base view. */
    private fun seedCompletedPaperCampaign() {
        val runner = ProactiveEvidenceRuntime.runner(store)
        repeat(100) { runner.recordCompletedPaperTrade() }
        repeat(100) { runner.recordCancellationLatency(42L) }
        repeat(100) { runner.recordMinimizeEvent(warnedAndWiped = true) }
        runner.recordBaseViewObservation(regressionDetected = false)

        // Process restart: the in-memory tracker is gone; only the store survives.
        ProactiveEvidenceRuntime.reset()
    }

    private fun shippedViewModel(): MainViewModel = MainViewModel(application).apply {
        startApiSession("surface-test-key", "surface-test-secret")
        setExecutionMode(ExecutionMode.SCALED_LIVE)
    }

    @Test
    fun `given evidence persisted before relaunch when the shipped view model reloads it then scaled live is granted by its own staging gate`() {
        seedCompletedPaperCampaign()

        val viewModel = shippedViewModel()
        val verdict = viewModel.preLaunchVerdict()

        assertEquals(ExecutionMode.SCALED_LIVE, viewModel.uiState.value.executionMode)
        assertTrue("The relaunched shipped surface must reload the campaign", verdict.unlocked)
        assertTrue(verdict.failures.isEmpty())
        assertEquals(
            "The verdict handed to gateStaging is the reloaded evidence's verdict",
            verdict,
            viewModel.preLaunchVerdict()
        )

        val reloaded = ProactiveEvidenceRuntime.runner(store).evidence()
        assertEquals(100, reloaded.consecutivePaperTrades)
        assertEquals(100, reloaded.cancellationSamples)
        assertEquals(42L, reloaded.cancellationLatencyMsP95)
        assertEquals(100, reloaded.minimizeEventsWarnedAndWiped)
        assertTrue(reloaded.baseViewObserved)

        val gated = viewModel.stageCampaigns(listOf(campaign()))

        // C-54: §38 is satisfied, but the shipped staging call also enforces §22's four
        // components, and a freshly relaunched process has observed none of them. The
        // board must therefore stay a visible plan instead of arming resting slices.
        assertEquals(CampaignState.PLANNED, gated.single().status)
        assertTrue(gated.single().entryLadder.none { it.isResting })
        val precondition = viewModel.stagingPreconditionFor("SOLUSDT")
        assertFalse(precondition.canStage)
        assertEquals(StagingComponent.entries, precondition.unmet)
    }

    @Test
    fun `given a corrupted evidence store when the shipped view model reloads it then the verdict is unmet and the gate stays locked without crashing`() {
        store.write("corrupt-evidence-from-an-interrupted-write")

        val viewModel = shippedViewModel()
        val verdict = viewModel.preLaunchVerdict()

        assertFalse("Corrupt evidence must never unlock Scaled Live", verdict.unlocked)
        assertTrue(verdict.failures.isNotEmpty())

        val gated = viewModel.stageCampaigns(listOf(campaign()))

        assertEquals(CampaignState.PLANNED, gated.single().status)
    }
}
