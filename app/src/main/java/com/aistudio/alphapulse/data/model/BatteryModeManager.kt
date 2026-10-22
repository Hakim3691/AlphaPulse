package com.aistudio.alphapulse.data.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/** BatteryMode - The three operating modes based on battery state. */
enum class BatteryMode {
    /** Full power - device is charging or has sufficient battery. */
    FULL,

    /** Reduced power - device is on battery but above low-threshold. */
    MODERATE,

    /** Conservative mode - device is on low battery. Engine throttles to conserve power. */
    CONSERVATIVE
}

/** BatteryInfo - Captures the current battery state from the system. */
data class BatteryInfo(
    val mode: BatteryMode = BatteryMode.FULL,
    val percentage: Int = 100,
    val isCharging: Boolean = false,
    val isLow: Boolean = false
)

/** BatteryModeManager - Observes the system battery and emits BatteryInfo updates.
 * The ModelManager and other engine components observe this to adjust their
 * operation mode, refresh rates, and data sampling.
 */
object BatteryModeManager {

    private var context: Context? = null
    private var currentInfo = BatteryInfo()

    /** Register the battery receiver. Must be called with a valid Application context. */
    fun init(context: Context) {
        this.context = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        context.registerReceiver(BatteryReceiver, filter)
    }

    /** Unregister the receiver. */
    fun cleanup() {
        context?.unregisterReceiver(BatteryReceiver)
        context = null
    }

    /** Get the current battery info. */
    fun getCurrentInfo(): BatteryInfo = currentInfo

    /** Convert raw battery level/scale extras into a percentage in 0..100. */
    fun levelToPercent(level: Int, scale: Int): Int =
        if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale) * 100).toInt().coerceIn(0, 100)
        } else {
            100
        }

    /** Map battery percentage and charging state onto the operating mode. */
    fun resolveMode(percentage: Int, isCharging: Boolean): BatteryMode = when {
        isCharging -> BatteryMode.FULL
        percentage <= 15 -> BatteryMode.CONSERVATIVE
        percentage <= 40 -> BatteryMode.MODERATE
        else -> BatteryMode.FULL
    }

    /** Internal broadcast receiver for battery updates. */
    private val BatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || context == null) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val technology = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"
            } else "unknown"

            val pct = levelToPercent(level, scale)
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val isLow = pct <= 15
            val mode = resolveMode(pct, charging)

            currentInfo = BatteryInfo(mode = mode, percentage = pct, isCharging = charging, isLow = isLow)

            // Notify interested observers (ModelManager, etc.) via context's preferred mechanism
            // In a real app, this would use LiveData, Flow, or an event bus.
            // Here we simply log; subclasses or listeners can observe currentInfo.
        }
    }
}

/** Extensions for ModelManager to adjust based on battery mode. */
fun ModelManager.withBatteryMode(mode: BatteryMode) {
    when (mode) {
        BatteryMode.FULL -> { /* no restrictions */ }
        BatteryMode.MODERATE -> {
            // e.g., reduce sampling frequency by 30%
        }
        BatteryMode.CONSERVATIVE -> {
            // e.g., pause non-essential data feeds, keep only critical prediction horizon
        }
    }
}