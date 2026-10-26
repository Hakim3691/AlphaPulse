package com.aistudio.alphapulse.service

import java.util.Locale

/**
 * Notification Veto Logic - Section 58
 * 
 * Suppression logic for AlphaPulse notifications per SPEC §58:
 * "notification veto with logged reason when LOB uncertainty extreme,
 * disagreement high, freshness low, or required model unavailable"
 * 
 * A notification should be vetoed (suppressed) if ANY of the following conditions
 * are met:
 * 1. LOB uncertainty extreme + model disagreement high
 * 2. Data freshness below threshold (stale data)
 * 3. Required model unavailable
 * 
 * When vetoed, the system logs the reason for transparency but does not show
 * the notification, preventing misleading stale or unreliable signals to the user.
 */
object NotificationVeto {

    /** Threshold for considering LOB uncertainty "extreme" */
    private const val LOB_UNCERTAINTY_EXTREME_THRESHOLD = 0.8f

    /** Threshold for considering model disagreement "high" */
    private const val MODEL_DISAGREEMENT_HIGH_THRESHOLD = 0.7f

    /** Maximum age for data to be considered "fresh" (in milliseconds) */
    private const val DATA_FRESHNESS_MAX_AGE_MS = 5 * 60 * 1000 // 5 minutes

    /** Checks whether a notification should be vetoed suppressed based on current state.
     * 
     * @param lobUncertainty Extreme if >= LOB_UNCERTAINTY_EXTREME_THRESHOLD (0.0 to 1.0)
     * @param modelDisagreement High if >= MODEL_DISAGREEMENT_HIGH_THRESHOLD (0.0 to 1.0)
     * @param lastDataTimestampMs Timestamp of last received data (0 if never received)
     * @param requiredModelAvailable true if the currently required model is available
     * @return Pair of (shouldVeto: Boolean, reason: String? - null if no veto)
     */
    fun checkVeto(
        lobUncertainty: Float,
        modelDisagreement: Float,
        lastDataTimestampMs: Long,
        requiredModelAvailable: Boolean
    ): Pair<Boolean, String?> {
        // Condition 1: LOB uncertainty extreme + model disagreement high
        val condition1 = lobUncertainty >= LOB_UNCERTAINTY_EXTREME_THRESHOLD &&
            modelDisagreement >= MODEL_DISAGREEMENT_HIGH_THRESHOLD

        if (condition1) {
            val lobUncFormatted = String.format(Locale.US, "%.1f", lobUncertainty)
            val modelDisagreeFormatted = String.format(Locale.US, "%.1f", modelDisagreement)
            return Pair(true, "LOB uncertainty extreme " + lobUncFormatted + "\n + model disagreement high " + modelDisagreeFormatted)
        }

        // Condition 2: Data freshness below threshold
        val dataTooOld = lastDataTimestampMs > 0 &&
            System.currentTimeMillis() - lastDataTimestampMs > DATA_FRESHNESS_MAX_AGE_MS

        if (dataTooOld) {
            val ageMinutes = (System.currentTimeMillis() - lastDataTimestampMs) / 60000
            return Pair(true, "Data freshness too old " + ageMinutes + " min old")
        }

        // Condition 3: Required model unavailable
        if (!requiredModelAvailable) {
            return Pair(true, "Required model unavailable")
        }

        // No veto conditions met
        return Pair(false, null)
    }
}