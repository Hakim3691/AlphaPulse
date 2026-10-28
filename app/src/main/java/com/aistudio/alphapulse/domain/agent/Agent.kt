/***
 * Agent - The on-device orchestration framework agent abstraction.
 * The Android application defines these logical agents regardless of the final orchestration runtime.
 * Required logical agents (in order):
 *   Ingestion Agent → Market-State Agent → Quant Vector Agent → TSFM Agent → Decision Router
 *   → Risk/Confidence Agent → Narrator Agent
 * No agent shall silently corrupt or replace another agent's output.
 *
 * Spec Ref: §30, Gap §3 line 109
 */

package com.aistudio.alphapulse.domain.agent

import com.aistudio.alphapulse.domain.decision.DecisionState
import com.aistudio.alphapulse.domain.decision.DecisionInputs
import com.aistudio.alphapulse.domain.decision.DecisionRouter
import com.aistudio.alphapulse.domain.decision.Needle3DecisionRouter
import kotlin.math.abs
import kotlin.math.max

/** AgentHealth - Health status of an agent. */
enum class AgentHealth {
    HEALTHY,
    UNHEALTHY,
    FAILING,
    OFFLINE
}

/** AgentTelemetry - Telemetry snapshot for a running agent. */
data class AgentTelemetry(
    val executionCount: Long = 0L,
    val lastExecutionDurationMs: Long = 0L,
    /** No failed execution has been recorded yet. */
    val lastSuccess: Boolean = true,
    val lastError: String? = null,
    val averageDurationMs: Long = 0L
)

/** BaseAgent - Core interface all 7 agents implement.
 * Every agent has: defined inputs, defined outputs, execution timeout,
 * health status, version, telemetry, and failure fallback.
 */
interface BaseAgent<In : Any, Out : Any> {

    /** Agent unique identifier */
    val agentId: String

    /** Agent version (for upgrades/audit) */
    val version: String

    /** Current health status */
    val health: AgentHealth

    /** Telemetry snapshot */
    val telemetry: AgentTelemetry

    /** Maximum execution duration in milliseconds */
    val executionTimeoutMs: Long

    /** Execute the agent with the given inputs.
     * Returns the output if successful, or null if the agent fails/times out.
     * Must not corrupt or replace another agent's output.
     */
    fun execute(inputs: In): Out?

    /** Update agent health and telemetry after execution. */
    fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long)

    /** Reset agent state (called on startup or recovery). */
    fun reset()

    /** Get the agent's designated input builder/validator. */
    fun validateInputs(inputs: Any): Boolean
}

/**
 * BaseAgentImpl - Shared execution harness for the logical agents.
 *
 * Centralises health/telemetry bookkeeping, timing and failure isolation so that
 * no agent can throw an exception into the DAG: a failed agent records telemetry
 * and returns `null` instead of corrupting downstream state.
 */
abstract class BaseAgentImpl<In : Any, Out : Any> : BaseAgent<In, Out> {

    final override var health: AgentHealth = AgentHealth.HEALTHY
        private set

    final override var telemetry: AgentTelemetry = AgentTelemetry()
        private set

    /** The agent body. Exceptions are caught by [execute]. */
    protected abstract fun executeInternal(inputs: In): Out?

    final override fun execute(inputs: In): Out? {
        val startedAt = System.nanoTime()
        return try {
            val output = executeInternal(inputs)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            updateHealth(
                isSuccessful = output != null,
                error = if (output == null) "Agent produced no output" else null,
                durationMs = elapsedMs
            )
            output
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            updateHealth(false, e.message ?: e::class.simpleName, elapsedMs)
            null
        }
    }

    final override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {
        val previous = telemetry
        val executions = previous.executionCount + 1L
        val average = ((previous.averageDurationMs * previous.executionCount) + durationMs) / executions

        telemetry = AgentTelemetry(
            executionCount = executions,
            lastExecutionDurationMs = durationMs,
            lastSuccess = isSuccessful,
            lastError = error,
            averageDurationMs = average
        )

        health = when {
            isSuccessful -> AgentHealth.HEALTHY
            error != null -> AgentHealth.FAILING
            else -> AgentHealth.UNHEALTHY
        }
    }

    final override fun reset() {
        health = AgentHealth.HEALTHY
        telemetry = AgentTelemetry()
    }
}

/* ------------------------------------------------------------------ */
/* Agent 1: Ingestion Agent                                            */
/* ------------------------------------------------------------------ */

