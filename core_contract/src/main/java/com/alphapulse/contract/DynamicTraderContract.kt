package com.alphapulse.contract

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 1 D4/D5: the boundary between the base app and the on-demand
 * `:proactive_trader` feature.
 *
 * The base app must be able to unlock, download and launch the module without a
 * compile-time dependency on it, so the whole lifecycle is expressed here and the
 * only thing that crosses the boundary is this interface, a state enum and an
 * Intent class name.
 *
 * Spec Ref: Phase 1 §6 Offline Simulated Entitlement & Dynamic Install Engine
 */
enum class ModuleInstallState {
    NOT_INSTALLED,
    ENTITLEMENT_REQUIRED,
    DOWNLOADING,
    INSTALLING,
    INSTALLED,
    ERROR
}

interface DynamicTraderContract {
    val installState: StateFlow<ModuleInstallState>

    /** 0 to 100%. */
    val downloadProgress: StateFlow<Int>
    val isEntitled: StateFlow<Boolean>

    /** Shows the simulated Play Billing sheet; [onSuccess] fires after entitlement. */
    fun requestPurchaseSimulation(activity: Activity, onSuccess: () -> Unit)

    /** Runs the simulated SplitInstallManager download progression. */
    fun startDynamicDownloadSimulation()

    /** Launches the trader dashboard only once the module is installed. */
    fun launchTraderDashboard(context: Context)
}
