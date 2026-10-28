package com.aistudio.alphapulse.domain.decision

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Needle3Engine - the on-device half of Cactus Needle 3 (§30).
 *
 * How the two halves fit together
 * -------------------------------
 *  1. the **weights** - `needle3.cact`, 35,335,380 bytes, downloaded from Hugging
 *     Face (`Cactus-Compute/needle3`) through the normal model pipeline, and
 *     verified by size and SHA-256 before it is mounted. Downloading is gated by
 *     the HardwareGatekeeper (mid-tier: 3.5 GB RAM, 4 cores).
 *  2. the **engine** - the Cactus static library (`libneedle.a` + `needle.h`),
 *     vendored under `app/src/main/cpp/engine/`, linked into the app by CMake and
 *     reached through JNI. That is what actually executes the weights.
 *
 * Both halves are real in this build. The engine is linked for `arm64-v8a` and
 * `armeabi-v7a`; Cactus publishes no x86_64 engine, so on an x86_64 emulator the
 * same code reports [Needle3EngineStatus.NotBundled] instead of crashing.
 *
 * The rule that never bends: **no engine or no weights means no text.** Every
 * failure below is a typed state carrying a reason and a remedy, and the caller
 * falls back to the deterministic narrator. Inventing a sentence would break
 * §59, so there is deliberately no "best effort" branch.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §59 AI Narrator Requirements,
 * §47 Model Download Architecture.
 */

/**
 * The structured market state handed to the edge model.
 *
 * The fields mirror the §28 example payload, so the same snapshot can feed the
 * deterministic narrator and the on-device model.
 */
data class Needle3Request(
    val symbol: String,
    val regime: String,
    val kinematic: Double,
    val painPath: Double,
    val alpha: Double,
    val tsfm: Double,
    val lobConfidence: Double,
    val liquidationBias: String,
    val coneBreaches: Int
)

/**
 * What the engine can do right now.
 *
 * Each non-[Ready] state names what is missing and what to do about it, because a
 * silent failure here would look like a model with nothing to say.
 */
sealed class Needle3EngineStatus {

    /** The engine is linked for this ABI and a checkpoint path was supplied. */
    data class Ready(val engineVersion: String) : Needle3EngineStatus()

    /** The engine is linked, but no checkpoint has been downloaded and mounted. */
    data class WeightsNotMounted(
        val reason: String,
        val remedy: String
    ) : Needle3EngineStatus()

    /** This build carries no Cactus engine for the device's ABI. */
    data class NotBundled(
        val reason: String,
        val remedy: String
    ) : Needle3EngineStatus()

    /** The engine rejected the checkpoint - a truncated or tampered download. */
    data class LoadFailed(
        val reason: String,
        val remedy: String
    ) : Needle3EngineStatus()

    /** The session or the completion failed; the engine said why. */
    data class InferenceFailed(
        val reason: String,
        val remedy: String
    ) : Needle3EngineStatus()
}

/** Outcome of asking Needle 3 for an explanation. */
sealed class Needle3Result {

    /**
     * The model produced an explanation.
     *
     * @property reasoning the model's own words
     * @property confidence the calibrated score the engine attaches to the turn,
     *           or null when the answer carried none
     * @property rawJson the untouched engine answer, kept for the prediction ledger
     * @property engineVersion which engine/ABI produced it
     */
    data class Generated(
        val reasoning: String,
        val confidence: Double?,
        val rawJson: String,
        val engineVersion: String
    ) : Needle3Result()

    /**
     * The model could not run. The caller must fall back to the deterministic
     * narrator and surface this state; it must never invent text.
     */
    data class Unavailable(val status: Needle3EngineStatus) : Needle3Result()
}

/**
 * The run path: load the checkpoint, start a session, complete one turn.
 *
 * The bridge is injected so the path can be exercised in unit tests without a
 * device or the NDK.
 */
