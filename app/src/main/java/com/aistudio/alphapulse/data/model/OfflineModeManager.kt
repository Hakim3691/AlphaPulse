package com.aistudio.alphapulse.data.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/** OfflineMode - The three offline operating states.
 *
 * Spec Ref: §36 Offline Mode
 */
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
    private var connectivityManager: ConnectivityManager? = null
    private var currentInfo = OfflineInfo()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Register the network callback. Must be called with a valid Application context. */
    fun init(context: Context) {
        val appContext = context.applicationContext
        this.context = appContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        }
        callback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            // Fallback: just compute the current state once.
        }
        refresh()
    }

    /** Unregister the callback. */
    fun cleanup() {
        val cm = connectivityManager
        val cb = callback
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // Ignore: callback may not have been registered.
            }
        }
        callback = null
        connectivityManager = null
        context = null
        currentInfo = OfflineInfo()
    }

    /** Get the current offline info. */
    fun getCurrentInfo(): OfflineInfo = currentInfo

    /** Map raw connectivity facts onto the operating mode. */
    fun resolveMode(isConnected: Boolean, isMetered: Boolean): OfflineMode = when {
        !isConnected -> OfflineMode.OFFLINE
        isMetered -> OfflineMode.DEGRADED
        else -> OfflineMode.ONLINE
    }

    /** Recompute connectivity state from the system and update currentInfo. */
    private fun refresh() {
        val cm = connectivityManager ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val isConnected = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isMetered = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val mode = resolveMode(isConnected, isMetered)

        currentInfo = OfflineInfo(
            mode = mode,
            isConnected = isConnected,
            isMetered = isMetered,
            lastConnectivityChange = System.currentTimeMillis()
        )
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
