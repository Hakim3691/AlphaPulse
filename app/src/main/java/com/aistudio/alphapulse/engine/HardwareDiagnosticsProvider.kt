package com.aistudio.alphapulse.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * One OS-sampled hardware/network diagnostic snapshot (Spec §2 Pre-Flight Diagnostics).
 */
data class HardwareSample(
    val binancePingMs: Long,
    val coinglassPingMs: Long,
    val isLowPowerMode: Boolean,
    val isThermallyThrottled: Boolean,
    val availableRamMb: Int,
    val batteryLevelPct: Int
)

/**
 * Phase 1 (Spec §2, Appendix B): real device diagnostics.
 *
 * Reads RAM, low-power mode, thermal status and battery level from the Android OS and
 * measures mandatory-feed API latency with short, bounded connections. No polling loop
 * is owned here; callers decide when to refresh.
 *
 * Threshold semantics (latency spike, RAM floor) remain authoritative in
 * [SessionSecurityManager.updateHardwareDiagnostics] so guardrail evaluation has a
 * single home.
 *
 * `latencyProbe` is injectable for deterministic unit tests.
 */
class HardwareDiagnosticsProvider(
    private val context: Context,
    private val latencyProbe: suspend (String) -> Long = { url -> defaultLatencyProbe(url) }
) {

    companion object {
        const val BINANCE_FAPI_PING_URL = "https://fapi.binance.com/fapi/v1/ping"
        const val COINGLASS_PING_URL = "https://open-api.coinglass.com/public/v2/exchange_list"

        /** Bounded per-probe timeout; anything slower cannot safely run a cancellation engine. */
        const val LATENCY_TIMEOUT_MS = 3_000

        /** Sentinel returned when an endpoint is unreachable; feeds `isFeedStale` upstream. */
        const val UNREACHABLE_LATENCY_MS = 9_999L

        /** Spec §2: MODERATE throttling or worse suppresses staging and cancellation. */
        private const val THERMAL_THROTTLE_THRESHOLD = PowerManager.THERMAL_STATUS_MODERATE

        suspend fun defaultLatencyProbe(url: String): Long = withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = LATENCY_TIMEOUT_MS
                    readTimeout = LATENCY_TIMEOUT_MS
                    requestMethod = "GET"
                    useCaches = false
                    instanceFollowRedirects = false
                }
                val code = connection.responseCode
                if (code in 200..299) SystemClock.elapsedRealtime() - startedAt else UNREACHABLE_LATENCY_MS
            } catch (_: Exception) {
                UNREACHABLE_LATENCY_MS
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun isDeviceInLowPowerMode(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false

    fun readAvailableRamMb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.availMem / (1024L * 1024L)).toInt()
    }

    /**
     * True when the OS reports MODERATE thermal throttling or worse (API 30+).
     * On older APIs this conservatively returns false — the RAM/latency/low-power
     * guardrails still apply.
     */
    fun isDeviceThermallyThrottled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.currentThermalStatus >= THERMAL_THROTTLE_THRESHOLD
    }

    /** -1 when the sticky battery broadcast is unavailable. */
    fun readBatteryLevelPct(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return (level * 100) / scale
    }

    /** One-shot snapshot combining OS state and bounded mandatory-feed latency probes. */
    suspend fun sample(): HardwareSample = HardwareSample(
        binancePingMs = latencyProbe(BINANCE_FAPI_PING_URL),
        coinglassPingMs = latencyProbe(COINGLASS_PING_URL),
        isLowPowerMode = isDeviceInLowPowerMode(),
        isThermallyThrottled = isDeviceThermallyThrottled(),
        availableRamMb = readAvailableRamMb(),
        batteryLevelPct = readBatteryLevelPct()
    )
}
