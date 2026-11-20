package com.aistudio.alphapulse.engine.proactive

/**
 * §34 — the production configuration asset.
 *
 * The Phase 2 specification fixes these defaults in the production configuration
 * rather than leaving them to a call site: the screening floor, the spread ceiling,
 * the ATR zone multiplier, the level-touch decay, the 45/70 invalidation thresholds,
 * the per-campaign account risk, the daily loss stop, the re-entry cooldown and the
 * WebSocket ping timeout. One object owns them so the monitoring loop, the
 * invalidation scorer and the pre-launch gate cannot drift apart, and a value the
 * spec did not licence fails at construction instead of at runtime.
 *
 * Spec Ref: Phase 2 §34
 */
data class ProductionExecutionConfig(
    val minQuoteVolume24hUsd: Double = MIN_QUOTE_VOLUME_24H_USD,
    val maxSpreadPct: Double = MAX_SPREAD_PCT,
    val atrZoneMultiplier: Double = ATR_ZONE_MULTIPLIER,
    val levelTouchDecayRate: Double = LEVEL_TOUCH_DECAY_RATE,
    val softInvalidationThreshold: Int = SOFT_INVALIDATION_THRESHOLD,
    val hardInvalidationThreshold: Int = HARD_INVALIDATION_THRESHOLD,
    val accountRiskPerCampaignPct: Double = ACCOUNT_RISK_PER_CAMPAIGN_PCT,
    val maxDailyLossR: Double = MAX_DAILY_LOSS_R,
    val reentryCooldownMinutes: Int = REENTRY_COOLDOWN_MINUTES,
    val wsPingTimeoutMs: Long = WS_PING_TIMEOUT_MS
) {
    init {
        require(minQuoteVolume24hUsd > 0.0) {
            "min_quote_volume_24h must be positive, was $minQuoteVolume24hUsd"
        }
        require(maxSpreadPct > 0.0 && maxSpreadPct <= 1.0) {
            "max_spread_pct must be a percentage in (0, 1], was $maxSpreadPct"
        }
        require(atrZoneMultiplier > 0.0) {
            "atr_zone_multiplier must be positive, was $atrZoneMultiplier"
        }
        require(levelTouchDecayRate in 0.0..1.0) {
            "level_touch_decay_rate must be a fraction in [0, 1], was $levelTouchDecayRate"
        }
        require(softInvalidationThreshold in 1 until hardInvalidationThreshold) {
            "soft_invalidation_threshold ($softInvalidationThreshold) must be below " +
                "hard_invalidation_threshold ($hardInvalidationThreshold)"
        }
        require(hardInvalidationThreshold <= 100) {
            "hard_invalidation_threshold is a 0-100 score, was $hardInvalidationThreshold"
        }
        require(accountRiskPerCampaignPct > 0.0 && accountRiskPerCampaignPct <= 1.0) {
            "account_risk_per_campaign must be a percentage in (0, 1], was $accountRiskPerCampaignPct"
        }
        require(maxDailyLossR > 0.0) {
            "max_daily_loss_r must be positive, was $maxDailyLossR"
        }
        require(reentryCooldownMinutes > 0) {
            "reentry_cooldown_duration must be positive, was $reentryCooldownMinutes"
        }
        require(wsPingTimeoutMs > 0L) {
            "ws_ping_timeout must be positive, was $wsPingTimeoutMs"
        }
    }

    /** The §34 cooldown as the millisecond deadline the campaign stores. */
    fun reentryCooldownMillis(): Long = reentryCooldownMinutes * 60_000L

    companion object {
        const val MIN_QUOTE_VOLUME_24H_USD = 5_000_000.0
        const val MAX_SPREAD_PCT = 0.10
        const val ATR_ZONE_MULTIPLIER = 0.25
        const val LEVEL_TOUCH_DECAY_RATE = 0.20
        const val SOFT_INVALIDATION_THRESHOLD = 45
        const val HARD_INVALIDATION_THRESHOLD = 70
        const val ACCOUNT_RISK_PER_CAMPAIGN_PCT = 0.50
        const val MAX_DAILY_LOSS_R = 2.0
        const val REENTRY_COOLDOWN_MINUTES = 30
        const val WS_PING_TIMEOUT_MS = 5_000L

        /** The shipped defaults, exactly as §34 publishes them. */
        val PRODUCTION = ProductionExecutionConfig()
    }
}