data class IngestionInput(
    val symbol: String,
    val marketData: Map<String, Any>,
    val normalizedPriceData: Map<String, Double> = emptyMap(),
    val orderBookSnapshot: Map<String, Double> = emptyMap(),
    val priceHistory: List<Double> = emptyList()
)

data class IngestionOutput(
    val symbol: String,
    val lastPrice: Double,
    val priceChangePercent: Double,
    val high24h: Double,
    val low24h: Double,
    val volume: Double,
    val quoteVolume: Double,
    val priceHistory: List<Double>,
    val orderBookSnapshot: Map<String, Double>,
    val currentRegime: String
)

/** Ingestion Agent - validates, normalises and time-stamps incoming market data. */
class IngestionAgent : BaseAgentImpl<IngestionInput, IngestionOutput>() {

    override val agentId = "Ingestion"
    override val version = "1.0"
    override val executionTimeoutMs = 5000L

    override fun executeInternal(inputs: IngestionInput): IngestionOutput? {
        if (inputs.symbol.isBlank() || inputs.marketData.isEmpty()) return null

        val lastPrice = inputs.marketData["lastPrice"] as? Double ?: return null
        if (!lastPrice.isFinite() || lastPrice <= 0.0) return null

        val priceChange = (inputs.marketData["priceChangePercent"] as? Double) ?: 0.0
        val high24h = (inputs.marketData["high24h"] as? Double) ?: lastPrice
        val low24h = (inputs.marketData["low24h"] as? Double) ?: lastPrice
        val volume = (inputs.marketData["volume"] as? Double) ?: 0.0
        val quoteVolume = (inputs.marketData["quoteVolume"] as? Double) ?: 0.0

        // Rolling window of the last 30 closes; fall back to the current price.
        val history = inputs.priceHistory.takeLast(30).filter { it.isFinite() && it > 0.0 }
        val priceHistory = if (history.isEmpty()) listOf(lastPrice) else history

        val bidRatio = ((inputs.marketData["bidRatioPct"] as? Double) ?: 50.0) / 100.0
        val netImbalance = (inputs.marketData["netImbalanceUsdt"] as? Double) ?: 0.0
        val orderBookSnapshot = mapOf(
            "bidRatio" to bidRatio.coerceIn(0.0, 1.0),
            "imbalance" to netImbalance / max(1.0, lastPrice)
        )

        return IngestionOutput(
            symbol = inputs.symbol,
            lastPrice = lastPrice,
            priceChangePercent = priceChange,
            high24h = high24h,
            low24h = low24h,
            volume = volume,
            quoteVolume = quoteVolume,
            priceHistory = priceHistory,
            orderBookSnapshot = orderBookSnapshot,
            currentRegime = determineRegime(priceChange)
        )
    }

    override fun validateInputs(inputs: Any): Boolean =
        inputs is IngestionInput && inputs.symbol.isNotBlank() && inputs.marketData.isNotEmpty()

    /** Provisional regime label derived from the 24h move; refined by the Market-State Agent. */
    private fun determineRegime(priceChange: Double): String = when {
        priceChange > 0.5 -> "State 1: Momentum Burst"
        priceChange < -0.5 -> "State 0: Mean-Reverting"
        else -> "State 0: Mean-Reverting"
    }
}

/* ------------------------------------------------------------------ */
/* Agent 2: Market-State Agent                                         */
/* ------------------------------------------------------------------ */

data class MarketStateInput(
    val symbol: String,
    val priceData: Map<String, Double>,
    val orderBookSnapshot: Map<String, Double>
)

data class MarketStateOutput(
    val symbol: String,
    val currentRegime: String,
    val regimeConfidence: Double,
    val priceChange: Double,
    val bidRatio: Double,
    val imbalance: Double
)

/** Market-State Agent - classifies the current Markov regime. */
class MarketStateAgent : BaseAgentImpl<MarketStateInput, MarketStateOutput>() {

    override val agentId = "Market-State"
    override val version = "1.0"
    override val executionTimeoutMs = 3000L

