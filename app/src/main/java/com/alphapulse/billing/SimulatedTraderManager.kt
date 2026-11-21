package com.alphapulse.billing

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.alphapulse.contract.DynamicTraderContract
import com.alphapulse.contract.ModuleInstallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Phase 1 D5: an offline emulation of Google Play Billing and
 * `SplitInstallManager`, used only because the app is pre-launch and not yet on
 * the Play Console.
 *
 * The simulation is deliberately faithful about the parts that matter: entitlement
 * is persisted, the download reports real progress in 20% steps, the install phase
 * is distinct from the download, and launching before [ModuleInstallState.INSTALLED]
 * is a no-op rather than a crash.
 *
 * One thing it must not do is pretend: the `:proactive_trader` module is
 * on-demand, so a published base APK merges its manifest entry while carrying no
 * dex with `com.alphapulse.proactive.TraderHostActivity`. `PackageManager`
 * resolves such a component, and a launch then dies inside the app process where
 * `startActivity`'s try/catch cannot see it. Only the class loader can answer
 * whether the module is really installed, so [isModuleHostPresent] does, and the
 * lifecycle ends in [ModuleInstallState.ERROR] instead of a false `INSTALLED`.
 *
 * Spec Ref: Phase 1 §6 Offline Simulated Entitlement & Dynamic Install Engine
 */
class SimulatedTraderManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Whether the feature's host class is really on this device, not just declared. */
    private val isModuleHostPresent: () -> Boolean = { hostClassPresent(context) }
) : DynamicTraderContract {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val isEntitled = MutableStateFlow(prefs.getBoolean(KEY_UNLOCKED, false))

    override val installState = MutableStateFlow(
        when {
            !isEntitled.value -> ModuleInstallState.NOT_INSTALLED
            isModuleHostPresent() -> ModuleInstallState.INSTALLED
            // Entitlement outlived the module (a restored backup, or an APK that
            // never carried the split). Never report a module that cannot open.
            else -> ModuleInstallState.ERROR
        }
    )

    override val downloadProgress = MutableStateFlow(0)

    override fun requestPurchaseSimulation(activity: Activity, onSuccess: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Google Play Billing (Simulated)")
            .setMessage("Unlock ProactiveMS VIP Features Plugin for $49.99 (Debug Emulation)?")
            .setPositiveButton("Simulate Purchase Success") { _, _ ->
                prefs.edit().putBoolean(KEY_UNLOCKED, true).apply()
                isEntitled.value = true
                startDynamicDownloadSimulation()
                onSuccess()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun startDynamicDownloadSimulation() {
        scope.launch {
            installState.value = ModuleInstallState.DOWNLOADING
            downloadProgress.value = 0
            for (progress in 10..100 step 20) {
                downloadProgress.value = progress
                delay(PROGRESS_STEP_MS)
            }
            downloadProgress.value = 100
            installState.value = ModuleInstallState.INSTALLING
            delay(INSTALL_MS)
            // The emulation downloads nothing, so the honest end state is decided by
            // the device: only a real host class makes the module INSTALLED.
            installState.value = if (isModuleHostPresent()) {
                ModuleInstallState.INSTALLED
            } else {
                ModuleInstallState.ERROR
            }
        }
    }

    override fun launchTraderDashboard(context: Context) {
        if (installState.value != ModuleInstallState.INSTALLED) return
        if (!isModuleHostPresent()) {
            // Starting a declared-but-missing activity throws inside the app process,
            // after this call has returned; refuse before the process can die.
            installState.value = ModuleInstallState.ERROR
            Toast.makeText(context, MODULE_UNAVAILABLE_MESSAGE, Toast.LENGTH_LONG).show()
            return
        }
        // Reflection-free dynamic launch: the class lives only in the feature
        // module, so it is addressed by component name.
        val intent = Intent().apply {
            setClassName(context, TRADER_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { installState.value = ModuleInstallState.ERROR }
    }

    companion object {
        const val TRADER_ACTIVITY: String = "com.alphapulse.proactive.TraderHostActivity"
        const val PREFS: String = "alphapulse_entitlements"
        const val KEY_UNLOCKED: String = "trader_plugin_unlocked"
        const val PROGRESS_STEP_MS: Long = 300
        const val INSTALL_MS: Long = 400

        /** Shown when the entitlement exists but the module was never delivered. */
        const val MODULE_UNAVAILABLE_MESSAGE: String =
            "VIP Features are not installed in this build. The on-demand module was not delivered with this APK."
    }
}

/** The merged manifest declares the host either way; only the class loader knows. */
private fun hostClassPresent(context: Context): Boolean = runCatching {
    Class.forName(SimulatedTraderManager.TRADER_ACTIVITY, false, context.classLoader)
}.isSuccess
