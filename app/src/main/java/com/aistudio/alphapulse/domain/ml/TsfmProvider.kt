package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.data.model.ChartPoint

/** Provider of TSFM predictions. Each provider implements predict() and knows its own priority/ordering. */
interface TsfmProvider {
    /** Predict the next price move.
     *  @param input market-state tensor input
     *  @return probabilistic forecast with P10-P90 quantiles
     */
    fun predict(input: TsfmInput): TsfmPrediction

    /** Priority for provider selection. Higher priority = selected first. */
    val priority: Int

    /** Whether this provider is available on the current platform.
     *  e.g. GPU, NN libs installed, model weights loaded.
     */
    val isAvailable: Boolean

    /** Optional fallback provider to delegate to when this provider is unavailable. */
    val fallbackProvider: TsfmProvider?
}