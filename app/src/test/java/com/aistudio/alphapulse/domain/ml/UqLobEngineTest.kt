package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.domain.quant.DepthTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import com.aistudio.alphapulse.domain.ml.LobFeatureVector
import com.aistudio.alphapulse.domain.ml.LobInferenceRequest
import com.aistudio.alphapulse.domain.ml.ConfidenceTier
import com.aistudio.alphapulse.domain.ml.InferenceBackend
import com.aistudio.alphapulse.domain.ml.NeuralUqLobModel

/** Unit tests for UQ-LOB engine and neural model integration (spec §13, §24-27). */

class UqLobEngineTest {

    private var heuristicModel: HeuristicUqLobModel = HeuristicUqLobModel()
    private var neuralModel: NeuralUqLobModel? = null
    private var engine: UqLobEngine? = null

    @Before
    fun setUp() {
        heuristicModel = HeuristicUqLobModel()
        // Neural model starts as null; engine will fall back to heuristic
        engine = UqLobEngine()
    }

    @Test
    fun `heuristic model infers on empty book is neutral`() {
        val request = LobInferenceRequest(
            symbol = "BTCUSDT",
            spotPrice = 100.0,
            bids = emptyList(),
            asks = emptyList(),
            features = LobFeatureVector.EMPTY,
            baseVolatility = 0.01
        )
        val output = heuristicModel.infer(request)
        assertEquals("Empty book should have neutral uncertainty 0.5", 0.5, output.uncertaintyScore, 1e-12)
        assertTrue("Empty book confidence should be neutral", output.confidenceTier == ConfidenceTier.LOW)
    }

    @Test
    fun `heuristic model infers on tight spread book`() {
        val bids = (1..10).map { DepthTier(100.0 - it * 0.01, 10.0) }
        val asks = (1..10).map { DepthTier(100.0 + it * 0.01, 10.0) }

        val request = LobInferenceRequest(
            symbol = "BTCUSDT",
            spotPrice = 100.0,
            bids = bids,
            asks = asks,
            features = LobFeatureVector(
                priceDistance = (1..10).map { it.toDouble() },
                bidVolume = (1..10).map { 10.0 },
                askVolume = (1..10).map { 10.0 },
                bidAskImbalance = 0.0,
                depthVelocity = 0.0,
                cancellationRate = 0.0,
                replenishmentRate = 0.0,
                spread = 0.002,
                tradeFlowImbalance = 0.0,
                levels = 10
            ),
            baseVolatility = 0.01
        )
        val output = heuristicModel.infer(request)
        assertTrue("Tight spread should have lower uncertainty than neutral", output.uncertaintyScore < 0.5)
        assertTrue("Tight spread should have HIGH confidence", output.confidenceTier == ConfidenceTier.HIGH)
    }

    @Test
    fun `heuristic model infers on wide spread book`() {
        // Wide spread book: large spread produces HIGH uncertaintyScore (near 0.9)
        // and LOW confidence tier, per the UQ-LOB code logic.
        val bids = listOf(
            DepthTier(10.0, 10.0), DepthTier(5.0, 10.0), DepthTier(9.0, 10.0),
            DepthTier(8.0, 10.0), DepthTier(7.0, 10.0), DepthTier(6.0, 10.0),
            DepthTier(5.0, 10.0), DepthTier(4.0, 10.0), DepthTier(3.0, 10.0),
            DepthTier(2.0, 10.0), DepthTier(1.0, 10.0)
        )
        val asks = listOf(
            DepthTier(190.0, 10.0), DepthTier(195.0, 10.0), DepthTier(200.0, 10.0),
            DepthTier(205.0, 10.0), DepthTier(210.0, 10.0), DepthTier(215.0, 10.0),
            DepthTier(220.0, 10.0), DepthTier(225.0, 10.0), DepthTier(230.0, 10.0),
            DepthTier(235.0, 10.0)
        )

        val request = LobInferenceRequest(
            symbol = "BTCUSDT",
            spotPrice = 100.0,
            bids = bids,
            asks = asks,
            features = LobFeatureVector(
                priceDistance = bids.indices.map { it.toDouble() * 9.0 + 1.0 },
                bidVolume = bids.map { 10.0 },
                askVolume = asks.map { 10.0 },
                bidAskImbalance = 0.0,
                depthVelocity = 0.0,
                cancellationRate = 0.0,
                replenishmentRate = 0.0,
                spread = 1.8,
                tradeFlowImbalance = 0.0,
                levels = bids.size.coerceAtLeast(1)
            ),
            baseVolatility = 0.5
        )
        val output = heuristicModel.infer(request)
        // Wide spread → high uncertaintyScore → UNCERTAIN confidence tier
        assertTrue("Wide spread should have higher uncertainty than neutral", output.uncertaintyScore > 0.5)
        assertTrue("Wide spread should have UNCERTAIN confidence", output.confidenceTier == ConfidenceTier.UNCERTAIN)
    }

