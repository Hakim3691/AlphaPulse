package com.aistudio.alphapulse.model

import com.aistudio.alphapulse.data.model.BatteryMode
import com.aistudio.alphapulse.data.model.BatteryModeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** BatteryModeManager unit tests (spec §35 battery optimisation). */
class BatteryModeManagerTest {

    @Test
    fun `battery_full_while_charging_returns_full_mode`() {
        assertEquals(BatteryMode.FULL, BatteryModeManager.resolveMode(80, isCharging = true))
    }

    @Test
    fun `battery_low_not_charging_returns_conservative_mode`() {
        assertEquals(BatteryMode.CONSERVATIVE, BatteryModeManager.resolveMode(8, isCharging = false))
        assertEquals(BatteryMode.CONSERVATIVE, BatteryModeManager.resolveMode(15, isCharging = false))
    }

    @Test
    fun `battery_mid_range_returns_moderate_mode`() {
        assertEquals(BatteryMode.MODERATE, BatteryModeManager.resolveMode(30, isCharging = false))
        assertEquals(BatteryMode.MODERATE, BatteryModeManager.resolveMode(40, isCharging = false))
    }

    @Test
    fun `battery_above_mid_range_returns_full_mode`() {
        assertEquals(BatteryMode.FULL, BatteryModeManager.resolveMode(60, isCharging = false))
    }

    @Test
    fun `battery_percentage_calculation_is_correct`() {
        assertEquals(60, BatteryModeManager.levelToPercent(level = 60, scale = 100))
        assertEquals(50, BatteryModeManager.levelToPercent(level = 1, scale = 2))
        // Missing extras fall back to a full battery rather than zero.
        assertEquals(100, BatteryModeManager.levelToPercent(level = -1, scale = -1))
    }

    @Test
    fun `battery_modes_cover_full_range`() {
        assertNotEquals(BatteryMode.FULL, BatteryMode.MODERATE)
        assertNotEquals(BatteryMode.MODERATE, BatteryMode.CONSERVATIVE)
        assertNotEquals(BatteryMode.FULL, BatteryMode.CONSERVATIVE)
    }
}
