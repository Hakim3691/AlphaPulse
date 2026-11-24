package com.aistudio.alphapulse.domain.campaign

/** One row of the Guardrails Thresholds surface. Stub phase. */
data class ThresholdRow(
    val layer: EvidenceLayer,
    val floorLabel: String,
    val samplesLabel: String,
    val effective: String
)

object ThresholdPresentation {

    fun rows(
        config: ThresholdConfig,
        verdict: LayeredEvidenceVerdict?
    ): List<ThresholdRow> = emptyList()
}
