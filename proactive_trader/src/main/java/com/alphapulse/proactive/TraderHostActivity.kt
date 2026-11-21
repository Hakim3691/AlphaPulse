package com.alphapulse.proactive

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aistudio.alphapulse.engine.OrphanInterceptDialog
import com.aistudio.alphapulse.engine.OrphanInterceptLifecycle
import com.aistudio.alphapulse.ui.MainUiState
import com.aistudio.alphapulse.ui.MainViewModel
import com.aistudio.alphapulse.ui.ProactiveMsTab
import com.aistudio.alphapulse.ui.ProactiveMsWorkspaceState
import com.aistudio.alphapulse.ui.ProactiveTabRenderer
import kotlinx.coroutines.launch

/**
 * Phase 1 §7 / Phase 2 §32: the dynamic feature module's host screen.
 *
 * The base app launches this by component name
 * (`com.alphapulse.proactive.TraderHostActivity`), so there is no compile-time
 * dependency in either direction. The ProactiveMS tabs render here, in their own
 * module, and never inside the AlphaPulse analysis view.
 *
 * The UI is built in code on purpose: the feature ships no resources and no native
 * libraries, so its packaging cannot disturb the base module's arm64 resource /
 * split processing.
 *
 * This activity mounts the base app's [MainViewModel]: it is the shipped surface that
 * runs the paper-trade loop and the single Scaled Live staging gate backed by the
 * durable §38 evidence.
 *
 * Three things this host must do, because it is the only shipped screen that mounts
 * the engine (each has its own BDD spec):
 *  - render the canonical [ProactiveMsWorkspaceState.TAB_ORDER] with one shared symbol,
 *    instead of the retired six-label list and its Toast placeholders (`ProactiveTabsBddSpec`);
 *  - observe the activity lifecycle and forward `ON_STOP` to the §3 policy
 *    (`OrphanInterceptReachabilityBddSpec`);
 *  - compose the §3 intercept when the policy asks for it.
 *
 * Spec Ref: Phase 1 §5 Dynamic Feature Module Architecture, §7, §3; Phase 2 §32, §38
 */
class TraderHostActivity : ComponentActivity() {

    /** The engine behind the auto-trader tabs; owns trading evidence and the gate. */
    private val viewModel: MainViewModel by viewModels()

    /** The one selection every tab shares. */
    private var workspace = ProactiveMsWorkspaceState()

    /** The last state the engine published; the tab body is a pure function of it. */
    private var lastState = MainUiState()

    private lateinit var bodyText: TextView
    private lateinit var symbolRow: LinearLayout
    private val tabButtons = mutableMapOf<ProactiveMsTab, Button>()

    /** Guard so a re-emitted state does not stack intercept dialogs. */
    private var interceptDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // §3: ON_STOP is the only signal that reaches the policy. ON_PAUSE is not used,
        // so a permission sheet or a system dialog cannot wipe a live session.
        lifecycle.addObserver(OrphanInterceptLifecycle { viewModel.onAppBackgrounded() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(16), dp(28), dp(16), dp(20))
        }

        root.addView(
            TextView(this).apply {
                text = "PROACTIVEMS AUTO-TRADER"
                setTextColor(ACCENT)
                textSize = 18f
            }
        )
        root.addView(
            TextView(this).apply {
                text = "Delivered on demand as the :proactive_trader dynamic feature. " +
                    "The base AlphaPulse analysis view is unchanged."
                setTextColor(MUTED)
                textSize = 11f
                setPadding(0, dp(6), 0, dp(16))
            }
        )

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        ProactiveMsWorkspaceState.TAB_ORDER.forEach { tab ->
            val button = Button(this).apply {
                text = tab.label
                textSize = 11f
                setOnClickListener {
                    workspace = workspace.selectTab(tab)
                    refreshTabs()
                    renderBody()
                }
            }
            tabButtons[tab] = button
            tabRow.addView(button)
        }
        root.addView(
            HorizontalScrollView(this).apply {
                addView(
                    tabRow,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )

        symbolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(
            HorizontalScrollView(this).apply {
                addView(
                    symbolRow,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )

        bodyText = TextView(this).apply {
            setTextColor(SlateTextPrimary)
            textSize = 12f
            gravity = Gravity.START
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        root.addView(
            ScrollView(this).apply {
                addView(
                    bodyText,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
        )

        root.addView(
            TextView(this).apply {
                // §38: the mounted engine's pre-launch verdict, read from the same
                // persisted evidence its staging gate uses.
                text = viewModel.preLaunchVerdict().summary
                setTextColor(MUTED)
                textSize = 10f
                setPadding(0, dp(12), 0, 0)
            }
        )

        setContentView(root)
        refreshTabs()
        renderBody()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    lastState = state
                    workspace = workspace.copy(
                        verifiedSymbols = state.assets.map { it.symbol }
                    )
                    renderSymbolRow()
                    renderBody()
                    syncIntercept(state)
                }
            }
        }
    }

    override fun onDestroy() {
        interceptDialog?.dismiss()
        interceptDialog = null
        super.onDestroy()
    }

    /** §3: mirror the policy's verdict into the platform dialog exactly once. */
    private fun syncIntercept(state: MainUiState) {
        if (state.isOrphanedOrderWarningOpen) {
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

    private fun refreshTabs() {
        tabButtons.forEach { (tab, button) ->
            val selected = tab == workspace.activeTab
            button.alpha = if (selected) 1f else 0.55f
            button.setTextColor(if (selected) ACCENT else MUTED)
        }
    }

    private fun renderSymbolRow() {
        symbolRow.removeAllViews()
        if (workspace.availableSymbols.isEmpty()) {
            symbolRow.addView(
                TextView(this).apply {
                    text = "No screened coin yet"
                    setTextColor(MUTED)
                    textSize = 10f
                }
            )
            return
        }
        workspace.availableSymbols.forEach { symbol ->
            val selected = symbol == workspace.activeSymbol
            symbolRow.addView(
                Button(this).apply {
                    text = symbol
                    textSize = 10f
                    alpha = if (selected) 1f else 0.6f
                    setOnClickListener {
                        // One symbol, shared by every tab (§72/C-22).
                        workspace = workspace.selectSymbol(symbol)
                        renderSymbolRow()
                        renderBody()
                    }
                }
            )
        }
    }

    private fun renderBody() {
        bodyText.text = ProactiveTabRenderer
            .lines(workspace.activeTab, lastState, workspace)
            .joinToString("\n")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BACKGROUND = 0xFF0B1220.toInt()
        private const val ACCENT = 0xFF22D3EE.toInt()
        private const val MUTED = 0xFF94A3B8.toInt()
        private const val SlateTextPrimary = 0xFFE2E8F0.toInt()
    }
}
