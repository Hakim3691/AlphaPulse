package com.aistudio.alphapulse.engine.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §34 / Contract C-45 — the production configuration asset.
 *
 * Spec Ref: Phase 2 §34
 */
class ProductionExecutionConfigBddSpec {

    @Test
    fun `given the production configuration when read then every section 34 default is the published value`() {
        val config = ProductionExecutionConfig.PRODUCTION

        assertEquals(5_000_000.0, config.minQuoteVolume24hUsd, 0.0)
        assertEquals(0.10, config.maxSpreadPct, 1e-9)
        assertEquals(0.25, config.atrZoneMultiplier, 1e-9)
        assertEquals(0.20, config.levelTouchDecayRate, 1e-9)
        assertEquals(45, config.softInvalidationThreshold)
        assertEquals(70, config.hardInvalidationThreshold)
        assertEquals(0.50, config.accountRiskPerCampaignPct, 1e-9)
        assertEquals(2.0, config.maxDailyLossR, 1e-9)
        assertEquals(30, config.reentryCooldownMinutes)
        assertEquals(5_000L, config.wsPingTimeoutMs)

        // The cooldown the loop writes onto a hard-invalidated campaign is §34's
        // 30 minutes in the units the campaign stores.
        assertEquals(30L * 60_000L, config.reentryCooldownMillis())
    }

    @Test
    fun `given a configuration that inverts the thresholds or accepts every spread when constructed then it is rejected`() {
        var softAboveHard = false
        try {
            ProductionExecutionConfig(softInvalidationThreshold = 80, hardInvalidationThreshold = 70)
        } catch (expected: IllegalArgumentException) {
            softAboveHard = true
        }
        assertTrue("Soft invalidation must stay below hard invalidation", softAboveHard)

        var zeroSpread = false
        try {
            ProductionExecutionConfig(maxSpreadPct = 0.0)
        } catch (expected: IllegalArgumentException) {
            zeroSpread = true
        }
        assertTrue("A zero spread ceiling would admit every book", zeroSpread)

        var invalidCooldown = false
        try {
            ProductionExecutionConfig(reentryCooldownMinutes = 0)
        } catch (expected: IllegalArgumentException) {
            invalidCooldown = true
        }
        assertTrue("The re-entry cooldown must be positive", invalidCooldown)
    }
}
