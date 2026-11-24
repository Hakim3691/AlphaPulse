package com.aistudio.alphapulse.domain.campaign

import java.util.Locale

/**
 * C-59 — the hit-rate thresholds, configured once and read live by every gate.
 *
 * Stub phase: the shape exists so the scenarios compile; the behaviour is filled in
 * after the specs have been seen to fail.
 *
 * Spec Ref: Phase 2 C-57/C-58/C-59, §22, §67, §73, §74
 */
data class ThresholdConfig(
    val floorPct: Double = DEFAULT_FLOOR_PCT,
    val minSettledFast: Int = DEFAULT_FAST_SAMPLES,
    val minSettledSlow: Int = DEFAULT_SLOW_SAMPLES,
    val minSettledStrategy: Int = DEFAULT_STRATEGY_SAMPLES
) {
    /** This config's minimum settled rows for [horizonId]. */
    fun minSettledFor(horizonId: String): Int = DEFAULT_SLOW_SAMPLES

    /** The floor as the operator reads it, e.g. `85.0%`. */
    fun describeFloor(): String = String.format(Locale.US, "%.1f%%", floorPct)

    /** The minimum settled samples, as the tab prints them for [layer]. */
    fun describeSamplesFor(layer: EvidenceLayer): String = DEFAULT_STRATEGY_SAMPLES.toString()

    fun validate(): ThresholdVerdict = ThresholdVerdict(accepted = true)

    companion object {
        const val MIN_FLOOR_PCT = 50.0
        const val MAX_FLOOR_PCT = 95.0
        const val DEFAULT_FLOOR_PCT = 85.0
        const val DEFAULT_FAST_SAMPLES = 20
        const val DEFAULT_SLOW_SAMPLES = 4
        const val DEFAULT_STRATEGY_SAMPLES = 20

        val DEFAULT = ThresholdConfig()

        fun clampFloor(pct: Double): Double = pct
    }
}

/** Whether a candidate configuration may be applied, and why not when it may not. */
data class ThresholdVerdict(val accepted: Boolean, val rejection: String? = null)

/** Which threshold a change touches; a reset touches all of them. */
enum class ThresholdField { FLOOR, FAST_SAMPLES, SLOW_SAMPLES, STRATEGY_SAMPLES, RESET }

/**
 * The one live configuration. Gates read it on every evaluation; the store installs it at
 * launch and after every accepted change.
 */
object Thresholds {

    @Volatile
    private var current: ThresholdConfig = ThresholdConfig.DEFAULT

    fun current(): ThresholdConfig = ThresholdConfig.DEFAULT

    fun install(config: ThresholdConfig) {
        // Stub.
    }

    fun reset() {
        // Stub.
    }
}
