package com.aistudio.alphapulse.domain.decision

/**
 * Needle3Engine - the on-device half of Cactus Needle 3 (§30).
 *
 * There are two separate pieces to running Needle 3:
 *
 *  1. the **weights** - `needle3.cact`, ~34 MB, downloaded from Hugging Face
 *     (`Cactus-Compute/needle3`) through the normal model pipeline and verified by
 *     size and SHA-256 before it is mounted. That part is wired and gated by the
 *     HardwareGatekeeper (mid-tier: 3.5 GB RAM, 4 cores).
 *  2. the **engine** - a native C/C++ library (`android-arm64/libneedle.a`) plus
 *     its header (`needle.h`), which is what actually executes the weights through
 *     JNI. This build does **not** contain that native layer: there is no NDK
 *     directory, no `externalNativeBuild` block and no vendored `libneedle.a`.
 *
 * Because of (2) this object deliberately **fails closed**. Downloading the weights
 * and then pretending to run them would produce fabricated narrative text, which
 * is exactly what §59 forbids. Instead every run returns
 * [Needle3Result.Unavailable] carrying the reason and the remedy, and the caller
 * keeps using the deterministic narrator until the engine ships.
 *
 * When the native layer lands, only [status] and [generateBrief] change: the
 * request/result types and every caller stay as they are.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §59 AI Narrator Requirements, §47 Model Download Architecture
 */

/**
 * The structured market state handed to the edge model.
 *
 * The fields mirror the §28 example payload, so the same snapshot can feed the
 * deterministic narrator and, later, the on-device model.
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

/** Whether the engine can execute weights in this build. */
sealed class Needle3EngineStatus {

    /** The native engine is present and can run the mounted checkpoint. */
    data class Ready(val engineVersion: String) : Needle3EngineStatus()

    /**
     * The native engine is not part of this build, so nothing may be inferred.
     *
     * @property reason what is missing, in one sentence
     * @property remedy what the user/engineer can do about it
     */
    data class NotBundled(val reason: String, val remedy: String) : Needle3EngineStatus()
}

/** Outcome of asking Needle 3 for an explanation. */
sealed class Needle3Result {

    /** The model produced text. Only reachable once the engine is bundled. */
    data class Generated(val text: String, val engineVersion: String) : Needle3Result()

    /**
     * The model could not run. The caller must fall back to the deterministic
     * narrator and surface this state; it must never invent text.
     */
    data class Unavailable(val status: Needle3EngineStatus.NotBundled) : Needle3Result()
}

object Needle3Engine {

    /**
     * Stated once, here, so the reason shown to a user and the reason recorded in
     * the traceability matrix cannot drift apart.
     */
    private val NOT_BUNDLED = Needle3EngineStatus.NotBundled(
        reason = "The Cactus static engine (android-arm64/libneedle.a + needle.h) is not " +
            "linked into this build, so needle3 weights cannot be executed on device.",
        remedy = "Download the needle3.cact weights in the model dock only when the mid-tier " +
            "gate allows it; narrative output keeps using the deterministic narrator (§59) " +
            "until the NDK/JNI engine is bundled."
    )

    /**
     * Current engine state.
     *
     * Always [Needle3EngineStatus.NotBundled] in this build - there is no native
     * layer to call. It is a `val`, not a guess: the APK contains no `.so` or `.a`
     * for the Cactus engine.
     */
    val status: Needle3EngineStatus = NOT_BUNDLED

    /** True only when weights could actually be executed. */
    val isAvailable: Boolean
        get() = status is Needle3EngineStatus.Ready

    /**
     * Ask the model for an institutional brief.
     *
     * Fails closed: with no engine there is no text, and the caller is told exactly
     * why. This is a deliberate refusal, not an error being swallowed.
     *
     * @param request the structured market state to explain
     */
    @Suppress("UNUSED_PARAMETER")
    fun generateBrief(request: Needle3Request): Needle3Result =
        when (val current = status) {
            is Needle3EngineStatus.NotBundled -> Needle3Result.Unavailable(current)
            is Needle3EngineStatus.Ready ->
                // Unreachable until the native bridge exists; documented rather than
                // faked so this branch can never fabricate narrative text.
                Needle3Result.Unavailable(NOT_BUNDLED)
        }

    /**
     * One-line state for the model dock and the diagnostics panel.
     */
    fun describeStatus(): String = when (val current = status) {
        is Needle3EngineStatus.NotBundled ->
            "Needle 3 weights can be downloaded, but the native engine is not bundled: " +
                current.reason
        is Needle3EngineStatus.Ready ->
            "Needle 3 engine ${current.engineVersion} ready."
    }
}
