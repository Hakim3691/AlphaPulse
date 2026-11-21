package com.alphapulse.proactive

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import com.aistudio.alphapulse.ui.MainViewModel

/**
 * Phase 1 §7: the dynamic feature module's host screen.
 *
 * The base app launches this by component name
 * (`com.alphapulse.proactive.TraderHostActivity`), so there is no compile-time
 * dependency in either direction. The six ProactiveMS tabs render here, in their
 * own module, and never inside the AlphaPulse analysis view.
 *
 * The UI is built in code on purpose: the feature ships no resources and no native
 * libraries, so its packaging cannot disturb the base module's arm64 resource /
 * split processing.
 *
 * This activity mounts the base app's [MainViewModel]: it is the shipped surface that
 * runs the paper-trade loop and the single Scaled Live staging gate backed by the
 * durable §38 evidence. Before this wiring the view model was never mounted by any
 * shipped screen, so Scaled Live could not be reached outside tests.
 *
 * Spec Ref: Phase 1 §5 Dynamic Feature Module Architecture, §7; Phase 2 §38
 */
class TraderHostActivity : ComponentActivity() {

    /** The engine behind the auto-trader tabs; owns trading evidence and the gate. */
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        PROACTIVE_MS_TABS.forEach { label ->
            tabRow.addView(
                Button(this).apply {
                    text = label
                    textSize = 11f
                    setOnClickListener {
                        Toast.makeText(this@TraderHostActivity, label, Toast.LENGTH_SHORT).show()
                    }
                }
            )
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

        root.addView(
            TextView(this).apply {
                text = "Screener · Dashboard · Telemetry · Guardrails · Replay · Audit"
                setTextColor(MUTED)
                textSize = 10f
                gravity = Gravity.START
                setPadding(0, dp(20), 0, 0)
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
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The six ProactiveMS tabs the module exposes (§7). */
        val PROACTIVE_MS_TABS: List<String> = listOf(
            "Screener",
            "Dashboard",
            "Telemetry",
            "Guardrails",
            "Replay",
            "Audit"
        )

        private const val BACKGROUND = 0xFF0B1220.toInt()
        private const val ACCENT = 0xFF22D3EE.toInt()
        private const val MUTED = 0xFF94A3B8.toInt()
    }
}