class Needle3Engine(
    private val bridge: Needle3NativeBridge = JniNeedle3Bridge()
) {

    /** Guards the native calls: the Cactus engine is process-global and not thread safe. */
    private val lock = Any()

    /** Which checkpoint is currently loaded, so it is loaded once, not per turn. */
    private var loadedWeightsPath: String? = null

    /** Which (system prompt, tool schema) prefix is tokenized, so a switch re-opens the session. */
    private var sessionKey: String? = null

    /** `cactus-needle3/arm64-v8a`, shown next to generated text. */
    val engineVersion: String = "cactus-needle3/${bridge.abiDescription}"

    /**
     * Whether this build could run the given checkpoint.
     *
     * Deliberately pure: it reports capability and never touches the 34 MB file,
     * so it is safe to call from the UI thread. The actual load happens in
     * [generateBrief], where a failure belongs to that turn.
     *
     * @param weightsPath absolute path to `needle3.cact`, or null when nothing is mounted
     */
    fun status(weightsPath: String?): Needle3EngineStatus = when {
        !bridge.engineLinked -> NOT_BUNDLED
        weightsPath.isNullOrBlank() -> WEIGHTS_NOT_MOUNTED
        else -> Needle3EngineStatus.Ready(engineVersion)
    }

    /** True only when a brief could actually be attempted. */
    fun isAvailable(weightsPath: String?): Boolean =
        status(weightsPath) is Needle3EngineStatus.Ready

    /**
     * Ask the model for an institutional brief.
     *
     * Every failure returns [Needle3Result.Unavailable] with the engine's own
     * reason, so the caller can show it and keep the deterministic narrator.
     *
     * @param request the structured market state to explain
     * @param weightsPath absolute path to the mounted `needle3.cact`
     * @param maxNewTokens generation cap; larger caps reserve context room
     */
    fun generateBrief(
        request: Needle3Request,
        weightsPath: String?,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS
    ): Needle3Result {
        val answer = when (val turn = runTurn(
            systemPrompt = SYSTEM_PROMPT,
            toolsJson = TOOLS_JSON,
            prompt = promptFor(request),
            weightsPath = weightsPath,
            maxNewTokens = maxNewTokens
        )) {
            is TurnRun.Failed -> return Needle3Result.Unavailable(turn.status)
            is TurnRun.Answered -> turn.answer
        }

        val parsed = parseAnswer(answer)
            ?: return Needle3Result.Unavailable(
                Needle3EngineStatus.InferenceFailed(
                    reason = "The engine returned text that is not the promised JSON object, so " +
                        "it cannot be attributed to the market snapshot.",
                    remedy = "Retry the turn; a malformed answer is never shown to the user (§59)."
                )
            )

        // §59: the entity that displays this text must still pass it through
        // NarratorConstraintValidator, which is what stops the model introducing
        // a number that is not in the payload.
        return Needle3Result.Generated(
            reasoning = parsed.reasoning,
            confidence = parsed.confidence,
            rawJson = answer,
            engineVersion = engineVersion
        )
    }

    /**
     * Run one routing turn: the same engine, a different persona and grammar.
     *
     * This is the on-device half of §29. The answer is returned untouched so
     * [Needle3DecisionRouter] can judge the tool that was chosen. Unlike a brief, a
     * routing turn need not carry prose: the engine may return an empty
     * `reasoning` for a call, and that is a valid answer rather than a malformed
     * one.
     *
     * @param prompt the decision snapshot, printed verbatim so its values are groundable
     * @param toolsJson the routing catalogue used as the decode grammar
     */
    fun routeDecision(
        prompt: String,
        toolsJson: String,
        weightsPath: String?,
        maxNewTokens: Int = DEFAULT_ROUTING_MAX_NEW_TOKENS
    ): Needle3Result {
        val answer = when (val turn = runTurn(
            systemPrompt = ROUTING_SYSTEM_PROMPT,
            toolsJson = toolsJson,
            prompt = prompt,
            weightsPath = weightsPath,
            maxNewTokens = maxNewTokens
        )) {
            is TurnRun.Failed -> return Needle3Result.Unavailable(turn.status)
            is TurnRun.Answered -> turn.answer
        }

        val map = runCatching { MAP_ADAPTER.fromJson(answer) }.getOrNull()
        if (map == null ||
            (!map.containsKey("function_calls") && !map.containsKey("suppressed_calls"))
        ) {
            return Needle3Result.Unavailable(
                Needle3EngineStatus.InferenceFailed(
                    reason = "The engine returned text that is not the promised routing JSON, " +
                        "so it cannot be attributed to a decision tool.",
                    remedy = "Retry the turn; an unparseable route is never executed (§29)."
                )
            )
        }

        return Needle3Result.Generated(
            reasoning = (map["reasoning"] as? String).orEmpty(),
            confidence = (map["confidence"] as? Number)?.toDouble(),
            rawJson = answer,
            engineVersion = engineVersion
        )
    }

    /**
     * Load the checkpoint, open the session for this (persona, grammar) pair, and
     * run one turn. Every native call happens under [lock]: the Cactus engine is
     * process-global and not thread safe.
     */
    private fun runTurn(
        systemPrompt: String,
        toolsJson: String,
        prompt: String,
        weightsPath: String?,
        maxNewTokens: Int
    ): TurnRun = synchronized(lock) {
        // 1. Capability first: no engine or no checkpoint means no answer at all.
        when (val current = status(weightsPath)) {
            is Needle3EngineStatus.Ready -> Unit
            else -> return TurnRun.Failed(current)
        }

        val path = weightsPath!!

        // 2. Map the checkpoint once per path. `needle_load` reads it in place.
        if (loadedWeightsPath != path) {
            val code = bridge.loadWeights(path)
            if (code < 0) {
                return TurnRun.Failed(
                    Needle3EngineStatus.LoadFailed(
                        reason = bridge.lastError(),
                        remedy = "Delete the downloaded Needle 3 weights and download them again; " +
                            "the engine rejects a truncated or altered checkpoint."
                    )
                )
            }
            loadedWeightsPath = path
            sessionKey = null
        }

        // 3. Tokenize the static prefix once per (persona, grammar). Switching
        //    between the narrator and the router restarts the session, because the
        //    prefix is what constrains the decode.
        val key = "$systemPrompt\u0000$toolsJson"
        if (sessionKey != key) {
            if (sessionKey != null) bridge.reset()
            val prefixTokens = bridge.beginSession(systemPrompt, toolsJson)
            if (prefixTokens < 0) {
                return TurnRun.Failed(
                    Needle3EngineStatus.InferenceFailed(
                        reason = bridge.lastError(),
                        remedy = "Shorten the system prompt or the tool schemas so the prefix " +
                            "fits the model context."
                    )
                )
            }
            sessionKey = key
        }

        // 4. One turn.
        val answer = bridge.complete(prompt, maxNewTokens)
            ?: return TurnRun.Failed(
                Needle3EngineStatus.InferenceFailed(
                    reason = bridge.lastError(),
                    remedy = "Retry the turn; if it keeps failing the device is out of memory " +
                        "for the orchestration model."
                )
            )

        TurnRun.Answered(answer)
    }

    /**
     * Forgets the conversation, keeping the loaded checkpoint.
     *
     * Called when the app leaves research mode so a new session cannot inherit the
     * previous session's context.
     */
    fun release() = synchronized(lock) {
        if (sessionKey != null) bridge.reset()
        sessionKey = null
    }

    /**
     * One line for the model dock and the diagnostics panel.
     *
     * It always says whether the engine is present, because "the model has nothing
     * to say" and "the model cannot run" must not look the same to a user.
     */
    fun describeStatus(weightsPath: String?): String = when (val current = status(weightsPath)) {
        is Needle3EngineStatus.Ready ->
            "Needle 3 engine ${current.engineVersion} ready; the mounted checkpoint will run on device."

        is Needle3EngineStatus.WeightsNotMounted ->
            "Needle 3 engine is bundled for ${bridge.abiDescription}, but no checkpoint is " +
                "downloaded: ${current.reason}"

        is Needle3EngineStatus.NotBundled ->
            "Needle 3 cannot run on this build: ${current.reason}"

        is Needle3EngineStatus.LoadFailed ->
            "Needle 3 rejected the downloaded checkpoint: ${current.reason}"

        is Needle3EngineStatus.InferenceFailed ->
            "Needle 3 could not finish a turn: ${current.reason}"
    }

    /** The engine's answer, or null when it is not the JSON object §30 promises. */
    private fun parseAnswer(answer: String): ParsedAnswer? {
        val map = runCatching { MAP_ADAPTER.fromJson(answer) }.getOrNull() ?: return null
        val reasoning = map["reasoning"] as? String ?: return null
        if (reasoning.isBlank()) return null
        val confidence = (map["confidence"] as? Number)?.toDouble()
        return ParsedAnswer(reasoning, confidence)
    }

    /** Builds the single user turn from the market snapshot. */
    private fun promptFor(request: Needle3Request): String = buildString {
        append("Snapshot for ")
        append(request.symbol)
        append(". Regime: ")
        append(request.regime)
        append(". Kinematic score: ")
        append(request.kinematic)
        append(". Pain-path score: ")
        append(request.painPath)
        append(". Alpha: ")
        append(request.alpha)
        append(". TSFM drift: ")
        append(request.tsfm)
        append(". LOB confidence: ")
        append(request.lobConfidence)
        append(". Liquidation bias: ")
        append(request.liquidationBias)
        append(". Cone breaches: ")
        append(request.coneBreaches)
        append(". Explain what the ensemble is testing and what would invalidate it.")
    }

    private data class ParsedAnswer(val reasoning: String, val confidence: Double?)

    /** One turn's outcome: either the engine's own answer or a typed refusal. */
    private sealed class TurnRun {
        data class Answered(val answer: String) : TurnRun()
        data class Failed(val status: Needle3EngineStatus) : TurnRun()
    }

    companion object {

        /** Generation cap per turn. Kept small: a brief is a paragraph, not an essay. */
        const val DEFAULT_MAX_NEW_TOKENS = 160

        /** Cap for a routing turn: one tool call plus a one-line derivation. */
        const val DEFAULT_ROUTING_MAX_NEW_TOKENS = 96

        /**
         * The persona handed to the engine.
         *
         * It restates §59 in the model's own instructions: describe the numbers it
         * was given, never introduce a new one, never promise a return.
         */
        const val SYSTEM_PROMPT =
            "You are the research narrator of a quantitative crypto forecasting terminal. " +
                "Explain, in at most four sentences, what the ensemble is currently testing and " +
                "which single observation would falsify it. Use only the numbers in the " +
                "snapshot. Never invent a price, a probability or a forecast that is not in the " +
                "snapshot. Never promise a return. Write plainly, for a professional reader."

        /**
         * No tools are declared on purpose.
         *
         * AlphaPulse asks for an explanation, not an action. Declaring tools would let
         * the model choose a function the app cannot execute, and the Cactus engine
         * would then answer with a function call instead of reasoning.
         */
        const val TOOLS_JSON = "[]"

        /**
         * Persona for a routing turn (§29), kept apart from the narrator's.
         *
         * The rule is the same one the router enforces in code: one tool, and only
         * numbers that appear in the snapshot.
         */
        const val ROUTING_SYSTEM_PROMPT =
            "You are the decision router of a quantitative crypto forecasting terminal. " +
                "Read the numeric snapshot and call exactly one decision tool. " +
                "Use only the numbers in the snapshot, never invent a value, and never " +
                "call more than one tool. The snapshot is the only evidence you have."

        /** The app-wide engine, backed by the vendored Cactus native library. */
        val shared: Needle3Engine by lazy { Needle3Engine() }

        /** Shared JSON reader: the engine's answer is a plain object, not a typed schema. */
        private val MAP_ADAPTER = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )

        private val NOT_BUNDLED = Needle3EngineStatus.NotBundled(
            reason = "This build has no Cactus engine for the device ABI. Cactus publishes " +
                "engines for arm64-v8a and armeabi-v7a only, so the 34 MB weights cannot be " +
                "executed here.",
            remedy = "Install the arm64-v8a APK on a physical device; narrative output keeps " +
                "using the deterministic narrator (§59) until then."
        )

        private val WEIGHTS_NOT_MOUNTED = Needle3EngineStatus.WeightsNotMounted(
            reason = "the needle3.cact checkpoint has not been downloaded and verified yet.",
            remedy = "Download Cactus Needle 3 from the model dock on a mid-tier device " +
                "(3.5 GB RAM and 4 cores or more)."
        )
    }
}
