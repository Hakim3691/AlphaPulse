package com.aistudio.alphapulse.data.local

import android.content.Context
import com.aistudio.alphapulse.domain.campaign.ThresholdConfig

/**
 * C-60 — where the configured thresholds live between launches.
 *
 * A gate configured in one session must still hold after the app is killed, or the
 * Thresholds tab is theatre. The store is deliberately dumb: it reads and writes the four
 * numbers, and the payload is validated on the way in, so a tampered or truncated value
 * loads as the shipped defaults rather than silently widening what counts as evidence.
 *
 * Spec Ref: Phase 2 C-60, §34
 */
interface ThresholdStore {
    fun load(): ThresholdConfig
    fun save(config: ThresholdConfig)
}

/** The shipped store, in the same private-preferences idiom as the other small stores. */
class SharedPreferencesThresholdStore(context: Context) : ThresholdStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * The persisted configuration, or the shipped defaults when nothing is stored or the
     * stored payload cannot be trusted.
     */
    override fun load(): ThresholdConfig {
        if (!prefs.contains(KEY_FLOOR_PCT)) return ThresholdConfig.DEFAULT
        val candidate = ThresholdConfig(
            floorPct = prefs.getFloat(KEY_FLOOR_PCT, Float.NaN).toDouble(),
            minSettledFast = prefs.getInt(KEY_MIN_SETTLED_FAST, -1),
            minSettledSlow = prefs.getInt(KEY_MIN_SETTLED_SLOW, -1),
            minSettledStrategy = prefs.getInt(KEY_MIN_SETTLED_STRATEGY, -1)
        )
        return if (candidate.validate().accepted) candidate else ThresholdConfig.DEFAULT
    }

    override fun save(config: ThresholdConfig) {
        prefs.edit()
            .putFloat(KEY_FLOOR_PCT, config.floorPct.toFloat())
            .putInt(KEY_MIN_SETTLED_FAST, config.minSettledFast)
            .putInt(KEY_MIN_SETTLED_SLOW, config.minSettledSlow)
            .putInt(KEY_MIN_SETTLED_STRATEGY, config.minSettledStrategy)
            .apply()
    }

    companion object {
        const val PREFERENCES_NAME = "alphapulse_thresholds"
        const val KEY_FLOOR_PCT = "floor_pct"
        const val KEY_MIN_SETTLED_FAST = "min_settled_fast"
        const val KEY_MIN_SETTLED_SLOW = "min_settled_slow"
        const val KEY_MIN_SETTLED_STRATEGY = "min_settled_strategy"
    }
}
