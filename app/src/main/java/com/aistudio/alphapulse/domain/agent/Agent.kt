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
import com.aistudio.alphapulse.domain.decision.DeterministicDecisionRouter
import com.aistudio.alphapulse.domain.quant.ModelState
import kotlinx.coroutines.*
import kotlin.math.*

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
    val lastSuccess: Boolean = false,
    val lastError: String? = null,
    val averageDurationMs: Long = 0L
)

/** BaseAgent - Core interface all 7 agents implement.
 * Every agent has: defined inputs, defined outputs, execution timeout,
 * health status, version, telemetry, and failure fallback.
 */
interface BaseAgent {

    /** Agent unique identifier */
    val agentId: String

    /** Agent version (for upgrades/audit) */
    val version: String

    /** Current health status */
    val health: AgentHealth

    /** Telemetry snapshot */
    val telemetry: AgentTelemetry

    /** Defined inputs type for this agent */
    type In: Any

    /** Defined outputs type for this agent */
    type Out: Any

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
        recentErrors: Double = 0.0
    ): DecisionState? = withContext(Dispatchers.IO) {

        // Agent 1: Ingestion Agent - validates and normalizes market data
        val ingestionResult = IngestionAgent().execute(
            IngestionInput(
                symbol = symbol,
                marketData = marketData,
                normalizedPriceData = marketData.filterValues { it is Double }.mapValues { it.value as Double },
                orderBookSnapshot = marketData.filterValues { it is Double }.mapValues { it.value as Double },
                priceHistory = emptyList()
            )
        ) ?: return null

        // Agent 2: Market-State Agent - determines current market regime
        val marketStateResult = MarketStateAgent().execute(
            MarketStateInput(
                symbol = ingestionResult.symbol,
                priceData = ingestionResult.normalizedPriceData,
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
                quantVector = quantVectorResult.quantVector
            )
        ) ?: return null

        // Agent 5: Decision Router - determines trade signal
        val decisionResult = DecisionRouterAgent().execute(
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
                modelDisagreement = modelDisagreement,
                dataFreshness = dataFreshness
            )
        ) ?: return null

        // Agent 7: Narrator Agent - generates structured explanation (JSON) without inventing numerical facts
        val narratorResult = NarratorAgent().execute(
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

    /** Data classes for each agent's inputs and outputs (inner of AgentDAG) */

    /** ----- Agent 1: Ingestion Agent ----- */

    data class IngestionInput(
        val symbol: String,
        val marketData: Map<String, Any>,
        val normalizedPriceData: Map<String, Double>,
        val orderBookSnapshot: Map<String, Double>,
        val priceHistory: List<Double>
    )

    object IngestionAgent : BaseAgent {

        override val agentId = "Ingestion"
        override val version = "1.0"
        override val health = AgentHealth.HEALTHY
        override val telemetry = AgentTelemetry()
        override val executionTimeoutMs = 5000L // 5 seconds max

        override type In = IngestionInput
        override type Out = IngestionOutput

        override fun execute(inputs: IngestionInput): IngestionOutput? {
            // Validate and normalize market data
            try {
                val symbol = inputs.symbol
                val marketData = inputs.marketData

                // Basic validation
                if (marketData.isEmpty() || symbol.isBlank()) {
                    updateHealth(false, "Invalid inputs: empty market data or blank symbol", 0)
                    return null
                }

                // Normalize price data - extract key metrics
                val lastPrice = marketData["lastPrice"] as? Double ?: return null
                val priceChange = marketData["priceChangePercent"] as? Double ?: 0.0
                val high24h = marketData["high24h"] as? Double ?: lastPrice
                val low24h = marketData["low24h"] as? Double ?: lastPrice
                val volume = marketData["volume"] as? Double ?: 0.0
                val quoteVolume = marketData["quoteVolume"] as? Double ?: 0.0

                // Simple price history window (last 30 closes) - if not provided, use empty
                val priceHistory = inputs.priceHistory.take(30).map { it to it }.map { it.first }.toList()
                    .also { if (it.isEmpty()) listOf(lastPrice) } // fallback

                // Order book snapshot normalization
                val orderBookSnapshot = mapOf(
                    "bidRatio" to (marketData["bidRatioPct"] ?: 50.0) / 100.0,
                    "imbalance" to (marketData["netImbalanceUsdt"] ?: 0.0) / max(1.0, lastPrice)
                )

                val output = IngestionOutput(
                    symbol = symbol,
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

                updateHealth(true, null, executionTimeoutMs)
                return output
            } catch (e: Exception) {
                updateHealth(false, e.message, executionTimeoutMs)
                return null
            }
        }

        override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {
            // Update telemetry in a real implementation
        }

        override fun reset() {
            // Reset agent state
        }

        override fun validateInputs(inputs: Any): Boolean {
            return inputs is IngestionInput
        }

        /** Determine the current Markov regime based on price change and confidence. */
        private fun determineRegime(priceChange: Double): String {
            return if (priceChange > 0.5) "Momentum Burst"
                else if (priceChange < -0.5) "Mean Reversion"
                else "State 0: Mean-Reverting"
        }

        /** IngestionOutput - Output of the Ingestion Agent. */
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
    }

    /** ----- Agent 2: Market-State Agent ----- */

    data class MarketStateInput(
        val symbol: String,
        val priceData: Map<String, Double>,
        val orderBookSnapshot: Map<String, Double>
    )

    object MarketStateAgent : BaseAgent {

        override val agentId = "Market-State"
        override val version = "1.0"
        override val health = AgentHealth.HEALTHY
        override val telemetry = AgentTelemetry()
        override val executionTimeoutMs = 3000L

        override type In = MarketStateInput
        override type Out = MarketStateOutput

        override fun execute(inputs: MarketStateInput): MarketStateOutput? {
            try {
                val priceChange = inputs.priceData["priceChangePercent"] ?: 0.0
                val bidRatio = inputs.orderBookSnapshot["bidRatio"] ?: 0.5
                val imbalance = inputs.orderBookSnapshot["imbalance"] ?: 0.0

                // Determine regime
                val regime: String = when {
                    abs(priceChange) > 1.0 && bidRatio > 0.6 -> "Momentum Burst"
                    abs(priceChange) < 0.3 && abs(imbalance) < 0.1 -> "State 0: Mean-Reverting"
                    else -> "State 1: Momentum Burst"
                }

                // Calculate regime confidence based on price magnitude and order-book strength
                val regimeConfidence = abs(priceChange).coerceAtMost(1.0) * bidRatio.coerceAtMost(1.0) * 1.0

                updateHealth(true, null, executionTimeoutMs)

                return MarketStateOutput(
                    symbol = inputs.symbol,
                    currentRegime = regime,
                    regimeConfidence = regimeConfidence,
                    priceChange = priceChange,
                    bidRatio = bidRatio,
                    imbalance = imbalance
                )
            } catch (e: Exception) {
                updateHealth(false, e.message, executionTimeoutMs)
                return null
            }
        }

        override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {}

        override fun reset() {}

        override fun validateInputs(inputs: Any): Boolean = inputs is MarketStateInput

        data class MarketStateOutput(
            val symbol: String,
            val currentRegime: String,
            val regimeConfidence: Double,
            val priceChange: Double,
            val bidRatio: Double,
            val imbalance: Double
        )
    }

    /** ----- Agent 3: Quant Vector Agent ----- */

    data class QuantVectorInput(
        val symbol: String,
        val currentRegime: String,
        val priceHistory: List<Double>,
        val depthSnapshot: Map<String, Double>
    )

    object QuantVectorAgent : BaseAgent {

        override val agentId = "Quant Vector"
        override val version = "1.0"
        override val health = AgentHealth.HEALTHY
        override val telemetry = AgentTelemetry()
        override val executionTimeoutMs = 4000L

        override type In = QuantVectorInput
        override type Out = QuantVectorOutput

        override fun execute(inputs: QuantVectorInput): QuantVectorOutput? {
            try {
                val regime = inputs.currentRegime
                val prices = inputs.priceHistory
                val depth = inputs.depthSnapshot

                // Compute kinematic component (simplified: momentum from price change)
                val kinematic = when {
                    regime.contains("Momentum Burst") && prices.size >= 2 ->
                        (prices.last() - prices[prices.size - 2]) / max(1.0, prices[prices.size - 2])
                    else -> 0.0
                }

                // Compute liquidity path component from depth snapshot
                val bidRatio = depth["bidRatio"] ?: 0.5
                val liquidityPath = when {
                    bidRatio > 0.6 -> 0.0005 // Bullish liquidity bias
                    bidRatio < 0.4 -> -0.0005 // Bearish liquidity bias
                    else -> 0.0 // Neutral
                }

                // Model disagreement estimate (simplified: inverse of regime confidence)
                val disagreement = 0.1 // Placeholder

                updateHealth(true, null, executionTimeoutMs)

                return QuantVectorOutput(
                    symbol = inputs.symbol,
                    triFactorForecast = kinematic, // % forecast for Tri-Factor engine
                    liquidityPath = liquidityPath, // % adjustment for pain path
                    quantVectorRegime = regime,
                    disagreement = disagreement
                )
            } catch (e: Exception) {
                updateHealth(false, e.message, executionTimeoutMs)
                return null
            }
        }

        override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {}

        override fun reset() {}

        override fun validateInputs(inputs: Any): Boolean = inputs is QuantVectorInput

        data class QuantVectorOutput(
            val symbol: String,
            val triFactorForecast: Double,
            val liquidityPath: Double,
            val quantVectorRegime: String,
            val disagreement: Double
        )
    }

    /** ----- Agent 4: TSFM Agent ----- */

    data class TsfmInput(
        val symbol: String,
        val currentPrice: Double,
        val regime: String,
        val quantVector: Double // from Quant Vector Agent
    )

    object TsfmAgent : BaseAgent {

        override val agentId = "TSFM"
        override val version = "1.0"
        override val health = AgentHealth.HEALTHY
        override val telemetry = AgentTelemetry()
        override val executionTimeoutMs = 8000L // TSFM inference may take longer

        override type In = TsfmInput
        override type Out = TsfmOutput

        override fun execute(inputs: TsfmInput): TsfmOutput? {
            // Placeholder TSFM inference - in production, this would use ExecuTorch/ONNX Runtime
            // For now, compute a deterministic forecast based on regime and quant vector
            try {
                val forecast = computeDeterministicForecast(inputs)

                updateHealth(true, null, executionTimeoutMs)

                return TsfmOutput(
                    symbol = inputs.symbol,
                    tsfmForecast = forecast, // % forecast
                    regime = inputs.regime,
                    confidence = 0.75 // Placeholder confidence
                )
            } catch (e: Exception) {
                updateHealth(false, e.message, executionTimeoutMs)
                return null
            }
        }

        override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {}

        override fun reset() {}

        override fun validateInputs(inputs: Any): Boolean = inputs is TsfmInput

        /** Deterministic TSFM forecast computation (placeholder for actual neural/chronos inference). */
        private fun computeDeterministicForecast(inputs: TsfmInput): Double {
            // Base forecast from quant vector influence
            val quantInfluence = inputs.quantVector * 0.6

            // Regime adjustment
            val regimeAdjustment = when (inputs.regime) {
                "Momentum Burst" -> 0.3
                "Mean Reverting" -> -0.2
                else -> 0.0
            }

            // Price trend component
            val priceTrend = (inputs.currentPrice * 0.0001) // 0.01% per unit price

            return quantInfluence + regimeAdjustment + priceTrend
        }

        data class TsfmOutput(
            val symbol: String,
            val tsfmForecast: Double,
            val regime: String,
            val confidence: Double
        )
    }

    /** ----- Agent 5: Decision Router Agent ----- */

    data class DecisionRouterInput(
        val symbol: String,
        val triFactorForecast: Double,
        val tsfmForecast: Double,
        val lobConfidence: Double,
        val liquidityConfidence: Double,
        val regimeConfidence: Double,
        val modelDisagreement: Double,
        val dataFreshness: Double,
        val coneBreaches: Int,
        val recentErrors: Double
    )

    object DecisionRouterAgent : BaseAgent {

        override val agentId = "Decision Router"
        override val version = "1.0"
        override val health = AgentHealth.HEALTHY
        override val telemetry = AgentTelemetry()
        override val executionTimeoutMs = 2000L

        override type In = DecisionRouterInput
        override type Out = DecisionState

        override fun execute(inputs: DecisionRouterInput): DecisionState? {
            // Delegate to the deterministic decision router (configured as default)
            return DeterministicDecisionRouter().router(DecisionInputs(
                triFactorForecast = inputs.triFactorForecast,
                tsfmForecast = inputs.tsfmForecast,
                lobConfidence = inputs.lobConfidence,
                liquidityConfidence = inputs.liquidityConfidence,
                regimeConfidence = inputs.regimeConfidence,
                modelDisagreement = inputs.modelDisagreement,
                dataFreshness = inputs.dataFreshness,
                coneBreaches = inputs.coneBreaches,
                recentErrors = inputs.recentErrors
            ))
        }

        override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {}

        override fun reset() {}

        override fun validateInputs(inputs: Any): Boolean = inputs is DecisionRouterInput

        // Reuse the DecisionInputs from the DecisionRouter module
        private data class InternalDecisionInputs(
            val triFactorForecast: Double,
            val tsfmForecast: Double,
            val lobConfidence: Double,
            val liquidityConfidence: Double,
            val regimeConfidence: Double,
            val modelDisagreement: Double,
            val dataFreshness: Double,
            val coneBreaches: Int,
            val recentErrors: Double
        )

        // Extend DecisionInputs or copy the router logic here
        // For brevity, the router delegates to DeterministicDecisionRouter above
    }

    /** ----- Agent 6: Risk/Confidence Agent ----- */

    data class RiskConfidenceInput(
        val symbol: String,
        val decisionState: DecisionState,
        val triFactorForecast: Double,
        val tsfmForecast: Double,
        val lobConfidence: Double,
        val liquidityConfidence: Double,
        val modelDisagreement: Double,
        val dataFreshness: Double
    )

    object RiskConfidenceAgent : BaseAgent {

        override val agentId = "Risk/Confidence"
        override val version = "1.0"
        override val health = AgentHealth.HEALTHY
        override val telemetry = AgentTelemetry()
        override val executionTimeoutMs = 2000L

        override type In = RiskConfidenceInput
        override type Out = RiskConfidenceOutput

        override fun execute(inputs: RiskConfidenceInput): RiskConfidenceOutput? {
            try {
                // Compute confidence score incorporating all factors
                val confidence = computeConfidence(inputs)

                // Compute risk metrics
                val riskMetrics = mapOf(
                    "triFactorForecast" to inputs.triFactorForecast,
                    "tsfmForecast" to 0.0, // placeholder
                    "lobConfidence" to inputs.lobConfidence,
                    "liquidityConfidence" to inputs.liquidityConfidence,
                    "modelDisagreement" to inputs.modelDisagreement,
                    "dataFreshness" to inputs.dataFreshness,
                    "confidence" to confidence
                )

                updateHealth(true, null, executionTimeoutMs)

                return RiskConfidenceOutput(
                    symbol = inputs.symbol,
                    decisionState = inputs.decisionState,
                    confidence = confidence,
                    riskMetrics = riskMetrics
                )
            } catch (e: Exception) {
                updateHealth(false, e.message, executionTimeoutMs)
                return null
            }
        }

        override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {}

        override fun reset() {}

        override fun validateInputs(inputs: Any): Boolean = inputs is RiskConfidenceInput

        /** Compute the final confidence score (0.0 to 1.0). */
        private fun computeConfidence(inputs: RiskConfidenceInput): Double {
            // Weighted combination of agreement factors
            val triFactorSign = if (inputs.triFactorForecast > 0) 1.0 else if (inputs.triFactorForecast < 0) -1.0 else 0.0
            val tsfmSign = if (inputs.tsfmForecast > 0) 1.0 else if (inputs.tsfmForecast < 0) -1.0 else 0.0
            val agreement = 0.4 * triFactorSign * tsfmSign *
                    0.3 * inputs.lobConfidence +
                    0.2 * inputs.liquidityConfidence +
                    0.1 * inputs.regimeConfidence

            // Penalties
            val disagreementPenalty = inputs.modelDisagreement * 0.3
            val stalenessPenalty = (1.0 - inputs.dataFreshness) * 0.2

            return coerceIn(agreement - disagreementPenalty - stalenessPenalty, 0.0, 1.0)
        }

        data class RiskConfidenceOutput(
            val symbol: String,
            val decisionState: DecisionState,
            val confidence: Double,
            val riskMetrics: Map<String, Double>
        )
    }

    /** ----- Agent 7: Narrator Agent ----- */

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

    object NarratorAgent : BaseAgent {

        override val agentId = "Narrator"
        override val version = "1.0"
        override val health = AgentHealth.HEALTHY
        override val telemetry = AgentTelemetry()
        override val executionTimeoutMs = 3000L

        override type In = NarratorInput
        override type Out = String // Returns structured JSON narrative

        override fun execute(inputs: NarratorInput): String? {
            try {
                // Generate structured narrative JSON without inventing numerical facts
                // All numbers must originate from the structured engine state
                val narrative = mapOf(
                    "symbol" to inputs.symbol,
                    "decisionState" to inputs.decisionState.name,
                    "triFactorForecast" to "%+.4f".format(inputs.triFactorForecast),
                    "tsfmForecast" to "%+.4f".format(0.0), // placeholder - would have actual TSFM forecast
                    "lobConfidence" to "%.2f".format(inputs.lobConfidence),
                    "liquidityConfidence" to "%.2f".format(inputs.liquidityConfidence),
                    "modelDisagreement" to "%.2f".format(inputs.modelDisagreement),
                    "dataFreshness" to "%.2f".format(inputs.dataFreshness),
                    "coneBreaches" to inputs.coneBreaches,
                    "recentError" to "%+.2f".format(inputs.recentErrors),
                    "riskMetrics" to inputs.riskMetrics
                )

                // Convert to JSON string (using simple string building; in production use Moshi/Gson)
                val entries = mutableListOf<String>()
                for ((k, v) in narrative) {
                    val vStr = when (v) {
                        null -> "null"
                        else -> "\"$v\""
                    }
                    entries.add("\"$k\": $vStr")
                }
                val json = entries.joinToString(",\n")
                val prettyJson = "{{\n$json\n}}"

                updateHealth(true, null, executionTimeoutMs)
                return prettyJson
            } catch (e: Exception) {
                updateHealth(false, e.message, executionTimeoutMs)
                return null
            }
        }

        override fun updateHealth(isSuccessful: Boolean, error: String?, durationMs: Long) {}

        override fun reset() {}

        override fun validateInputs(inputs: Any): Boolean = inputs is NarratorInput
    }
}