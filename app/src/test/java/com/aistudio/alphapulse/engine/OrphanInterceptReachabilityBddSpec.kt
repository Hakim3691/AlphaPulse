package com.aistudio.alphapulse.engine

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.StopLogic
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 §3 / Phase 2 §38 — the orphan intercept must be reachable from the app people
 * install, not merely unit-tested.
 *
 * `MainViewModel.onAppBackgrounded()` implemented the whole §3 policy, but nothing in the
 * shipped graph ever called it: `MainActivity` mounts `AlphaPulseViewModel`, and the
 * ProactiveMS host that does mount `MainViewModel` had no lifecycle observer and never
 * composed `OrphanedOrderWarningDialog`. A perfect rule nobody can reach protects nobody.
 *
 * This spec drives the observer that fixes that, and asserts the host wires it, so the
 * call cannot quietly disappear again.
 *
 * Spec Ref: Phase 1 §3 Orphaned Order OS Intercept, Phase 2 §38
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrphanInterceptReachabilityBddSpec {

    @Test
    fun `given the host is going to the background when the observer receives ON_STOP then the view model is told foreground was lost`() {
        val owner = FakeOwner()
        var foregroundLost = 0
        owner.lifecycle.addObserver(OrphanInterceptLifecycle { foregroundLost++ })

        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(
            "Leaving the foreground must run the §3 policy exactly once",
            1,
            foregroundLost
        )
    }

    @Test
    fun `given the app is only paused when the observer receives ON_PAUSE then keys are not wiped and the operator is not interrupted`() {
        val owner = FakeOwner()
        var foregroundLost = 0
        owner.lifecycle.addObserver(OrphanInterceptLifecycle { foregroundLost++ })

        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)

        assertEquals(
            "A transient pause (permission sheet, dialog) is not the background",
            0,
            foregroundLost
        )
    }

    @Test
    fun `given a live session with a resting board when the app is backgrounded then the intercept is mandatory`() {
        val decision = OrphanInterceptPolicy.decide(
            hasActiveSession = true,
            hasRestingOrders = OrphanInterceptPolicy.hasRestingOrLiveOrders(
                listOf(stagedCampaign())
            )
        )

        assertEquals(OrphanInterceptDecision.SHOW_INTERCEPT, decision)
    }

    @Test
    fun `given the shipped host when its source is read then it observes the lifecycle, calls the policy and shows the intercept`() {
        val host = repoFile(
            "proactive_trader/src/main/java/com/alphapulse/proactive/TraderHostActivity.kt"
        ).readText()

        assertTrue(
            "The ProactiveMS host must observe its lifecycle: it is the only screen that mounts MainViewModel",
            host.contains("OrphanInterceptLifecycle")
        )
        assertTrue(
            "The observer must reach the §3 policy on the mounted view model",
            host.contains("onAppBackgrounded")
        )
        assertTrue(
            "The §3 intercept dialog must be composed by the shipped host, not left unused",
            host.contains("OrphanInterceptDialog")
        )
    }

    private fun stagedCampaign() = Campaign(
        id = "campaign-1",
        asset = "BTCUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 0.82,
        sizeBudgetUsd = 1_000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(
            LadderSlice(
                sliceIndex = 0,
                price = 100.0,
                qty = 10.0,
                notionalUsd = 1_000.0,
                isResting = true
            )
        ),
        stopLogic = StopLogic(hardStopPrice = 90.0),
        targets = emptyList(),
        invalidationScore = 10,
        topReasons = emptyList(),
        status = CampaignState.STAGED
    )

    private class FakeOwner : LifecycleOwner {
        override val lifecycle: LifecycleRegistry = LifecycleRegistry(this)
    }

    /** Locates a repository file from whichever directory tests run in. */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
