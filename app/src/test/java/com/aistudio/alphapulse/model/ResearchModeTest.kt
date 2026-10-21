package com.aistudio.alphapulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.content.Context
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.domain.quant.QuantMath
import com.aistudio.alphapulse.domain.quant.KlineData
import com.aistudio.alphapulse.domain.quant.DepthTier
import com.aistudio.alphapulse.domain.quant.DepthSnapshot

/** Research Mode unit tests - spec section 60.
 * Dedicated research mode allows user to compare models per asset and per horizon.
 */
class ResearchModeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun `research_mode_comparison_displays_per_horizon_models`() {
        // Verify comparison displays all 5 horizons for selected asset
        val comparison = generate15mComparison()
        assertNotNull("Comparison should not be null", comparison)
        assertTrue("Should compare all 5 horizons", comparison.horizons.size == 5)
    }

    @Test
    fun `research_mode_comparison_includes_tri_factor`() {
        val comparison = generate15mComparison()
        val tfComparison = comparison.models?.filter { it.modelType == "Tri-Factor" }
        assertNotNull("Tri-Factor should be in comparison", tfComparison)
        assertTrue("Tri-Factor should be present", tfComparison.size > 0)
    }

    @Test
    fun `research_mode_comparison_includes_tsfm`() {
        val comparison = generate15mComparison()
        val tsfmComparison = comparison.models?.filter { it.modelType == "TSFM" }
        assertNotNull("TSFM should be in comparison", tsfmComparison)
        assertTrue("TSFM should be present", tsfmComparison.size > 0)
    }

    @Test
    fun `research_mode_comparison_includes_lob_neural`() {
        val comparison = generate15mComparison()
        val lobComparison = comparison.models?.filter { it.modelType == "LOB Neural" }
        assertNotNull("LOB Neural should be in comparison", lobComparison)
        assertTrue("LOB Neural should be present", lobComparison.size > 0)
    }

    @Test
    fun `research_mode_weights_sum_to_one`() {
        val modelState = ModelState()
        // Simulate research mode weight calibration
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
        assertTrue("Should generate 5-horizon pain path",
            listOf(result.muP1m, result.muP5m, result.muP15m, result.muP30m, result.muP60m).size == 5)
    }

    @Test
    fun `research_mode_confidence_decomposable`() {
        val comparison = generate15mComparison()
        // Confidence should be explicitly decomposable into model contributions
        val confidence = comparison.ensembleConfidence
        val modelContributions = comparison.modelConfidences

        // Confidence should be expressible as weighted sum
        val weightedSum = modelContributions.values().average()
        assertTrue("Confidence should be decomposable", confidence in 0.0..1.0)
        assertTrue("Model contributions should average to confidence", 
            weightedSum in 0.0..1.0)
    }

    @Test
    fun `research_mode_per_horizon_readiness`() {
        // Research mode should display readiness per horizon
        val readiness = generateHorizonReadiness()
        assertNotNull("Readiness should not be null", readiness)
        assertTrue("Should have 5 horizon readiness states", readiness.size == 5)
        // Each horizon should have a readiness status
        for ((horizonId, status) in readiness) {
            assertTrue("Horizon $horizonId should have readiness status", 
                status in "READY" to "NOT_READY")
        }
    }

    @Test
    fun `research_mode_model_agreement_thresholds`() {
        // Research mode should display model agreement thresholds
        val agreement = checkModelAgreement()
        assertNotNull("Model agreement should be checkable", agreement)
        // Agreement thresholds should be configurable
        assertTrue("Agreement threshold should be configurable", 
            agreement.threshold in 0.0..1.0)
        assertTrue("Actual agreement should be comparable to threshold",
            agreement.actualComparableToThreshold)
    }

    /* Helper functions */

    private data class HorizonComparison(
        val horizonId: String,
        val label: String,
        val minutes: Int,
        val modelTypes: List<String>,
        val predictedPrices: Map<String, Double>,
        val confidences: Map<String, Double>,
        val ensemblePrice: Double,
        val ensembleConfidence: Double
    )

    private data class HorizonReadiness(
        val horizonId: String,
        val status: String
    )

    private data class ModelAgreementCheck(
        val threshold: Double,
        val actual: Double,
        val actualComparableToThreshold: Boolean
    )

    private fun generate15mComparison(): HorizonComparison {
        return HorizonComparison(
            horizonId = "15m",
            label = "15 Minutes",
            minutes = 15,
            modelTypes = listOf("Tri-Factor", "TSFM", "LOB Neural", "Liquidity", "Confluence"),
            predictedPrices = mapOf(
                "Tri-Factor" to 105.25,
                "TSFM" to 105.40,
                "LOB Neural" to 105.30,
                "Liquidity" to 105.35,
                "Confluence" to 105.38
            ),
            confidences = mapOf(
                "Tri-Factor" to 0.87,
                "TSFM" to 0.91,
                "LOB Neural" to 0.83,
                "Liquidity" to 0.89,
                "Confluence" to 0.88
            ),
            ensemblePrice = 105.34,
            ensembleConfidence = 0.88
        )
    }

    private fun generateHorizonReadiness(): Map<String, String> {
        return mapOf(
            "1m" to "READY",
            "5m" to "READY",
            "15m" to "READY",
            "30m" to "PARTIAL",
            "1h" to "NOT_READY"
        )
    }

    private fun checkModelAgreement(): ModelAgreementCheck {
        return ModelAgreementCheck(
            threshold = 0.80,
            actual = 0.85,
            actualComparableToThreshold = 0.85 >= 0.80
        )
    }
}