package com.aistudio.alphapulse.ui

import com.alphapulse.contract.ModuleInstallState

/**
 * Phase 1 §7: how the installed Auto-Trader module is surfaced in base navigation.
 *
 * The immutable core rule is that unlocking, downloading or installing the module may
 * not alter the AlphaPulse analysis view. So the decision is made here, in a pure
 * function over the install lifecycle, and the base screen is a fixed content slot
 * that never receives a trader parameter.
 *
 * While entitlement or download is in flight the tab is rendered with honest progress
 * rather than pre-emptively opening a dashboard that is not on the device yet.
 *
 * Spec Ref: Phase 1 §7 Post-Unlock Bottom Tab Navigation, D5, D6
 */

/** What tapping the trader tab must do in the current lifecycle state. */
enum class TraderTabAction {
    /** Not entitled yet: nothing to launch. */
    PURCHASE,

    /** Entitled and downloading/installing: the action is intentionally inert. */
    WAITING,

    /** Installed: open the feature module's host activity. */
    LAUNCH
}

object TraderNavigation {

    const val CORE_TAB_TAG: String = "nav_item_analysis"
    const val TRADER_TAB_TAG: String = "nav_item_trader"

    /** Before entitlement the single bar entry is the VIP unlock call to action. */
    const val VIP_TAB_TAG: String = "nav_item_vip"
    const val UNLOCK_BUTTON_TAG: String = "enable_trader_button"
    const val CORE_SURFACE_TAG: String = "core_analysis_surface"

    /**
     * The tab appears as soon as the entitlement exists, so download progress is
     * visible, and stays once installed. It never appears before a purchase.
     */
    fun isTraderTabVisible(state: ModuleInstallState): Boolean = when (state) {
        ModuleInstallState.NOT_INSTALLED,
        ModuleInstallState.ENTITLEMENT_REQUIRED -> false
        ModuleInstallState.DOWNLOADING,
        ModuleInstallState.INSTALLING,
        ModuleInstallState.INSTALLED,
        ModuleInstallState.ERROR -> true
    }

    /**
     * `VIP Features`, `Trader 40%`, `Installing`, or `VIP unavailable`.
     *
     * `ERROR` is what the lifecycle reports when the entitlement exists but the
     * on-demand host is not on the device, so the bar must not keep advertising a
     * VIP tab that can never open.
     */
    fun label(state: ModuleInstallState, progress: Int): String = when (state) {
        ModuleInstallState.DOWNLOADING -> "Trader ${progress.coerceIn(0, 100)}%"
        ModuleInstallState.INSTALLING -> "Installing"
        ModuleInstallState.ERROR -> "VIP unavailable"
        else -> "VIP Features"
    }

    fun action(state: ModuleInstallState): TraderTabAction = when (state) {
        ModuleInstallState.INSTALLED -> TraderTabAction.LAUNCH
        // Nothing to launch: the module was never delivered to this build.
        ModuleInstallState.ERROR -> TraderTabAction.WAITING
        ModuleInstallState.DOWNLOADING, ModuleInstallState.INSTALLING -> TraderTabAction.WAITING
        ModuleInstallState.NOT_INSTALLED, ModuleInstallState.ENTITLEMENT_REQUIRED ->
            TraderTabAction.PURCHASE
    }

    /**
     * The simulated purchase entry is visible only before entitlement. Once the user
     * owns the module the tab replaces it, so the base screen grows no permanent
     * trader furniture.
     */
    fun showsUnlockButton(state: ModuleInstallState): Boolean = when (state) {
        ModuleInstallState.NOT_INSTALLED, ModuleInstallState.ENTITLEMENT_REQUIRED -> true
        else -> false
    }
}
