package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.domain.quant.QuantMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic UQ-LOB approximation (spec §13 fallback tier).
 *
 * This is the SPA-era book heuristic: spread quality against realised volatility
 * plus a depth-imbalance penalty. It is retained deliberately — the PRD requires
 * the deterministic approximation to remain available as a fallback — but it is
 * now clearly labelled as such: [isNeural] is false and its uncertainty is split
 * into an aleatoric part (spread noise) and an epistemic part (book lean).
 */
class HeuristicUqLobModel : UqLobModel {

    override val modelId = "uq-lob-heuristic"
    override val version = "1.0"
    override val backend = InferenceBackend.DETERMINISTIC
    override val isNeural = false

    override fun infer(request: LobInferenceRequest): UqLobOutput {
        val bidNotional = request.bids.take(20).sumOf { it.price * it.quantity }
        val askNotional = request.asks.take(20).sumOf { it.price * it.quantity }
        val total = bidNotional + askNotional
        val imbalance = if (total > 0.0) abs(bidNotional - askNotional) / total else 1.0
        val directional = if (total > 0.0) (bidNotional / total).coerceIn(0.0, 1.0) else 0.5

        val confidence = QuantMath.computeUQLOBUncertainty(
            request.bids,
            request.asks,
            request.baseVolatility
        )
        val uncertainty = (1.0 - confidence).coerceIn(0.0, 1.0)

        // Book lean (one-sided depth) is model-form uncertainty: a lopsided book is
        // one a richer model could still resolve. Spread noise is irreducible for
        // the observed state, so it lands in the aleatoric bucket.
        val epistemic = (0.85 * imbalance * uncertainty).coerceIn(0.0, 1.0)
        val aleatoric = (uncertainty - epistemic).coerceIn(0.0, 1.0)
        val composite = (epistemic + aleatoric).coerceIn(0.0, 1.0)

        return UqLobOutput(
            directionalProbability = directional,
            uncertaintyScore = composite,
            epistemicUncertainty = epistemic,
            aleatoricUncertainty = aleatoric,
            confidenceTier = ConfidenceTier.fromUncertainty(composite),
            backend = backend,
            modelVersion = "$modelId@$version",
            isNeural = false
        )
    }
}

/**
 * Neural UQ-LOB model executed through an on-device [InferenceRuntime].
 *
 * Reads the spatial L2/L3 tensor defined by [LobTensorLayout] and produces the
 * §13 output contract. Two export styles are supported, and the model picks
 * whichever the artifact declares:
 *
 *  1. **Explicit heads** — outputs named `prob_up` / `aleatoric` / `epistemic`
 *     (or `directional_probability` / `uncertainty`) are read directly.
 *  2. **Logits** — a single logits output is passed through a softmax; aleatoric
 *     uncertainty is the predictive entropy and epistemic uncertainty is the
 *     mutual information across MC-dropout samples when the artifact declares a
 *     dropout control input, or the decision-boundary margin otherwise.
 *
 * An artifact that fails the §27 capability test is never used: the engine
 * resolves to [HeuristicUqLobModel] instead (see [UqLobEngine.of]).
 */
