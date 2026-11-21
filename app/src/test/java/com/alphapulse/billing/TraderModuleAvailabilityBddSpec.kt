package com.alphapulse.billing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alphapulse.contract.ModuleInstallState
import com.aistudio.alphapulse.ui.TraderNavigation
import com.aistudio.alphapulse.ui.TraderTabAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Phase 1 D5 / §7 — the VIP gate must describe the device it is running on.
 *
 * The emulated Play Billing / SplitInstall lifecycle is the only gate in front of
 * the `:proactive_trader` surface. A published base APK does not contain the
 * on-demand module: its manifest entry is merged in, so the component resolves,
 * but no dex holds `com.alphapulse.proactive.TraderHostActivity`. The lifecycle
 * therefore used to end at `INSTALLED` on a module that was never delivered, and
 * the tab then started an activity whose class does not exist — the launch fails
 * inside the app process, not inside the `startActivity` call the emulation
 * guarded, so the user saw a dead VIP tab and no explanation.
 *
 * These scenarios pin the honest contract: `INSTALLED` means the host really is
 * on the device, anything else leaves the gate closed with a visible reason, and
 * no launch is attempted against a missing component.
 *
 * Spec Ref: Phase 1 §6 Offline Simulated Entitlement & Dynamic Install Engine,
 * §7 Post-Unlock Bottom Tab Navigation, D5, C-33
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraderModuleAvailabilityBddSpec {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(SimulatedTraderManager.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowToast.reset()
    }

    @Test
    fun `given this APK ships no VIP module when the emulated download completes then the tab is unavailable and nothing is launched`() {
        // The published base APK carries no feature dex; the unit-test classpath is
        // the same shape, so the default availability check sees the real answer.
        assertFalse(
            "The base APK must not carry the on-demand feature host",
            runCatching {
                Class.forName(SimulatedTraderManager.TRADER_ACTIVITY, false, javaClass.classLoader)
            }.isSuccess
        )

        val scope = TestScope(UnconfinedTestDispatcher())
        val manager = SimulatedTraderManager(application, scope)

        manager.startDynamicDownloadSimulation()
        scope.advanceUntilIdle()

        assertEquals(100, manager.downloadProgress.value)
        assertEquals(ModuleInstallState.ERROR, manager.installState.value)
        assertEquals(
            "The bar must not offer a live VIP tab for a module that is not on the device",
            "VIP unavailable",
            TraderNavigation.label(manager.installState.value, manager.downloadProgress.value)
        )
        assertEquals(TraderTabAction.WAITING, TraderNavigation.action(manager.installState.value))

        manager.launchTraderDashboard(application)

        assertNull(
            "A missing feature host must never be started by component name",
            shadowOf(application).nextStartedActivity
        )
    }

    @Test
    fun `given a restored entitlement without the module when the manager is created then it does not report installed`() {
        application.getSharedPreferences(SimulatedTraderManager.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SimulatedTraderManager.KEY_UNLOCKED, true).commit()

        val manager = SimulatedTraderManager(application, TestScope()) { false }

        assertTrue("The entitlement itself is still restored", manager.isEntitled.value)
        assertEquals(
            "Entitlement is not the same thing as a delivered module",
            ModuleInstallState.ERROR,
            manager.installState.value
        )
    }

    @Test
    fun `given the module is really present when the download completes then the tab launches the feature host`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val manager = SimulatedTraderManager(application, scope) { true }

        manager.startDynamicDownloadSimulation()
        scope.advanceUntilIdle()

        assertEquals(ModuleInstallState.INSTALLED, manager.installState.value)
        assertEquals("VIP Features", TraderNavigation.label(manager.installState.value, 100))
        assertEquals(TraderTabAction.LAUNCH, TraderNavigation.action(manager.installState.value))

        manager.launchTraderDashboard(application)

        val started = shadowOf(application).nextStartedActivity
        assertNotNull("The installed module must be launched", started)
        assertEquals(
            SimulatedTraderManager.TRADER_ACTIVITY,
            started.component?.className
        )
    }

    @Test
    fun `given the module disappears after install when the tab is tapped then nothing is launched and the user is told why`() {
        var present = true
        val scope = TestScope(UnconfinedTestDispatcher())
        val manager = SimulatedTraderManager(application, scope) { present }

        manager.startDynamicDownloadSimulation()
        scope.advanceUntilIdle()
        assertEquals(ModuleInstallState.INSTALLED, manager.installState.value)

        present = false
        manager.launchTraderDashboard(application)

        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(ModuleInstallState.ERROR, manager.installState.value)
        assertEquals(
            SimulatedTraderManager.MODULE_UNAVAILABLE_MESSAGE,
            ShadowToast.getTextOfLatestToast()
        )
    }
}
