package com.aistudio.alphapulse.data.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlin.coroutines.experimental.CoroutineContext

/** OfflineMode - The three offline operating states. */
enum class OfflineMode {
    /** Online - network is available and active. */
    ONLINE,

    /** Offline degraded - network is present but metered or unstable; use cached data. */
    DEGRADED,

    /** Fully offline - no network connectivity. Use stale cache only. */
    OFFLINE
}

/** OfflineInfo - Snapshot of the current network connectivity state. */
data class OfflineInfo(
    val mode: OfflineMode = OfflineMode.ONLINE,
    val isConnected: Boolean = false,
    val isMetered: Boolean = false,
    val lastConnectivityChange: Long = System.currentTimeMillis()
)

/** OfflineModeManager - Observes connectivity via ConnectivityManager and emits
 * OfflineInfo updates. The ModelManager and repository layers observe this to
 * switch between live data feeds and cached-data fallbacks.
 */
object OfflineModeManager {

    private var context: Context? = null
    private var currentInfo = OfflineInfo()

    /** Register the connectivity receiver. Must be called with a valid Application context. */
    fun init(context: Context) {
        this.context = context.applicationContext
        val receiver = ConnectivityReceiver()
        val registerResult = context.registerReceiver(receiver, null)
        // On API 23+, we need POST_CONNECTIVITY permission; the system receiver
        // is sufficient for our use case. We keep a reference to avoid GC.
        if (registerResult != null) {
            // Receiver registered successfully
        }
    }

    /** Unregister the receiver. */
    fun cleanup() {
        context?.unregisterReceiver(ConnectivityReceiver)
        context = null
        currentInfo = OfflineInfo()
    }

    /** Get the current offline info. */
    fun getCurrentInfo(): OfflineInfo = currentInfo

    /** Internal broadcast receiver for connectivity changes. */
    private val ConnectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || context == null) return

            val networkType: Int = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    val network = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
                    if (network == null) 0
                    else when (network.type) {
                        NetworkManager.NET_TYPE_WIFI -> 1
                        NetworkManager.NET_TYPE_CELLULAR -> 2
                        NetworkManager.NET_TYPE_ETHERNET -> 3
                        else -> 0
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    when (intent.getStringExtra(ConnectivityManager.EXTRA_EXTRA_NETWORK_INFO)) {
                        "WIFI" -> 1
                        "CELLULAR" -> 2
                        else -> 0
                    }
                }
                else -> 0
            }

            val isConnected = networkType != 0
            val isMetered = when (networkType) {
                1 -> false // Wi-Fi is not metered in our model
                2 -> intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_METERED, false)
                else -> false
            }

            val mode = when (isConnected) {
                !isMetered -> OfflineMode.ONLINE
                isMetered -> OfflineMode.DEGRADED
            } else OfflineMode.OFFLINE

            val old = currentInfo
            currentInfo = OfflineInfo(mode = mode, isConnected = isConnected, isMetered = isMetered, lastConnectivityChange = System.currentTimeMillis())

            // Notify observers (ModelManager, Repository) — in production would use LiveData/Flow
        }
    }
}

/** Convenience extension for the ModelManager to react to offline mode changes. */
fun ModelManager.withOfflineMode(mode: OfflineMode) {
    when (mode) {
        OfflineMode.ONLINE -> {
            // Resume live data feeds, full polling frequency, real-time predictions
        }
        OfflineMode.DEGRADED -> {
            // Use cached snapshots, reduce refresh interval, show "stale data" banner
        }
        OfflineMode.OFFLINE -> {
            // Serve only from persisted cache; disable auto-refresh; show "offline" banner
        }
    }
}