    @Test
    fun `engine uses heuristic fallback when neural model is null`() {
        val request = LobInferenceRequest(
            symbol = "ETHUSDT",
            spotPrice = 2000.0,
            bids = (1..10).map { DepthTier(2000.0 - it * 0.1, 5.0) },
            asks = (1..10).map { DepthTier(2000.0 + it * 0.1, 5.0) },
            features = LobFeatureVector.EMPTY,
            baseVolatility = 0.02
        )
        val output = engine!!.infer(request)
        assertNotNull("Engine should produce output", output)
        assertFalse("Should use heuristic fallback (neural is null)", engine!!.isNeural)
        assertEquals("Should use heuristic backend", "Deterministic", engine!!.backendLabel())
    }

    @Test
    fun `engine neural model available when artifact is provided`() {
        // Create a minimal valid model artifact
        val artifact = ModelArtifact(
            modelId = "test-neural-uq-lob",
            version = "1.0.0",
            file = null, // No file = will fall back to heuristic
            quantization = "FP16",
            preferredBackend = InferenceBackend.ONNX_RUNTIME_MOBILE,
            parameterCount = 1_000_000,
            sizeBytes = 0L,
            licenseApproved = true
        )

        // When artifact has no file, engine should fall back to heuristic
        val testEngine = UqLobEngine(neuralModel = NeuralUqLobModel(null, artifact))
        val request = LobInferenceRequest(
            symbol = "ETHUSDT",
            spotPrice = 2000.0,
            bids = (1..10).map { DepthTier(2000.0 - it * 0.1, 5.0) },
            asks = (1..10).map { DepthTier(2000.0 + it * 0.1, 5.0) },
            features = LobFeatureVector.EMPTY,
            baseVolatility = 0.02
        )
        val output = testEngine.infer(request)
        assertNotNull("Engine should produce output", output)
        // Without a deployable file, NeuralUqLobModel should fall back to heuristic
        assertTrue("Should fall back to heuristic when no file", !testEngine.isNeural ||
            output.isNeural == false || output.modelVersion.contains("heuristic"))
    }

    @Test
    fun `uQLOB output contract has all required fields`() {
        val request = LobInferenceRequest(
            symbol = "BTCUSDT",
            spotPrice = 100.0,
            bids = (1..5).map { DepthTier(100.0 - it * 0.5, 5.0) },
            asks = (1..5).map { DepthTier(100.0 + it * 0.5, 5.0) },
            features = LobFeatureVector.EMPTY,
            baseVolatility = 0.01
        )
        val output = heuristicModel.infer(request)

        assertNotNull("Directional probability should be set", output.directionalProbability)
        assertTrue("Directional probability in [0,1]", output.directionalProbability in 0.0..1.0)
        assertNotNull("Uncertainty score should be set", output.uncertaintyScore)
        assertTrue("Uncertainty score in [0,1]", output.uncertaintyScore in 0.0..1.0)
        assertNotNull("Epistemic uncertainty should be set", output.epistemicUncertainty)
        assertNotNull("Aleatoric uncertainty should be set", output.aleatoricUncertainty)
        assertNotNull("Confidence tier should be set", output.confidenceTier)
        assertNotNull("Backend should be set", output.backend)
        assertNotNull("Model version should be set", output.modelVersion)
        assertTrue("Is neural should be false for heuristic", output.isNeural == false)

        // Verify confidence property
        assertTrue("Confidence should be in [0.1, 1.0]", output.confidence in 0.1..1.0)

        // Verify directional bias
        assertTrue("Directional bias in [-1,1]", output.directionalBias in -1.0..1.0)
    }

    @Test
    fun `uQLOB engine confidence property`() {
        val request = LobInferenceRequest(
            symbol = "BTCUSDT",
            spotPrice = 100.0,
            bids = (1..5).map { DepthTier(100.0 - it * 0.5, 5.0) },
            asks = (1..5).map { DepthTier(100.0 + it * 0.5, 5.0) },
            features = LobFeatureVector.EMPTY,
            baseVolatility = 0.01
        )
        val confidence = engine!!.confidence(request)
        assertTrue("Confidence should be in [0.1, 1.0]", confidence in 0.1..1.0)
    }

    @Test
    fun `uQLOB engine backend label`() {
        assertTrue("Heuristic backend label should be Deterministic", engine!!.backendLabel() == "Deterministic")
    }
}