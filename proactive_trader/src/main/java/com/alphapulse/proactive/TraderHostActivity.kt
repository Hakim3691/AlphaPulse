package com.alphapulse.proactive

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alphapulse.billing.SimulatedTraderManager
import com.aistudio.alphapulse.engine.OrphanInterceptDialog
import com.aistudio.alphapulse.engine.OrphanInterceptLifecycle
import com.aistudio.alphapulse.ui.MainViewModel
import com.aistudio.alphapulse.ui.ProactiveMsTab
import com.aistudio.alphapulse.ui.mountProactiveWorkspace
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseViewModel
import kotlinx.coroutines.launch

/**
 * Phase 1 §7 / Phase 2 §32: the dynamic feature module's host screen.
 *
 * The base app launches this by component name
 * (`com.alphapulse.proactive.TraderHostActivity`), so there is no compile-time
 * dependency in either direction. The ProactiveMS tabs render here, in their own
 * module, and never inside the AlphaPulse analysis view.
 *
 * The host is deliberately thin. The workspace itself is composed in the base module
 * (`ProactiveWorkspaceScreen`) so every tab is drawn with AlphaPulse's design system and
 * components rather than a hand-built `TextView` column, and the feature still ships no
 * resources or native libraries to reconcile with the base module's arm64 split.
 *
 * Three things this host must do, because it is the only shipped screen that mounts
 * the engine (each has its own BDD spec):
 *  - render the canonical [com.aistudio.alphapulse.ui.ProactiveMsWorkspaceState.TAB_ORDER]
 *    workspace with one shared symbol (`ProactiveTabsBddSpec`);
 *  - observe the activity lifecycle and forward `ON_STOP` to the §3 policy
 *    (`OrphanInterceptReachabilityBddSpec`);
 *  - compose the §3 intercept when the policy asks for it.
 *
 * Spec Ref: Phase 1 §5 Dynamic Feature Module Architecture, §7, §3; Phase 2 §32, §38
 */
class TraderHostActivity : ComponentActivity() {

    /** The engine behind the ProactiveMS tabs; owns trading evidence and the gate. */
    private val viewModel: MainViewModel by viewModels()

    /**
     * C-22's AlphaPulse tab is the real analysis screen, so the host also mounts the
     * analysis view model rather than a summary of it.
     */
    private val alphaPulseViewModel: AlphaPulseViewModel by viewModels()

    /** Guard so a re-emitted state does not stack intercept dialogs. */
    private var interceptDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // C-57: the workspace is a paid surface, and it now shares the app's one
        // navigation bar. A host reached without the persisted entitlement (a stale
        // deep link, an exported-launch attempt) closes instead of mounting the
        // campaign engine for an unentitled user. The manifest keeps the activity
        // unexported; this is the defensive second check.
        if (!isVipEntitled()) {
            finish()
            return
        }

        // §3: ON_STOP is the only signal that reaches the policy. ON_PAUSE is not used,
        // so a permission sheet or a system dialog cannot wipe a live session.
        lifecycle.addObserver(OrphanInterceptLifecycle { viewModel.onAppBackgrounded() })

        mountProactiveWorkspace(
            activity = this,
            mainViewModel = viewModel,
            alphaPulseViewModel = alphaPulseViewModel,
            initialTab = requestedInitialTab()
        )

        // §3: mirror the policy's verdict into the platform dialog exactly once.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> syncIntercept(state.isOrphanedOrderWarningOpen) }
            }
        }
    }

    override fun onDestroy() {
        interceptDialog?.dismiss()
        interceptDialog = null
        super.onDestroy()
    }

    /** The persisted simulated entitlement, the same key the base bar reads. */
    private fun isVipEntitled(): Boolean =
        getSharedPreferences(SimulatedTraderManager.PREFS, MODE_PRIVATE)
            .getBoolean(SimulatedTraderManager.KEY_UNLOCKED, false)

    /** C-57: the shared bar's requested tab; an unknown value falls back to AlphaPulse. */
    private fun requestedInitialTab(): ProactiveMsTab? =
        intent?.getStringExtra(SimulatedTraderManager.EXTRA_INITIAL_TAB)
            ?.let { name -> runCatching { ProactiveMsTab.valueOf(name) }.getOrNull() }

    private fun syncIntercept(isOpen: Boolean) {
        if (isOpen) {
            if (interceptDialog != null) return
            interceptDialog = OrphanInterceptDialog.show(
                activity = this,
                onAcknowledge = {
                    interceptDialog = null
                    viewModel.acknowledgeOrphanedOrderWarning()
                },
                onDismiss = {
                    interceptDialog = null
                    viewModel.dismissOrphanedOrderWarning()
                }
            )
        } else {
            interceptDialog?.dismiss()
            interceptDialog = null
        }
    }
}
