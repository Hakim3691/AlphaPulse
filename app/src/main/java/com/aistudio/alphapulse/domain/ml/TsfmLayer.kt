package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.data.model.ChartPoint
import com.aistudio.alphapulse.domain.quant.KlineData

/**
 * Time-Series Foundation Model (TSFM) layer - Phase 3 stub
 * Encapsulates future-proof interface for zero-shot/finetuned TSFM inference
 * (e.g., Chronos, TimesFM, Moirai) with quantized edge execution path.
 */
interface TsfmLayer {
    fun predict(
        klines: List<KlineData>,
        horizonMinutes: List<Int>
    ): List<ChartPoint>
}

class TsfmLayerStub : TsfmLayer {
    override fun predict(
        klines: List<KlineData>,
        horizonMinutes: List<Int>
    ): List<ChartPoint> {
        // Phase 3: Replace with actual TSFM inference
        return emptyList()
    }
}
