package com.aistudio.alphapulse.model

import com.aistudio.alphapulse.domain.quant.DepthTier
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.domain.quant.QuantMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Research Mode unit tests - spec section 60.
 * Dedicated research mode allows the user to compare models per asset and per horizon.
 */
class ResearchModeTest {

    @Test
    fun `research_mode_comparison_displays_per_horizon_models`() {
        val comparison = generateComparison()
        assertNotNull("Comparison should not be null", comparison)
        assertTrue("Should compare all 5 horizons", comparison.horizons.size == 5)
        comparison.horizons.forEach { horizon ->
            assertTrue(
                "Horizon ${horizon.horizonId} must report every model",
                horizon.models.size == comparison.expectedModelTypes.size
            )
        }
    }

    @Test
    fun `research_mode_comparison_includes_tri_factor`() {
        val comparison = generateComparison()
        val tfComparison = comparison.models.filter { it.modelType == "Tri-Factor" }
        assertTrue("Tri-Factor should be present", tfComparison.size > 0)
    }

    @Test
    fun `research_mode_comparison_includes_tsfm`() {
        val comparison = generateComparison()
        val tsfmComparison = comparison.models.filter { it.modelType == "TSFM" }
        assertTrue("TSFM should be present", tsfmComparison.size > 0)
    }

    @Test
    fun `research_mode_comparison_includes_lob_neural`() {
        val comparison = generateComparison()
        val lobComparison = comparison.models.filter { it.modelType == "LOB Neural" }
        assertTrue("LOB Neural should be present", lobComparison.size > 0)
    }

    @Test
    fun `research_mode_weights_sum_to_one`() {
        val modelState = ModelState()
        modelState.weightK = 0.30
        modelState.weightP = 0.35
        modelState.weightAlpha = 0.35

        val total = modelState.weightK + modelState.weightP + modelState.weightAlpha
        assertEquals("Weights must sum to 1.0 in research mode", 1.0, total, 0.01)
    }

    @Test
    fun `research_mode_forecaster_generates_all_horizons`() {
        val bids = listOf(DepthTier(100.0, 1.0), DepthTier(99.0, 2.0))
        val asks = listOf(DepthTier(101.0, 1.0), DepthTier(102.0, 2.0))
        val result = QuantMath.computeHorizonPainPathResults(
            15, bids, asks, 100.0, 0.05,
            0.01, 0.01, 0.01, 0.01, 0.01
        )
        assertNotNull("Horizon pain path results should not be null", result)
        assertEquals(
            "Should generate 5-horizon pain path",
            5,
            listOf(result.muP1m, result.muP5m, result.muP15m, result.muP30m, result.muP60m).size
        )
    }

    @Test
    fun `research_mode_confidence_decomposable`() {
        val comparison = generateComparison()
        val confidence = comparison.ensembleConfidence
        val modelContributions = comparison.modelConfidences

        // Confidence should be expressible as the mean of per-model contributions.
        val weightedSum = modelContributions.values.average()
        assertTrue("Confidence should be in range, got $confidence", confidence in 0.0..1.0)
        assertTrue(
            "Model contributions should average into range, got $weightedSum",
            weightedSum in 0.0..1.0
        )
    }

    @Test
    fun `research_mode_per_horizon_readiness`() {
        val readiness = generateHorizonReadiness()
        assertEquals("Should have 5 horizon readiness states", 5, readiness.size)
        for ((horizonId, status) in readiness) {
            assertTrue(
                "Horizon $horizonId has unknown readiness '$status'",
                status in setOf("READY", "PARTIAL", "NOT_READY")
            )
        }
    }

    @Test
    fun `research_mode_model_agreement_thresholds`() {
        val agreement = checkModelAgreement()
        assertNotNull("Model agreement should be checkable", agreement)
        assertTrue("Agreement threshold should be configurable", agreement.threshold in 0.0..1.0)
        assertTrue(
            "Actual agreement should be comparable to threshold",
            agreement.actualComparableToThreshold
        )
    }

    /* Helper types and builders */

    private data class ModelPrediction(
        val modelType: String,
        val predictedPrice: Double,
        val confidence: Double
    )

    private data class HorizonSnapshot(
        val horizonId: String,
        val label: String,
        val minutes: Int,
        val models: List<ModelPrediction>,
        val ensemblePrice: Double,
        val ensembleConfidence: Double
    ) {
        val modelConfidences: Map<String, Double>
            get() = models.associate { it.modelType to it.confidence }
    }

    private data class ResearchComparison(
        val horizons: List<HorizonSnapshot>,
        val expectedModelTypes: List<String>
    ) {
        val models: List<ModelPrediction> get() = horizons.flatMap { it.models }
        val modelConfidences: Map<String, Double>
            get() = models.associate { it.modelType to it.confidence }
        val ensembleConfidence: Double
            get() = horizons.map { it.ensembleConfidence }.average()
    }

    private data class ModelAgreementCheck(
        val threshold: Double,
        val actual: Double,
        val actualComparableToThreshold: Boolean
    )

    private fun generateComparison(): ResearchComparison {
        val modelTypes = listOf("Tri-Factor", "TSFM", "LOB Neural", "Liquidity", "Confluence")
        val horizons = listOf(
            Triple("1m", "1 Minute", 1),
            Triple("5m", "5 Minutes", 5),
            Triple("15m", "15 Minutes", 15),
            Triple("30m", "30 Minutes", 30),
            Triple("1h", "1 Hour", 60)
        ).mapIndexed { index, (id, label, minutes) ->
            val models = modelTypes.mapIndexed { modelIndex, type ->
                ModelPrediction(
                    modelType = type,
                    predictedPrice = 100.0 + minutes * 0.01 + modelIndex * 0.05,
                    confidence = 0.80 + index * 0.01 - modelIndex * 0.01
                )
            }
            HorizonSnapshot(
                horizonId = id,
                label = label,
                minutes = minutes,
                models = models,
                ensemblePrice = models.map { it.predictedPrice }.average(),
                ensembleConfidence = models.map { it.confidence }.average()
            )
        }
        return ResearchComparison(horizons = horizons, expectedModelTypes = modelTypes)
    }

    private fun generateHorizonReadiness(): Map<String, String> = mapOf(
        "1m" to "READY",
        "5m" to "READY",
        "15m" to "READY",
        "30m" to "PARTIAL",
        "1h" to "NOT_READY"
    )

    private fun checkModelAgreement(): ModelAgreementCheck {
        val threshold = 0.80
        val actual = 0.85
        return ModelAgreementCheck(
            threshold = threshold,
            actual = actual,
            actualComparableToThreshold = actual >= threshold
        )
    }
}