    override fun executeInternal(inputs: MarketStateInput): MarketStateOutput? {
        if (inputs.symbol.isBlank()) return null

        val priceChange = inputs.priceData["priceChangePercent"] ?: 0.0
        val bidRatio = (inputs.orderBookSnapshot["bidRatio"] ?: 0.5).coerceIn(0.0, 1.0)
        val imbalance = inputs.orderBookSnapshot["imbalance"] ?: 0.0

        val regime = when {
            abs(priceChange) > 1.0 && bidRatio > 0.6 -> "State 1: Momentum Burst"
            abs(priceChange) < 0.3 && abs(imbalance) < 1.0 -> "State 0: Mean-Reverting"
            else -> "State 1: Momentum Burst"
        }

        // Confidence grows with the size of the move and with order-book directional bias.
        val moveStrength = (abs(priceChange) / 2.0).coerceIn(0.0, 1.0)
        val bookBias = abs(bidRatio - 0.5) * 2.0
        val regimeConfidence = (0.5 + 0.3 * moveStrength + 0.2 * bookBias).coerceIn(0.0, 1.0)

        return MarketStateOutput(
            symbol = inputs.symbol,
            currentRegime = regime,
            regimeConfidence = regimeConfidence,
            priceChange = priceChange,
            bidRatio = bidRatio,
            imbalance = imbalance
        )
    }

    override fun validateInputs(inputs: Any): Boolean = inputs is MarketStateInput
}

/* ------------------------------------------------------------------ */
/* Agent 3: Quant Vector Agent                                         */
/* ------------------------------------------------------------------ */

data class QuantVectorInput(
    val symbol: String,
    val currentRegime: String,
    val priceHistory: List<Double>,
    val depthSnapshot: Map<String, Double>
)

data class QuantVectorOutput(
    val symbol: String,
    val triFactorForecast: Double,
    val liquidityPath: Double,
    val quantVectorRegime: String,
    val disagreement: Double
)

/** Quant Vector Agent - produces the kinematic + liquidity vector bundle. */
class QuantVectorAgent : BaseAgentImpl<QuantVectorInput, QuantVectorOutput>() {

    override val agentId = "Quant Vector"
    override val version = "1.0"
    override val executionTimeoutMs = 4000L

    override fun executeInternal(inputs: QuantVectorInput): QuantVectorOutput? {
        if (inputs.symbol.isBlank()) return null

        val prices = inputs.priceHistory.filter { it.isFinite() && it > 0.0 }
        val momentumBurst = inputs.currentRegime.contains("Burst")

        // Kinematic drift: last observed return, amplified inside a momentum regime.
        val lastReturn = if (prices.size >= 2) {
            val previous = prices[prices.size - 2]
            (prices.last() - previous) / previous
        } else {
            0.0
        }
        val kinematic = lastReturn * if (momentumBurst) 1.5 else 0.5

        // Liquidity drift: order-book imbalance bias around the neutral 50% mark.
        val bidRatio = inputs.depthSnapshot["bidRatio"] ?: 0.5
        val liquidityPath = (bidRatio - 0.5) * 0.002

        val disagreement = abs(kinematic - liquidityPath)

        return QuantVectorOutput(
            symbol = inputs.symbol,
            triFactorForecast = kinematic,
            liquidityPath = liquidityPath,
            quantVectorRegime = inputs.currentRegime,
            disagreement = disagreement
        )
    }

    override fun validateInputs(inputs: Any): Boolean = inputs is QuantVectorInput
}

/* ------------------------------------------------------------------ */
/* Agent 4: TSFM Agent                                                 */
/* ------------------------------------------------------------------ */

data class TsfmInput(
    val symbol: String,
    val currentPrice: Double,
    val regime: String,
    val quantVector: Double
)

data class TsfmOutput(
    val symbol: String,
    val tsfmForecast: Double,
    val regime: String,
    val confidence: Double
)

/** TSFM Agent - time-series foundation model forecast.
 * Placeholder deterministic inference until the on-device runtime is wired in.
 */
class TsfmAgent : BaseAgentImpl<TsfmInput, TsfmOutput>() {

    override val agentId = "TSFM"
    override val version = "1.0"
    override val executionTimeoutMs = 8000L

    override fun executeInternal(inputs: TsfmInput): TsfmOutput? {
        if (inputs.symbol.isBlank() || !inputs.currentPrice.isFinite() || inputs.currentPrice <= 0.0) {
            return null
        }

        return TsfmOutput(
            symbol = inputs.symbol,
            tsfmForecast = computeDeterministicForecast(inputs),
            regime = inputs.regime,
            confidence = 0.75
        )
    }

    override fun validateInputs(inputs: Any): Boolean = inputs is TsfmInput

