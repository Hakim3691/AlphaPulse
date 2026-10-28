package com.aistudio.alphapulse.domain.decision

import com.aistudio.alphapulse.domain.ensemble.CalibrationHorizons
import com.aistudio.alphapulse.domain.ensemble.CalibrationPlanSource
import com.aistudio.alphapulse.domain.ensemble.CoinCalibrationPlan
import com.aistudio.alphapulse.domain.ensemble.EnsembleCalibration
import com.aistudio.alphapulse.domain.ensemble.EnsembleWeighting

/**
 * Needle 3 dispatch for the 30-day per-coin calibration (spec §20, §30).
 *
 * The engine does not fetch candles or run ONNX - it cannot - so its job is the
 * one it is actually trained for: pick a tool. The tool encodes *which horizon
 * set to calibrate*, which is the only decision the pipeline needs from outside.
 * The mounted models and the symbol are grounded facts printed into the prompt,
 * not model output, so they can never be invented.
 *
 * One tool per horizon set, no catch-all: the same grammar-first design the
 * decision router uses. If the engine is not mounted, withholds the call, reports
 * it below the act threshold or answers with anything unexpected, the
 * deterministic plan (all supported horizons, all mounted models, 30 days) runs
 * and the reason says why. Calibration is never skipped because the model is
 * missing; it is only ever re-scoped.
 *
 * Spec Ref: §20 Adaptive Ensemble Learning, §30 Needle / Agent Orchestration,
 * §44 Failure Handling
 */
