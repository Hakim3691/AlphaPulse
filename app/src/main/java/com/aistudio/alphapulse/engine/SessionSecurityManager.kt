package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.VenueHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 1 hardening (Spec §3, Appendix B):
 *  - Credentials are held as char arrays and zeroized in place on every wipe, so the
 *    immutable-String interned copies from earlier revisions cannot linger in RAM.
 *  - Diagnostics now feed feed-staleness into the health gate: a mandatory feed that
 *    fails its latency probe (>= UNREACHABLE_LATENCY_MS) marks the venue stale.
 *
 * Zero persistence mandate unchanged: nothing here touches disk, SharedPreferences,
 * or any database.
 */
class SessionSecurityManager {

    private var ephemeralApiKeyChars: CharArray? = null
    private var ephemeralApiSecretChars: CharArray? = null

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _venueHealth = MutableStateFlow(VenueHealth())
    val venueHealth: StateFlow<VenueHealth> = _venueHealth.asStateFlow()

    fun startSession(key: String, secret: String) {
        if (key.isNotBlank() && secret.isNotBlank()) {
            // Replace any prior credentials, zeroizing them first.
            zeroize()
            ephemeralApiKeyChars = key.trim().toCharArray()
            ephemeralApiSecretChars = secret.trim().toCharArray()
            _isSessionActive.value = true
        }
    }

    /**
     * Suspension Wipe (Spec §3): zeroes credential buffers in place, then clears them,
     * and deactivates the session immediately.
     */
    fun terminateSessionAndWipeRam() {
        zeroize()
        _isSessionActive.value = false
    }

    /** Zeroizes any currently held credential buffers in place, then clears them. */
    private fun zeroize() {
        ephemeralApiKeyChars?.fill('\u0000')
        ephemeralApiSecretChars?.fill('\u0000')
        ephemeralApiKeyChars = null
        ephemeralApiSecretChars = null
    }

    fun hasValidActiveSession(): Boolean {
        val key = ephemeralApiKeyChars
        return _isSessionActive.value && key != null && key.isNotEmpty()
    }

    /**
     * Phase 4 (Spec §22): copies the active credentials out for exactly one signing
     * operation. The originals are never exposed; the caller must zeroize both copies
     * (Arrays.fill 0) immediately after the signed call completes.
     */
    fun copyCredentials(): Pair<CharArray, CharArray>? {
        val key = ephemeralApiKeyChars
        val secret = ephemeralApiSecretChars
        if (key == null || key.isEmpty() || secret == null || secret.isEmpty() || !_isSessionActive.value) return null
        return Pair(key.copyOf(), secret.copyOf())
    }

    fun updateHardwareDiagnostics(
        pingBinance: Long,
        pingCoinGlass: Long,
        isLowPower: Boolean,
        isThrottled: Boolean,
        availableRamMb: Int
    ) {
        val isRamConstrained = availableRamMb < RAM_FLOOR_MB
        val isLatencySpike = pingBinance > LATENCY_SPIKE_BINANCE_MS ||
            pingCoinGlass > LATENCY_SPIKE_COINGLASS_MS
        val unreachableProbe = pingBinance >= HardwareDiagnosticsProvider.UNREACHABLE_LATENCY_MS ||
            pingCoinGlass >= HardwareDiagnosticsProvider.UNREACHABLE_LATENCY_MS

        _venueHealth.value = _venueHealth.value.copy(
            binancePingMs = pingBinance,
            coinglassPingMs = pingCoinGlass,
            isLatencySpike = isLatencySpike,
            isLowPowerMode = isLowPower,
            isThermallyThrottled = isThrottled,
            availableRamMb = availableRamMb,
            isRamConstrained = isRamConstrained,
            isFeedStale = unreachableProbe
        )
    }

    companion object {
        const val RAM_FLOOR_MB = 800
        const val LATENCY_SPIKE_BINANCE_MS = 120L
        const val LATENCY_SPIKE_COINGLASS_MS = 150L
    }
}