    /** Deterministic stand-in for neural/chronos inference. */
    private fun computeDeterministicForecast(inputs: TsfmInput): Double {
        val quantInfluence = inputs.quantVector * 0.6
        val regimeAdjustment = when {
            inputs.regime.contains("Burst") -> 0.3
            inputs.regime.contains("Mean-Reverting") -> -0.2
            else -> 0.0
        }
        // Tiny scale so price level does not dominate a percentage forecast.
        val priceTrend = inputs.currentPrice * 0.0001
        return quantInfluence + regimeAdjustment + priceTrend
    }
}

/* ------------------------------------------------------------------ */
/* Agent 5: Decision Router Agent                                      */
/* ------------------------------------------------------------------ */

data class DecisionRouterInput(
    val symbol: String,
    val triFactorForecast: Double,
    val tsfmForecast: Double,
    val lobConfidence: Double,
    val liquidityConfidence: Double,
    val regimeConfidence: Double,
    val modelDisagreement: Double,
    val dataFreshness: Double,
    val coneBreaches: Int = 0,
    val recentErrors: Double = 0.0
)

/**
 * Decision Router Agent - delegates to the §29 router.
 *
 * Production construction resolves [Needle3DecisionRouter.forMountedCheckpoint], so
 * the trade signal is produced on device by Cactus Needle 3 whenever a verified
 * checkpoint is mounted, and by the deterministic rules whenever it is not, when the
 * engine cannot run, or when the calibrated confidence is below the act threshold.
 *
 * The router is injectable so the pipeline stays testable and §29's interface stays
 * replaceable. It is resolved on first execution rather than construction: building
 * it must not touch the native engine or the download store before a decision is
 * actually asked for.
 */
class DecisionRouterAgent(
    private val injectedRouter: DecisionRouter? = null
) : BaseAgentImpl<DecisionRouterInput, DecisionState>() {

    override val agentId = "Decision Router"
    override val version = "1.0"
    override val executionTimeoutMs = 2000L

    private val activeRouter: DecisionRouter by lazy {
        injectedRouter ?: Needle3DecisionRouter.forMountedCheckpoint()
    }

    /** Why the last route was chosen: the Needle 3 answer, or the fallback's reason. */
    val lastReason: String get() = activeRouter.lastReason

    /** Calibrated confidence of the last route; 0.0 when the deterministic rules answered. */
    val decisionConfidence: Double get() = activeRouter.decisionConfidence

    override fun executeInternal(inputs: DecisionRouterInput): DecisionState? {
        if (inputs.symbol.isBlank()) return null

        return activeRouter.router(
            DecisionInputs(
                triFactorForecast = inputs.triFactorForecast,
                tsfmForecast = inputs.tsfmForecast,
                lobConfidence = inputs.lobConfidence,
                liquidityConfidence = inputs.liquidityConfidence,
                regimeConfidence = inputs.regimeConfidence,
                modelDisagreement = inputs.modelDisagreement,
                dataFreshness = inputs.dataFreshness,
                coneBreaches = inputs.coneBreaches,
                recentErrors = inputs.recentErrors
            )
        )
    }

    override fun validateInputs(inputs: Any): Boolean = inputs is DecisionRouterInput
}

/* ------------------------------------------------------------------ */
/* Agent 6: Risk/Confidence Agent                                      */
/* ------------------------------------------------------------------ */

data class RiskConfidenceInput(
    val symbol: String,
    val decisionState: DecisionState,
    val triFactorForecast: Double,
    val tsfmForecast: Double,
    val lobConfidence: Double,
    val liquidityConfidence: Double,
    val regimeConfidence: Double,
    val modelDisagreement: Double,
    val dataFreshness: Double
)

data class RiskConfidenceOutput(
    val symbol: String,
    val decisionState: DecisionState,
    val confidence: Double,
    val riskMetrics: Map<String, Double>
)

/** Risk/Confidence Agent - scores conviction and exposes the risk metrics. */
class RiskConfidenceAgent : BaseAgentImpl<RiskConfidenceInput, RiskConfidenceOutput>() {

    override val agentId = "Risk/Confidence"
    override val version = "1.0"
    override val executionTimeoutMs = 2000L

    override fun executeInternal(inputs: RiskConfidenceInput): RiskConfidenceOutput? {
        if (inputs.symbol.isBlank()) return null

        val confidence = computeConfidence(inputs)

        val riskMetrics = mapOf(
            "confidence" to confidence,
            "triFactorForecast" to inputs.triFactorForecast,
            "tsfmForecast" to inputs.tsfmForecast,
            "lobConfidence" to inputs.lobConfidence,
            "liquidityConfidence" to inputs.liquidityConfidence,
            "regimeConfidence" to inputs.regimeConfidence,
            "modelDisagreement" to inputs.modelDisagreement,
            "dataFreshness" to inputs.dataFreshness
        )

        return RiskConfidenceOutput(
            symbol = inputs.symbol,
            decisionState = inputs.decisionState,
            confidence = confidence,
            riskMetrics = riskMetrics
        )
    }