class NeuralUqLobModel(
    private val runtime: InferenceRuntime?,
    val artifact: ModelArtifact,
    private val mcDropoutSamples: Int = 8
) : UqLobModel {

    override val modelId: String = artifact.modelId
    override val version: String = artifact.version
    override val backend: InferenceBackend = runtime?.backend ?: InferenceBackend.DETERMINISTIC
    override val isNeural = true

    /** One graph execution: mean probability plus the uncertainty decomposition. */
    override fun infer(request: LobInferenceRequest): UqLobOutput {
        // Fall back to heuristic when runtime is null or session cannot open
        val session = if (runtime != null) {
            runtime.openSession(artifact)
                ?: return HeuristicUqLobModel().infer(request)
        } else {
            null
        }

        return try {
            val featureTensor = request.features.toTensor()
            val inputName = artifact.inputNames.firstOrNull() ?: DEFAULT_INPUT
            val dropoutInput = artifact.inputNames.firstOrNull { it.contains("dropout", ignoreCase = true) }

            val samples = ArrayList<Double>(mcDropoutSamples)
            var explicitEpistemic: Double? = null
            var explicitAleatoric: Double? = null

            val passes = if (dropoutInput != null && artifact.requiresDropoutSampling) {
                mcDropoutSamples.coerceAtLeast(2)
            } else {
                1
            }

            for (pass in 0 until passes) {
                val feed = HashMap<String, FloatArray>(2)
                feed[inputName] = featureTensor
                if (dropoutInput != null) {
                    feed[dropoutInput] = dropoutMask(pass)
                }
                val outputs = if (session != null) session.run(feed) else emptyMap()

                val prob = readProbability(outputs)
                    ?: return HeuristicUqLobModel().infer(request)
                samples.add(prob)

                if (explicitEpistemic == null) {
                    explicitEpistemic = readScalar(outputs, "epistemic")
                }
                if (explicitAleatoric == null) {
                    explicitAleatoric = readScalar(outputs, "aleatoric")
                }
            }

            val meanProb = samples.average()
            val aleatoric = explicitAleatoric ?: samples.map(::predictiveEntropy).average()
            val epistemic = explicitEpistemic ?: if (samples.size > 1) {
                // Mutual information: total predictive entropy minus mean sample entropy.
                max(0.0, predictiveEntropy(meanProb) - aleatoric)
            } else {
                // Single deterministic pass: the exported graph gives no dropout
                // spread, so fall back to the decision-boundary margin, which is
                // the standard confidence-based epistemic proxy.
                decisionBoundaryMargin(meanProb)
            }

            val composite = (epistemic + aleatoric).coerceIn(0.0, 1.0)
            UqLobOutput(
                directionalProbability = meanProb.coerceIn(0.0, 1.0),
                uncertaintyScore = composite,
                epistemicUncertainty = epistemic.coerceIn(0.0, 1.0),
                aleatoricUncertainty = aleatoric.coerceIn(0.0, 1.0),
                confidenceTier = ConfidenceTier.fromUncertainty(composite),
                backend = backend,
                modelVersion = "$modelId@$version/${artifact.quantization}",
                isNeural = true
            )
        } finally {
            runCatching { session?.close() }
        }
    }

    /** Deterministic Bernoulli-style dropout mask, seeded per pass for reproducibility. */
    private fun dropoutMask(pass: Int): FloatArray {
        val mask = FloatArray(LobTensorLayout.TENSOR_SIZE)
        var state = 0x9E3779B9.toInt() xor (pass * 0x85EBCA6B.toInt())
        for (i in mask.indices) {
            state = state * 1664525 + 1013904223
            mask[i] = if ((state ushr 16) and 1 == 0) 0f else 1f
        }
        return mask
    }

    private fun readProbability(outputs: Map<String, FloatArray>): Double? {
        readScalar(outputs, "prob_up")?.let { return it }
        readScalar(outputs, "directional_probability")?.let { return it }
        val logits = outputs.entries.firstOrNull { it.key.contains("logit", ignoreCase = true) }
            ?: return null
        return softmax(logits.value)
    }

    private fun readScalar(outputs: Map<String, FloatArray>, needle: String): Double? =
        outputs.entries.firstOrNull { it.key.equals(needle, ignoreCase = true) }
            ?.value?.firstOrNull()?.toDouble()

    /** Softmax over the first two logits → P(class 1). */
    private fun softmax(logits: FloatArray): Double {
        if (logits.isEmpty()) return 0.5
        val a = logits[0].toDouble()
        val b = if (logits.size > 1) logits[1].toDouble() else 0.0
        val m = max(a, b)
        val ea = exp(a - m)
        val eb = exp(b - m)
        return eb / (ea + eb)
    }

    /** Shannon entropy in nats of a Bernoulli probability, normalised to [0, 1]. */
    private fun predictiveEntropy(p: Double): Double {
        val q = p.coerceIn(1e-6, 1.0 - 1e-6)
        val h = -(q * ln(q) + (1.0 - q) * ln(1.0 - q))
        return (h / ln(2.0)).coerceIn(0.0, 1.0)
    }

    /** 1 - |2p - 1|: zero information at the decision boundary, 1 for a confident call. */
    private fun decisionBoundaryMargin(p: Double): Double = (1.0 - abs(2.0 * p - 1.0)).coerceIn(0.0, 1.0)

    companion object {
        const val DEFAULT_INPUT = "lob_features"
    }
}

/**
 * UQ-LOB engine: the single entry point the pipeline uses for order-book
 * uncertainty. Resolves the neural graph when one is deployable and otherwise
 * exposes the deterministic fallback, so callers never branch on availability.
 */
class UqLobEngine(
    val neuralModel: UqLobModel? = null,
    val fallback: UqLobModel = HeuristicUqLobModel()
) {
    /** Model that will serve the next request. */
    val activeModel: UqLobModel get() = neuralModel ?: fallback

    /** True when a neural graph (not the deterministic fallback) is serving. */
    val isNeural: Boolean get() = neuralModel != null

    fun infer(request: LobInferenceRequest): UqLobOutput = activeModel.infer(request)

    /** Legacy scalar bridge used by the confluence-α blend. */
    fun confidence(request: LobInferenceRequest): Double = infer(request).confidence

    /** Backend label for the UI/diagnostics surface. */
    fun backendLabel(): String = activeModel.backend.displayName

    companion object {
        /**
         * Resolve the best available UQ-LOB model for [artifact]: a neural session
         * when the artifact passes the capability test, the heuristic otherwise.
         */
        fun of(artifact: ModelArtifact?): UqLobEngine {
            if (artifact == null) return UqLobEngine()
            val runtime = ModelRuntimeRegistry.select(artifact) ?: return UqLobEngine()
            val capability = RuntimeCapabilityProbe.probe(artifact)
            return if (capability.executable) {
                UqLobEngine(neuralModel = NeuralUqLobModel(runtime, artifact))
            } else {
                UqLobEngine()
            }
        }
    }
}

