package com.aistudio.alphapulse.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.domain.campaign.ThresholdConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-60 — the configured thresholds survive a restart, and a corrupt payload fails closed.
 *
 * A gate configured in one session must still hold after the app is killed, or the tab is
 * theatre. A stored value that cannot be trusted (out of range, truncated) must load as the
 * shipped defaults instead of unlocking evidence the operator did not ask for.
 *
 * Spec Ref: Phase 2 C-60, §34
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThresholdStoreBddSpec {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `given nothing was ever configured when the store is read then the shipped defaults come back`() {
        assertEquals(ThresholdConfig.DEFAULT, SharedPreferencesThresholdStore(context).load())
    }

    @Test
    fun `given a saved configuration when a new store instance reads it then the exact values return`() {
        val saved = ThresholdConfig(
            floorPct = 90.0,
            minSettledFast = 25,
            minSettledSlow = 6,
            minSettledStrategy = 30
        )
        SharedPreferencesThresholdStore(context).save(saved)

        // A fresh instance stands in for the next app launch.
        assertEquals(saved, SharedPreferencesThresholdStore(context).load())
    }

    @Test
    fun `given a corrupt payload when the store is read then it fails closed to the shipped defaults`() {
        context.getSharedPreferences(
            SharedPreferencesThresholdStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putFloat(SharedPreferencesThresholdStore.KEY_FLOOR_PCT, 200.0f)
            .putInt(SharedPreferencesThresholdStore.KEY_MIN_SETTLED_FAST, 20)
            .putInt(SharedPreferencesThresholdStore.KEY_MIN_SETTLED_SLOW, 4)
            .putInt(SharedPreferencesThresholdStore.KEY_MIN_SETTLED_STRATEGY, 20)
            .apply()

        val loaded = SharedPreferencesThresholdStore(context).load()

        assertEquals(
            "An untrusted floor must not become the live gate",
            ThresholdConfig.DEFAULT,
            loaded
        )
    }
}