class CoinCalibrationOrchestrator(
    private val engine: Needle3Engine = Needle3Engine.shared,
    private val weightsPathProvider: () -> String? = { Needle3DecisionRouter.mountedCheckpointPath() },
    private val confidenceThreshold: Double = Needle3DecisionRouter.DEFAULT_CONFIDENCE_THRESHOLD,
    private val maxNewTokens: Int = Needle3Engine.DEFAULT_ROUTING_MAX_NEW_TOKENS
) {

    /**
     * Choose the calibration plan for one asset.
     *
     * @param symbol already-normalised market symbol
     * @param mountedModelIds models the dock has verified and mounted
     * @param windowDays history window to request
     */
    fun plan(
        symbol: String,
        mountedModelIds: Collection<String>,
        windowDays: Int = EnsembleCalibration.WINDOW_DAYS
    ): CoinCalibrationPlan {
        val mounted = EnsembleWeighting.mountedExtraIds(mountedModelIds)
        val deterministic = deterministicPlan(symbol, mounted, windowDays, NO_ENGINE_REASON)

        // Nothing mounted means nothing to score; asking the engine would only
        // produce a plan that cannot run.
        if (mounted.isEmpty()) {
            return deterministic.copy(
                reason = "No forecasting model is mounted, so there is nothing to calibrate."
            )
        }

        val weightsPath = weightsPathProvider()
        if (weightsPath.isNullOrBlank()) {
            return deterministic.copy(
                reason = "Needle 3 has no mounted checkpoint, so the deterministic calibration plan runs."
            )
        }

        val answer = when (val result = engine.routeDecision(
            prompt = promptFor(symbol, mounted, windowDays),
            toolsJson = CALIBRATION_TOOLS_JSON,
            weightsPath = weightsPath,
            maxNewTokens = maxNewTokens
        )) {
            is Needle3Result.Generated -> result.rawJson
            is Needle3Result.Unavailable -> return deterministic.copy(
                reason = "Needle 3 could not run (${result.status}), so the deterministic " +
                    "calibration plan runs."
            )
        }

        val turn = NeedleRouteTurn.parse(answer)
            ?: return deterministic.copy(
                reason = "Needle 3 answered with text that is not the promised calibration " +
                    "JSON, so the deterministic plan runs."
            )

        if (turn.ungroundedFields.isNotEmpty()) {
            return deterministic.copy(
                reason = "Needle 3 could not ground ${turn.ungroundedFields.joinToString()}, " +
                    "so the deterministic calibration plan runs."
            )
        }

        if (turn.functionCalls.size != 1) {
            return deterministic.copy(
                reason = "Needle 3 returned ${turn.functionCalls.size} calibration calls; " +
                    "a plan must be exactly one, so the deterministic plan runs."
            )
        }

        val toolName = turn.functionCalls.single()
        val horizons = TOOL_TO_HORIZONS[toolName]
            ?: return deterministic.copy(
                reason = "Needle 3 chose '$toolName', which is not one of the calibration " +
                    "tools, so the deterministic plan runs."
            )

        val confidence = turn.confidence
        if (confidence != null && confidence < confidenceThreshold) {
            return deterministic.copy(
                reason = "Needle 3 chose '$toolName' at confidence " +
                    "${"%.2f".format(confidence)}, below the " +
                    "${"%.2f".format(confidenceThreshold)} act threshold, so the " +
                    "deterministic calibration plan runs."
            )
        }

        return CoinCalibrationPlan(
            symbol = symbol,
            windowDays = windowDays,
            horizons = horizons,
            modelIds = mounted,
            dispatchedBy = CalibrationPlanSource.NEEDLE3,
            reason = buildString {
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
        )
    }

    private fun deterministicPlan(
        symbol: String,
        mounted: List<String>,
        windowDays: Int,
        reason: String
    ): CoinCalibrationPlan = CoinCalibrationPlan(
        symbol = symbol,
        windowDays = windowDays,
        horizons = CalibrationHorizons.ALL,
        modelIds = mounted,
        dispatchedBy = CalibrationPlanSource.DETERMINISTIC,
        reason = reason
    )

    /** The facts the engine may ground its tool choice in, printed verbatim. */
    private fun promptFor(symbol: String, mounted: List<String>, windowDays: Int): String =
        buildString {
            appendLine("Ensemble calibration request.")
            appendLine("symbol: $symbol")
            appendLine("window_days: $windowDays")
            appendLine("mounted_models: ${mounted.joinToString(", ")}")
            appendLine("supported_horizons: ${CalibrationHorizons.ALL.joinToString(", ") { it.id }}")
            append("Choose the calibration tool this request needs.")
        }

    companion object {

        private const val NO_ENGINE_REASON =
            "The calibration plan is deterministic: every supported horizon is scored."

        /** Tool name -> the horizon set it calibrates. One tool per set, no catch-all. */
        val TOOL_TO_HORIZONS: Map<String, List<com.aistudio.alphapulse.domain.ensemble.CalibrationHorizon>> =
            linkedMapOf(
                "calibrate_all_horizons" to CalibrationHorizons.ALL,
                "calibrate_intraday_horizons" to CalibrationHorizons.INTRADAY,
                "calibrate_swing_horizons" to CalibrationHorizons.SWING,
                "calibrate_reference_horizon" to CalibrationHorizons.REFERENCE
            )

        /**
         * The calibration tool catalogue used as the decode grammar.
         *
         * Every tool has an empty parameter object, so the engine can never
         * withhold a call for want of a groundable argument.
         */
        const val CALIBRATION_TOOLS_JSON: String = """
[
  {
    "name": "calibrate_all_horizons",
    "description": "Score every supported horizon from one minute to one hour over the full thirty-day window.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "calibrate_intraday_horizons",
    "description": "Score only the intraday horizons one minute, five minutes and fifteen minutes over the full thirty-day window.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "calibrate_swing_horizons",
    "description": "Score only the swing horizons fifteen minutes, thirty minutes and one hour over the full thirty-day window.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  },
  {
    "name": "calibrate_reference_horizon",
    "description": "Score only the fifteen-minute reference horizon the decision router reads.",
    "parameters": {"type": "object", "properties": {}, "required": []}
  }
]
"""
    }
}