/**
 * Derives the UQ-LOB feature vector from a reconstructed L2/L3 book plus the
 * order-flow counters maintained by the order-book engine (FR-006) and the
 * trade-tape layer (FR-008).
 */
object LobFeatureBuilder {

    fun build(
        spotPrice: Double,
        bids: List<com.aistudio.alphapulse.domain.quant.DepthTier>,
        asks: List<com.aistudio.alphapulse.domain.quant.DepthTier>,
        depthVelocity: Double,
        cancellationRate: Double,
        replenishmentRate: Double,
        tradeFlowImbalance: Double,
        levels: Int = LobTensorLayout.LEVELS
    ): LobFeatureVector {
        val mid = if (spotPrice > 0.0) spotPrice else {
            val bestBid = bids.maxOfOrNull { it.price } ?: 0.0
            val bestAsk = asks.minOfOrNull { it.price } ?: 0.0
            if (bestBid > 0.0 && bestAsk > 0.0) (bestBid + bestAsk) / 2.0 else 0.0
        }
        if (mid <= 0.0) return LobFeatureVector.EMPTY

        val bidNotional = bids.take(20).sumOf { it.price * it.quantity }
        val askNotional = asks.take(20).sumOf { it.price * it.quantity }
        val total = bidNotional + askNotional
        val imbalance = if (total > 0.0) (bidNotional - askNotional) / total else 0.0

        val basis = bids.firstOrNull()?.price?.let { abs(mid - it) }?.takeIf { it > 0.0 } ?: (mid * 1e-4)
        val bestBid = bids.maxOfOrNull { it.price } ?: mid
        val bestAsk = asks.minOfOrNull { it.price } ?: mid
        val spread = if (bestAsk > bestBid) (bestAsk - bestBid) / mid else 0.0

        val n = levels.coerceAtLeast(1)
        val priceDistance = DoubleArray(n)
        val bidVolume = DoubleArray(n)
        val askVolume = DoubleArray(n)
        val normaliser = bids.take(n).maxOfOrNull { it.quantity }?.takeIf { it > 0.0 } ?: 1.0
        val askNormaliser = asks.take(n).maxOfOrNull { it.quantity }?.takeIf { it > 0.0 } ?: 1.0

        for (i in 0 until n) {
            val bid = bids.getOrNull(i)
            val ask = asks.getOrNull(i)
            val bidDist = if (bid != null) abs(mid - bid.price) / basis else 0.0
            val askDist = if (ask != null) abs(ask.price - mid) / basis else 0.0
            priceDistance[i] = if (bid != null && ask != null) (bidDist + askDist) / 2.0 else max(bidDist, askDist)
            bidVolume[i] = (bid?.quantity ?: 0.0) / normaliser
            askVolume[i] = (ask?.quantity ?: 0.0) / askNormaliser
        }

        return LobFeatureVector(
            priceDistance = priceDistance.toList(),
            bidVolume = bidVolume.toList(),
            askVolume = askVolume.toList(),
            bidAskImbalance = imbalance.coerceIn(-1.0, 1.0),
            depthVelocity = depthVelocity,
            cancellationRate = cancellationRate.coerceIn(0.0, 1.0),
            replenishmentRate = replenishmentRate.coerceIn(0.0, 1.0),
            spread = spread,
            tradeFlowImbalance = tradeFlowImbalance.coerceIn(-1.0, 1.0),
            levels = n
        )
    }

    /** Euclidean norm of the flattened tensor; useful for drift/diagnostics checks. */
    fun magnitude(features: LobFeatureVector): Double {
        val tensor = features.toTensor()
        var sum = 0.0
        for (v in tensor) sum += v.toDouble() * v
        return sqrt(sum)
    }

    /** Symmetric divergence between two books used by the spoofing detector. */
    fun normalisedL1(a: LobFeatureVector, b: LobFeatureVector): Double {
        val ta = a.toTensor()
        val tb = b.toTensor()
        val n = min(ta.size, tb.size)
        if (n == 0) return 0.0
        var total = 0.0
        for (i in 0 until n) total += abs(ta[i] - tb[i])
        return total / n
    }
}
