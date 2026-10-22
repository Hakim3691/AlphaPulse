package com.aistudio.alphapulse.domain.ml

import com.aistudio.alphapulse.domain.quant.DepthTier

/**
 * Neural UQ-LOB model contract (spec §13).
 *
 * The Android application must expose a *production-capable* neural order-book
 * uncertainty model whose spatial L2/L3 inputs cover price distance, bid volume,
 * ask volume, bid/ask imbalance, depth velocity, order cancellation, order
 * replenishment, spread and trade flow, and whose outputs cover directional
 * probability, an uncertainty score, epistemic uncertainty, aleatoric
 * uncertainty and a confidence tier.
 *
 * [NeuralUqLobModel] executes that graph through [InferenceRuntime]; when no
 * deployable artifact + runtime pair is present the engine resolves to
 * [HeuristicUqLobModel], which is the deterministic approximation the source SPA
 * used and which the PRD explicitly keeps as a fallback.
 */

/** Confidence tier exposed alongside every UQ-LOB prediction. */
enum class ConfidenceTier {
    HIGH,
    MEDIUM,
    LOW,
    UNCERTAIN;

    companion object {
        /** Map a normalised uncertainty score in [0, 1] onto a tier. */
        fun fromUncertainty(uncertainty: Double): ConfidenceTier = when {
            uncertainty <= 0.15 -> HIGH
            uncertainty <= 0.35 -> MEDIUM
            uncertainty <= 0.55 -> LOW
            else -> UNCERTAIN
        }
    }
}

/**
 * Fixed tensor layout shared by the trainer export and the on-device graph so the
 * two never drift. Channel order is part of the model's I/O contract.
 */
object LobTensorLayout {

    /** Number of book levels represented per side. */
    const val LEVELS = 100

    /** Per-level channels: price distance, bid volume, ask volume. */
    const val PER_LEVEL_CHANNELS = 3

    /** Scalar channels appended after the per-level block. */
    const val SCALAR_CHANNELS = 6

    const val CHANNEL_PRICE_DISTANCE = 0
    const val CHANNEL_BID_VOLUME = 1
    const val CHANNEL_ASK_VOLUME = 2
    const val CHANNEL_IMBALANCE = 3
    const val CHANNEL_DEPTH_VELOCITY = 4
    const val CHANNEL_CANCELLATION = 5
    const val CHANNEL_REPLENISHMENT = 6
    const val CHANNEL_SPREAD = 7
    const val CHANNEL_TRADE_FLOW = 8

    /** Logical channel count (3 per-level + 6 scalar). */
    const val TOTAL_CHANNELS = PER_LEVEL_CHANNELS + SCALAR_CHANNELS

    /** Flattened element count of the on-device input tensor. */
    const val TENSOR_SIZE = (PER_LEVEL_CHANNELS * LEVELS) + SCALAR_CHANNELS
}

/**
 * Spatial L2/L3 feature vector fed to the UQ-LOB graph.
 *
 * Per-level channels are ordered best-level-first so a model trained on one
 * venue's tick convention still receives a monotonic depth axis. Scalars are
 * bounded to [-1, 1] where they represent a signed bias.
 */
data class LobFeatureVector(
    val priceDistance: List<Double>,
    val bidVolume: List<Double>,
    val askVolume: List<Double>,
    val bidAskImbalance: Double,
    val depthVelocity: Double,
    val cancellationRate: Double,
    val replenishmentRate: Double,
    val spread: Double,
    val tradeFlowImbalance: Double,
    val levels: Int = LobTensorLayout.LEVELS
) {
    /** Flattened tensor in [LobTensorLayout] channel order. */
    fun toTensor(): FloatArray {
        val n = levels.coerceAtLeast(1)
        val out = FloatArray(LobTensorLayout.TENSOR_SIZE)
        fun writeLevels(channel: Int, values: List<Double>) {
            val base = channel * n
            val count = minOf(n, values.size)
            for (i in 0 until count) out[base + i] = values[i].toFloat()
        }
        writeLevels(LobTensorLayout.CHANNEL_PRICE_DISTANCE, priceDistance)
        writeLevels(LobTensorLayout.CHANNEL_BID_VOLUME, bidVolume)
        writeLevels(LobTensorLayout.CHANNEL_ASK_VOLUME, askVolume)

        val scalarBase = LobTensorLayout.PER_LEVEL_CHANNELS * n
        val scalars = doubleArrayOf(
            bidAskImbalance, depthVelocity, cancellationRate,
            replenishmentRate, spread, tradeFlowImbalance
        )
        for (i in scalars.indices) {
            val index = scalarBase + i
            if (index < out.size) out[index] = scalars[i].toFloat()
        }
        return out
    }

    companion object {
        val EMPTY = LobFeatureVector(
            priceDistance = emptyList(),
            bidVolume = emptyList(),
            askVolume = emptyList(),
            bidAskImbalance = 0.0,
            depthVelocity = 0.0,
            cancellationRate = 0.0,
            replenishmentRate = 0.0,
            spread = 0.0,
            tradeFlowImbalance = 0.0
        )
    }
}

/** One UQ-LOB forward result (spec §13 output contract). */
data class UqLobOutput(
    /** P(up) over the model's native horizon, in [0, 1]. */
    val directionalProbability: Double,
    /** Composite uncertainty in [0, 1]; higher means less trustworthy. */
    val uncertaintyScore: Double,
    /** Model-form uncertainty (reducible with more data / MC dropout). */
    val epistemicUncertainty: Double,
    /** Data-noise uncertainty (irreducible for the given book state). */
    val aleatoricUncertainty: Double,
    val confidenceTier: ConfidenceTier,
    val backend: InferenceBackend,
    val modelVersion: String,
    val isNeural: Boolean
) {
    /** Legacy 0.1..1.0 "confidence" scale used by the confluence-α blend. */
    val confidence: Double get() = (1.0 - uncertaintyScore).coerceIn(0.1, 1.0)

    /** Signed directional bias in [-1, 1]: +1 fully bullish, -1 fully bearish. */
    val directionalBias: Double get() = (2.0 * directionalProbability) - 1.0
}

/** Input to any UQ-LOB implementation. */
data class LobInferenceRequest(
    val symbol: String,
    val spotPrice: Double,
    val bids: List<DepthTier>,
    val asks: List<DepthTier>,
    val features: LobFeatureVector,
    val baseVolatility: Double
)

/** A UQ-LOB implementation: neural graph or deterministic fallback. */
interface UqLobModel {
    /** Model identifier used for version stamping (§55). */
    val modelId: String

    /** Model version used for version stamping (§55). */
    val version: String

    /** Backend actually used for this implementation. */
    val backend: InferenceBackend

    /** True only when this implementation executes a neural graph. */
    val isNeural: Boolean

    fun infer(request: LobInferenceRequest): UqLobOutput
}
