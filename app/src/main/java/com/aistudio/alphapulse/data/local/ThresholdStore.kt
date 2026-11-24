package com.aistudio.alphapulse.data.local

import android.content.Context
import com.aistudio.alphapulse.domain.campaign.ThresholdConfig

/**
 * C-59 — where the configured thresholds live between launches.
 *
 * Stub phase.
 *
 * Spec Ref: Phase 2 C-59, §34
 */
interface ThresholdStore {
    fun load(): ThresholdConfig
    fun save(config: ThresholdConfig)
}

/** The shipped store, in the same private preferences idiom as the other small stores. */
class SharedPreferencesThresholdStore(context: Context) : ThresholdStore {

    override fun load(): ThresholdConfig = ThresholdConfig.DEFAULT

    override fun save(config: ThresholdConfig) {
        // Stub.
    }

    companion object {
        const val PREFERENCES_NAME = "alphapulse_thresholds"
    }
}
