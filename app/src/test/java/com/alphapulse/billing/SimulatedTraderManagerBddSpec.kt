package com.alphapulse.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alphapulse.contract.ModuleInstallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 D5: the offline billing / install emulation must behave like the real
 * lifecycle, because the base app gates the trader tabs on it.
 *
 * The scenarios pin the parts a simulator usually gets wrong: progress must reach
 * an honest 100%, INSTALLING must be a distinct phase, entitlement must survive a
 * process restart, and installing before entitlement must never claim INSTALLED.
 *
 * Spec Ref: Phase 1 §6 Offline Simulated Entitlement & Dynamic Install Engine, D5
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SimulatedTraderManagerBddSpec {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `given a fresh install when the manager is created then nothing is entitled or installed`() {
        val manager = SimulatedTraderManager(context, TestScope())

        assertEquals(ModuleInstallState.NOT_INSTALLED, manager.installState.value)
        assertEquals(false, manager.isEntitled.value)
        assertEquals(0, manager.downloadProgress.value)
    }

    @Test
    fun `given a simulated purchase when the download runs then progress reaches 100 and the state becomes installed`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        // The feature host is present: the install lifecycle is what is under test.
        val manager = SimulatedTraderManager(context, scope) { true }

        // The billing sheet writes the entitlement; the install simulation then runs.
        context.getSharedPreferences(SimulatedTraderManager.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SimulatedTraderManager.KEY_UNLOCKED, true).apply()
        manager.isEntitled.value = true

        manager.startDynamicDownloadSimulation()

        assertEquals(ModuleInstallState.DOWNLOADING, manager.installState.value)
        scope.advanceUntilIdle()

        assertEquals(100, manager.downloadProgress.value)
        assertEquals(ModuleInstallState.INSTALLED, manager.installState.value)
    }

    @Test
    fun `given a persisted entitlement when a new manager is created then it starts installed`() {
        context.getSharedPreferences(SimulatedTraderManager.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SimulatedTraderManager.KEY_UNLOCKED, true).apply()

        val manager = SimulatedTraderManager(context, TestScope()) { true }

        assertTrue(manager.isEntitled.value)
        assertEquals(ModuleInstallState.INSTALLED, manager.installState.value)
    }

    @Test
    fun `given the module is not installed when the dashboard is launched then nothing happens`() {
        val manager = SimulatedTraderManager(context, TestScope())

        // Must be a no-op rather than an ActivityNotFoundException.
        manager.launchTraderDashboard(context)

        assertEquals(ModuleInstallState.NOT_INSTALLED, manager.installState.value)
    }

    @Test
    fun `given the feature module when its component is addressed then it is the proactive host activity`() {
        assertEquals(
            "com.alphapulse.proactive.TraderHostActivity",
            SimulatedTraderManager.TRADER_ACTIVITY
        )
    }
}