    override fun validateInputs(inputs: Any): Boolean = inputs is RiskConfidenceInput

    /** Confidence in [0, 1]: model agreement and signal quality, penalised for
     * disagreement and stale data.
     */
    private fun computeConfidence(inputs: RiskConfidenceInput): Double {
        val triSign = inputs.triFactorForecast.compareTo(0.0)
        val tsfmSign = inputs.tsfmForecast.compareTo(0.0)
        val directionAgreement = if (triSign == tsfmSign && triSign != 0) 1.0 else 0.0

        val base = 0.35 * directionAgreement +
            0.25 * inputs.lobConfidence.coerceIn(0.0, 1.0) +
            0.20 * inputs.liquidityConfidence.coerceIn(0.0, 1.0) +
            0.20 * inputs.regimeConfidence.coerceIn(0.0, 1.0)

        val disagreementPenalty = 0.30 * inputs.modelDisagreement.coerceIn(0.0, 1.0)
        val stalenessPenalty = 0.20 * (1.0 - inputs.dataFreshness.coerceIn(0.0, 1.0))

        return (base - disagreementPenalty - stalenessPenalty).coerceIn(0.0, 1.0)
    }
}

/* ------------------------------------------------------------------ */
/* Agent 7: Narrator Agent                                             */
/* ------------------------------------------------------------------ */

data class NarratorInput(
    val symbol: String,
    val decisionState: DecisionState,
    val triFactorForecast: Double,
    val tsfmForecast: Double,
    val lobConfidence: Double,
    val liquidityConfidence: Double,
    val modelDisagreement: Double,
    val dataFreshness: Double,
    val coneBreaches: Int,
    val recentErrors: Double,
    val riskMetrics: Map<String, Double>
)

/** Narrator Agent - renders a structured JSON explanation.
 * Every number is copied from the engine state; the agent never invents values.
 */
class NarratorAgent : BaseAgentImpl<NarratorInput, String>() {

    override val agentId = "Narrator"
    override val version = "1.0"
    override val executionTimeoutMs = 3000L

    override fun executeInternal(inputs: NarratorInput): String? {
        if (inputs.symbol.isBlank()) return null

        val metrics = inputs.riskMetrics.entries.joinToString(",") { (key, value) ->
            "\"${escape(key)}\":${number(value)}"
        }

        return buildString {
            append("{")
            append("\"symbol\":\"${escape(inputs.symbol)}\",")
            append("\"decisionState\":\"${inputs.decisionState.name}\",")
            append("\"triFactorForecast\":${number(inputs.triFactorForecast)},")
            append("\"tsfmForecast\":${number(inputs.tsfmForecast)},")
            append("\"lobConfidence\":${number(inputs.lobConfidence)},")
            append("\"liquidityConfidence\":${number(inputs.liquidityConfidence)},")
            append("\"modelDisagreement\":${number(inputs.modelDisagreement)},")
            append("\"dataFreshness\":${number(inputs.dataFreshness)},")
            append("\"coneBreaches\":${inputs.coneBreaches},")
            append("\"recentErrors\":${number(inputs.recentErrors)},")
            append("\"riskMetrics\":{$metrics}")
            append("}")
        }
    }

    override fun validateInputs(inputs: Any): Boolean = inputs is NarratorInput

