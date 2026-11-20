package com.alphapulse.proactive

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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
 * Spec Ref: Phase 1 §5 Dynamic Feature Module Architecture, §7
 */
class TraderHostActivity : Activity() {

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
                text = "Analysis · Screener · Dashboard · Telemetry · Guardrails · Audit"
                setTextColor(MUTED)
                textSize = 10f
                gravity = Gravity.START
                setPadding(0, dp(20), 0, 0)
            }
        )

        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The six ProactiveMS tabs the module exposes (§7). */
        val PROACTIVE_MS_TABS: List<String> = listOf(
            "Analysis",
            "Screener",
            "Dashboard",
            "Telemetry",
            "Guardrails",
            "Audit"
        )

        private const val BACKGROUND = 0xFF0B1220.toInt()
        private const val ACCENT = 0xFF22D3EE.toInt()
        private const val MUTED = 0xFF94A3B8.toInt()
    }
}
