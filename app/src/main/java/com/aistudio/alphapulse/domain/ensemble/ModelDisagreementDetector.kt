package com.aistudio.alphapulse.domain.ensemble

/**
 * Model Disagreement Detection Engine - Phase 3 stub
 * Detects divergence between ensemble model components (K, P, Alpha)
 * to surface regime uncertainty and trigger adaptive weighting.
 */
data class DisagreementMetrics(
    val kpDisagreement: Double = 0.0,
    val kAlphaDisagreement: Double = 0.0,
    val pAlphaDisagreement: Double = 0.0,
    val overallDisagreement: Double = 0.0,
    val isHighUncertainty: Boolean = false
)

interface ModelDisagreementDetector {
    fun computeDisagreement(
        muK: Double,
        muP: Double,
        muAlpha: Double
    ): DisagreementMetrics
}

class ModelDisagreementDetectorImpl : ModelDisagreementDetector {
    override fun computeDisagreement(
        muK: Double,
        muP: Double,
        muAlpha: Double
    ): DisagreementMetrics {
        // Phase 3: Implement KL divergence / Wasserstein-based disagreement detection
        val kpDisagreement = Math.abs(muK - muP)
        val kAlphaDisagreement = Math.abs(muK - muAlpha)
        val pAlphaDisagreement = Math.abs(muP - muAlpha)
        val overallDisagreement = (kpDisagreement + kAlphaDisagreement + pAlphaDisagreement) / 3.0
        val isHighUncertainty = overallDisagreement > 0.015 // ~1.5% threshold
        
        return DisagreementMetrics(
            kpDisagreement = kpDisagreement,
            kAlphaDisagreement = kAlphaDisagreement,
            pAlphaDisagreement = pAlphaDisagreement,
            overallDisagreement = overallDisagreement,
            isHighUncertainty = isHighUncertainty
        )
    }
}
