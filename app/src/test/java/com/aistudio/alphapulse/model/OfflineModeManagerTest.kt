package com.aistudio.alphapulse.model

import com.aistudio.alphapulse.data.model.OfflineMode
import com.aistudio.alphapulse.data.model.OfflineModeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** OfflineModeManager unit tests (spec §35 resilience). */
class OfflineModeManagerTest {

    @Test
    fun `offline_mode_online_when_network_present`() {
        assertEquals(OfflineMode.ONLINE, OfflineModeManager.getCurrentInfo().mode)
        assertEquals(OfflineMode.ONLINE, OfflineModeManager.resolveMode(isConnected = true, isMetered = false))
    }

    @Test
    fun `offline_mode_degraded_when_metered`() {
        assertEquals(OfflineMode.DEGRADED, OfflineModeManager.resolveMode(isConnected = true, isMetered = true))
    }

    @Test
    fun `offline_mode_offline_when_no_network`() {
        assertEquals(OfflineMode.OFFLINE, OfflineModeManager.resolveMode(isConnected = false, isMetered = false))
        assertEquals(OfflineMode.OFFLINE, OfflineModeManager.resolveMode(isConnected = false, isMetered = true))
    }

    @Test
    fun `offline_modes_cover_full_range`() {
        assertNotEquals(OfflineMode.ONLINE, OfflineMode.DEGRADED)
        assertNotEquals(OfflineMode.DEGRADED, OfflineMode.OFFLINE)
        assertNotEquals(OfflineMode.ONLINE, OfflineMode.OFFLINE)
    }
}