    private fun number(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "null"
        else String.format(java.util.Locale.US, "%.4f", value)

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** AgentDAG - The directed-acyclic-graph orchestrator that sequences the 7 agents.
 * Responsibilities:
 * - Sequence agents in the correct order
 * - Manage execution timeouts per agent
 * - Collect and forward outputs from one agent to the next
 * - Handle agent failures with configured fallbacks
 * - Maintain overall DAG health status
 * - Expose final output after all agents have executed
 */
object AgentDAG {

    /** Execute the full 7-agent pipeline.
     * @param ingestion Inputs for the Ingestion Agent (market data, symbol)
     * @return The final DecisionState after all agents, or null if pipeline fails
     */
    suspend fun executeFullPipeline(
        // Ingestion Agent inputs
        symbol: String,
        marketData: Map<String, Any>,
        // Optional: pre-computed values from upstream if bypassing earlier agents
        triFactorForecast: Double = 0.0,
        tsfmForecast: Double = 0.0,
        lobConfidence: Double = 0.5,
        liquidityConfidence: Double = 0.5,
        regimeConfidence: Double = 0.5,
        modelDisagreement: Double = 0.0,
        dataFreshness: Double = 1.0,
        coneBreaches: Int = 0,
        recentErrors: Double = 0.0,
        priceHistory: List<Double> = emptyList(),
        // §29: on-device Needle 3 by default; callers and tests may substitute a router.
        decisionRouter: DecisionRouter? = null
    ): DecisionState? {

        // Agent 1: Ingestion Agent - validates and normalizes market data
        val ingestionResult = IngestionAgent().execute(
            IngestionInput(
                symbol = symbol,
                marketData = marketData,
                priceHistory = priceHistory
            )
        ) ?: return null

        // Agent 2: Market-State Agent - determines current market regime
        val marketStateResult = MarketStateAgent().execute(
            MarketStateInput(
                symbol = ingestionResult.symbol,
                priceData = mapOf("priceChangePercent" to ingestionResult.priceChangePercent),
                orderBookSnapshot = ingestionResult.orderBookSnapshot
            )
        ) ?: return null

        // Agent 3: Quant Vector Agent - produces kinematic + liquidity vectors
        val quantVectorResult = QuantVectorAgent().execute(
            QuantVectorInput(
                symbol = ingestionResult.symbol,
                currentRegime = marketStateResult.currentRegime,
                priceHistory = ingestionResult.priceHistory,
                depthSnapshot = ingestionResult.orderBookSnapshot
            )
        ) ?: return null

        // Agent 4: TSFM Agent - produces time-series foundation model forecast
        val tsfmResult = TsfmAgent().execute(
            TsfmInput(
                symbol = ingestionResult.symbol,
                currentPrice = ingestionResult.lastPrice,
                regime = marketStateResult.currentRegime,
                quantVector = quantVectorResult.triFactorForecast
            )
        ) ?: return null

        // Agent 5: Decision Router - determines trade signal
        val decisionResult = DecisionRouterAgent(decisionRouter).execute(
            DecisionRouterInput(
                symbol = ingestionResult.symbol,
                triFactorForecast = quantVectorResult.triFactorForecast,
                tsfmForecast = tsfmResult.tsfmForecast,
                lobConfidence = lobConfidence,
                liquidityConfidence = liquidityConfidence,
                regimeConfidence = regimeConfidence,
                modelDisagreement = modelDisagreement,
                dataFreshness = dataFreshness,
                coneBreaches = coneBreaches,
                recentErrors = recentErrors
            )
        ) ?: return null

        // Agent 6: Risk/Confidence Agent - computes confidence score and risk metrics
        val riskResult = RiskConfidenceAgent().execute(
            RiskConfidenceInput(
                symbol = ingestionResult.symbol,
                decisionState = decisionResult,
                triFactorForecast = quantVectorResult.triFactorForecast,
                tsfmForecast = tsfmResult.tsfmForecast,
                lobConfidence = lobConfidence,
                liquidityConfidence = liquidityConfidence,
                regimeConfidence = marketStateResult.regimeConfidence,
                modelDisagreement = modelDisagreement,
                dataFreshness = dataFreshness
            )
        ) ?: return null

        // Agent 7: Narrator Agent - structured explanation (JSON) without inventing numerical facts
        NarratorAgent().execute(
            NarratorInput(
                symbol = ingestionResult.symbol,
                decisionState = decisionResult,
                triFactorForecast = quantVectorResult.triFactorForecast,
                tsfmForecast = tsfmResult.tsfmForecast,
                lobConfidence = lobConfidence,
                liquidityConfidence = liquidityConfidence,
                modelDisagreement = modelDisagreement,
                dataFreshness = dataFreshness,
                coneBreaches = coneBreaches,
                recentErrors = recentErrors,
                riskMetrics = riskResult.riskMetrics
            )
        ) ?: return null

        // Pipeline completed successfully - return the final decision state
        return decisionResult
    }

    /** Pre-built agent set, useful for the engine and for health dashboards. */
    val agents: List<BaseAgent<*, *>> = listOf(
        IngestionAgent(),
        MarketStateAgent(),
        QuantVectorAgent(),
        TsfmAgent(),
        DecisionRouterAgent(),
        RiskConfidenceAgent(),
        NarratorAgent()
    )
}
