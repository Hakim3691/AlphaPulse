package com.aistudio.alphapulse.domain.decision

import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Needle3DecisionRouter - §29 decision routing, executed on device by Needle 3 (§30).
 *
 * Why Microsoft-Decision-1 is gone
 * --------------------------------
 * Decision-1 was an HTTPS call to a closed-weight model that needed an endpoint, an
 * API key, a network and a per-token bill. Needle 3 already ships in the APK with
 * byte-level grammar decoding and a calibrated confidence head, so the same job -
 * pick one of the six [DecisionState] outcomes for a snapshot - is done locally:
 *
 *  1. **One tool per route.** Needle 3 is trained to pick a tool name rather than
 *     invent free-text arguments, so each of the six states is its own tool. There
 *     is deliberately no catch-all `route_decision(option = ...)` tool: an abstract
 *     enum would force the model to infer a category the grammar cannot constrain.
 *  2. **No required arguments.** Every tool declares an empty parameter object, so
 *     the engine can never withhold a call because an argument had no literal span
 *     in the snapshot. The snapshot is the grounding, and it is printed verbatim.
 *  3. **Calibrated confidence gates the answer.** At or above
 *     [confidenceThreshold] the route is executed. Below it, or when the engine
 *     suppressed the call, the deterministic rules answer instead (§44).
 *
 * §29 still holds: this is only one implementation of [DecisionRouter]. No caller
 * can tell an on-device route from the deterministic one, and every failure below
 * ends in [DeterministicDecisionRouter] rather than an exception.
 *
 * Deterministic regex triggers are intentionally not used. The routes here are
 * numeric, not textual: the deterministic router already encodes the exact
 * thresholds as code, and it is the fallback rather than a downstream box, so a
 * regex table in the schema would duplicate it and fire on every snapshot.
 *
 * Spec Ref: §29 Quantitative Decision Router, §30 Needle / Agent Orchestration,
 * §33 Forecast Confidence Score, §44 Failure Handling, §45 Performance Requirements
 *
 * @param engine the on-device run path; shared instance in production
 * @param weightsPathProvider the mounted `needle3.cact` path, or null when nothing
 *   has been downloaded and verified
 * @param fallback the rules that answer whenever Needle 3 cannot
 * @param confidenceThreshold calibrated floor for acting on a route
 */
class Needle3DecisionRouter(
    private val engine: Needle3Engine = Needle3Engine.shared,
    private val weightsPathProvider: () -> String? = { null },
    private val fallback: DecisionRouter = DeterministicDecisionRouter(),
    private val confidenceThreshold: Double = DEFAULT_CONFIDENCE_THRESHOLD,
    private val maxNewTokens: Int = Needle3Engine.DEFAULT_ROUTING_MAX_NEW_TOKENS
) : DecisionRouter {

    override var lastReason: String = "Needle 3 decision router: not called yet"
        private set

    override var decisionConfidence: Double = 0.0
        private set

    /** Attribution for the audit ledger: which router actually answered. */
    override var decisionSource: DecisionSource = DecisionSource.DETERMINISTIC
        private set

    /**
     * Route one snapshot, always returning one of the six states.
     *
     * The method cannot throw: a missing checkpoint, an ABI without the engine, a
     * malformed answer, an ungrounded argument, a suppressed call or a low
     * confidence all resolve to the deterministic verdict, with the reason kept in
     * [lastReason] for the audit trail.
     */
    override fun router(inputs: DecisionInputs): DecisionState {
        val weightsPath = weightsPathProvider()
        if (weightsPath.isNullOrBlank()) {
            return fallbackTo(
                inputs,
                "Needle 3 has no mounted checkpoint, so the decision stays deterministic."
            )
        }

        val answer = when (val result = engine.routeDecision(
            prompt = promptFor(inputs),
            toolsJson = ROUTING_TOOLS_JSON,
            weightsPath = weightsPath,
            maxNewTokens = maxNewTokens
        )) {
            is Needle3Result.Generated -> result
            // The typed status carries the engine's own words, so the reason says
            // what actually failed instead of only that the engine is mounted.
            is Needle3Result.Unavailable -> return fallbackTo(
                inputs,
                "Needle 3 could not run (${describe(result.status)}), " +
                    "so the decision stays deterministic."
            )
        }

        val turn = NeedleRouteTurn.parse(answer.rawJson)
            ?: return fallbackTo(
                inputs,
                "Needle 3 answered with text that is not the promised routing JSON, " +
                    "so the route was refused."
            )

        // An argument the engine could not ground is a hallucination signal; a
        // route built on it must never reach execution.
        if (turn.ungroundedFields.isNotEmpty()) {
            return fallbackTo(
                inputs,
                "Needle 3 could not ground ${turn.ungroundedFields.joinToString()}, " +
                    "so the route was refused."
            )
        }

        // The engine moves calls below its own 0.10 floor into suppressed_calls.
        if (turn.functionCalls.isEmpty()) {
            val withheld = turn.suppressedCalls.joinToString().ifBlank { "no tool" }
            return fallbackTo(
                inputs,
                "Needle 3 withheld its route ($withheld), so the decision stays deterministic."
            )
        }

        if (turn.functionCalls.size > 1) {
            return fallbackTo(
                inputs,
                "Needle 3 returned ${turn.functionCalls.size} routes " +
                    "(${turn.functionCalls.joinToString()}); a decision must be exactly one, " +
                    "so the deterministic rules answer."
            )
        }

        val toolName = turn.functionCalls.single()
        val state = ROUTE_TO_STATE[toolName]
            ?: return fallbackTo(
                inputs,
                "Needle 3 chose '$toolName', which is not one of the six decision tools, " +
                    "so the route was refused."
            )

        val confidence = turn.confidence
        if (confidence != null && confidence < confidenceThreshold) {
            return fallbackTo(
                inputs,
                "Needle 3 chose '$toolName' at confidence " +
                    "${"%.2f".format(confidence)}, below the " +
                    "${"%.2f".format(confidenceThreshold)} act threshold, so the " +
                    "deterministic rules answer."
            )
        }

        // A LoRA-tuned .cact has no post-hoc head, so the engine reports no score;
        // the grammar-constrained call is still honoured, and saying so beats
        // pretending a calibrated number exists.
        decisionConfidence = (confidence ?: UNSCORED_CONFIDENCE).coerceIn(0.0, 1.0)
        decisionSource = DecisionSource.NEEDLE3
        lastReason = buildString {
            append("Needle 3: ")
            append(toolName)
            append(
                if (confidence == null) {
                    " (the engine reported no calibrated confidence)"
                } else {
                    " (confidence ${"%.0f".format(confidence * 100)}%)"
                }
            )
            if (turn.reasoning.isNotBlank()) {
                append(" - ")
                append(turn.reasoning)
            }
        }
        return state
    }

    /** The failure in the engine's own words, for the fallback reason. */
    private fun describe(status: Needle3EngineStatus): String = when (status) {
        is Needle3EngineStatus.Ready -> status.engineVersion
        is Needle3EngineStatus.WeightsNotMounted -> status.reason
        is Needle3EngineStatus.NotBundled -> status.reason
        is Needle3EngineStatus.LoadFailed -> status.reason
        is Needle3EngineStatus.InferenceFailed -> status.reason
    }

    /** Answer with the deterministic rules and record both halves of the reason. */
    private fun fallbackTo(inputs: DecisionInputs, reason: String): DecisionState {
        val state = fallback.router(inputs)
        decisionConfidence = fallback.decisionConfidence
        decisionSource = fallback.decisionSource
        lastReason = "$reason Fallback: ${fallback.lastReason}"
        return state
    }

    /**
     * The snapshot as text.
     *
     * It is printed verbatim so every tool argument the engine might echo has a
     * literal span to be grounded in, and so the model sees the same numbers the
     * deterministic rules score.
     */
    private fun promptFor(inputs: DecisionInputs): String = buildString {
        appendLine("Decision snapshot.")
        appendLine("tri_factor_forecast: ${"%.4f".format(inputs.triFactorForecast)}")
        appendLine("tsfm_forecast: ${"%.4f".format(inputs.tsfmForecast)}")
        appendLine("lob_confidence: ${"%.2f".format(inputs.lobConfidence)}")
        appendLine("liquidity_confidence: ${"%.2f".format(inputs.liquidityConfidence)}")
        appendLine("regime_confidence: ${"%.2f".format(inputs.regimeConfidence)}")
        appendLine("model_disagreement: ${"%.2f".format(inputs.modelDisagreement)}")
        appendLine("data_freshness: ${"%.2f".format(inputs.dataFreshness)}")
        appendLine("cone_breaches: ${inputs.coneBreaches}")
        appendLine("recent_errors: ${"%.4f".format(inputs.recentErrors)}")
        append("Choose the one decision tool this snapshot supports.")
    }

    companion object {

        /** Calibrated floor for acting on a route; below it the rules answer. */
        const val DEFAULT_CONFIDENCE_THRESHOLD: Double = 0.70

        /**
         * The router the production pipeline uses.
         *
         * The checkpoint is looked up at call time from the same store the model dock
         * writes, so mounting or evicting Needle 3 takes effect on the next decision
         * with no re-wiring. Before the store is initialised - and on any device
         * without the 34 MB checkpoint - the path is null and the deterministic
         * rules answer.
         */
        fun forMountedCheckpoint(): DecisionRouter = Needle3DecisionRouter(
            weightsPathProvider = { mountedCheckpointPath() }
        )

        /** Absolute path of a verified, non-empty `needle3.cact`, or null when none is mounted. */
        internal fun mountedCheckpointPath(): String? = runCatching {
            ModelDownloadStore.installedFile(ModelArtifacts.NEEDLE3.modelId)
                ?.takeIf { it.isFile && it.length() > 0 }
                ?.absolutePath
        }.getOrNull()

        /**
         * Reported as the confidence when the engine supplies none (LoRA weights).
         *
         * Zero rather than a flattering guess: an uncalibrated route must not look
         * calibrated to whatever reads [decisionConfidence].
         */
        const val UNSCORED_CONFIDENCE: Double = 0.0

        /** Tool name for each decision state - one tool per route, no catch-all. */
        val ROUTE_TO_STATE: Map<String, DecisionState> = linkedMapOf(
            "decide_strong_long" to DecisionState.STRONG_LONG,
            "decide_long" to DecisionState.LONG,
            "decide_neutral" to DecisionState.NEUTRAL,
            "decide_short" to DecisionState.SHORT,
            "decide_strong_short" to DecisionState.STRONG_SHORT,
            "decide_no_trade" to DecisionState.NO_TRADE
        )

        /**
         * The tool catalogue handed to the engine as the decode grammar.
         *
         * Descriptions restate the deterministic thresholds on purpose, so the
         * model's own classification lines up with the rules that back it up when
         * confidence is too low.
         */
        const val ROUTING_TOOLS_JSON: String = """
[
  {
    "name": "decide_strong_long",
    "description": "Choose when both forecasts are positive and lob_confidence and liquidity_confidence are at least 0.75: a high-conviction long.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "decide_long",
    "description": "Choose when both forecasts are positive and lob_confidence and liquidity_confidence are at least 0.60 but not both 0.75 or more.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "decide_neutral",
    "description": "Choose when the signals conflict or model_disagreement is greater than 0.40, so there is no tradable edge in either direction.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "decide_short",
    "description": "Choose when both forecasts are negative and lob_confidence and liquidity_confidence are at least 0.60 but not both 0.75 or more.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "decide_strong_short",
    "description": "Choose when both forecasts are negative and lob_confidence and liquidity_confidence are at least 0.75: a high-conviction short.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "decide_no_trade",
    "description": "Choose when data_freshness is below 0.50 or cone_breaches is 3 or more: the snapshot is not trustworthy enough to trade.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  }
]
"""
    }
}

/**
 * The routing half of a Needle 3 answer.
 *
 * Kept separate from [Needle3Result] because a routing turn is judged on the tool
 * it chose, not on its prose: the engine may legitimately return an empty
 * `reasoning` for a call, and that must not be mistaken for a malformed answer.
 */
internal data class NeedleRouteTurn(
    val functionCalls: List<String>,
    val suppressedCalls: List<String>,
    val confidence: Double?,
    val reasoning: String,
    val ungroundedFields: List<String>
) {

    companion object {

        /** Same generic reader the narrator uses: the answer is a plain object. */
        private val MAP_ADAPTER = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )

        /**
         * Parse the engine's JSON, or null when it is not a routing payload.
         *
         * Anything that is not the promised object - prose, a bare string, a
         * truncated body - is refused instead of being interpreted.
         */
        fun parse(rawJson: String): NeedleRouteTurn? {
            val map = runCatching { MAP_ADAPTER.fromJson(rawJson) }.getOrNull() ?: return null
            if (!map.containsKey("function_calls") && !map.containsKey("suppressed_calls")) return null

            val validation = map["validation"] as? Map<*, *>
            return NeedleRouteTurn(
                functionCalls = namesOf(map["function_calls"]),
                suppressedCalls = namesOf(map["suppressed_calls"]),
                confidence = (map["confidence"] as? Number)?.toDouble(),
                reasoning = (map["reasoning"] as? String).orEmpty(),
                ungroundedFields = (validation?.get("ungrounded") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?: emptyList()
            )
        }

        /** Tool names out of either call array, tolerant of string-only entries. */
        private fun namesOf(value: Any?): List<String> = (value as? List<*>)
            ?.mapNotNull { entry ->
                when (entry) {
                    is Map<*, *> -> entry["name"] as? String
                    is String -> entry
                    else -> null
                }
            }
            ?: emptyList()
    }
}
