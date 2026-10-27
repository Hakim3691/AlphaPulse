package com.aistudio.alphapulse.domain.decision

/**
 * The raw JNI surface of the vendored Cactus Needle 3 engine (§30).
 *
 * This is the thinnest possible layer: one Kotlin function per function in
 * `app/src/main/cpp/needle_jni.cpp`, and one C function per function in Cactus's
 * published `needle.h`. Keeping it this literal means the mapping between the
 * three files can be checked by eye.
 *
 * Return convention (from the Cactus header, restated so it cannot be misread):
 * a **negative** number means the call failed, and the human-readable reason is
 * then in [lastError]. Nothing is fabricated here - if the engine refuses, the
 * caller is told the engine's own words.
 *
 * The functions are declared but never implemented in Kotlin: the native library
 * `libalphapulse_needle.so` provides them. They live behind
 * [Needle3NativeBridge] so the pure-Kotlin run path can be unit tested with a
 * fake, because a JVM test cannot load an Android `.so`.
 *
 * This object is deliberately **public, not `internal`**: the JVM looks a native
 * method up by its exact name, and Kotlin appends a module suffix to internal
 * members, so an internal declaration would be named `loadWeights$app_debug` and
 * every call would fail to resolve. `JniNeedle3BridgeBddSpec` pins that.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §47 Model Download Architecture
 */
object Needle3Jni {

    /** The ABI this bridge was compiled for, e.g. `arm64-v8a`. */
    external fun abi(): String

    /** True when this build linked the Cactus static engine. */
    external fun engineLinked(): Boolean

    /** Maps and loads `needle3.cact`; returns the engine's status or a negative failure. */
    external fun loadWeights(path: String): Int

    /** Tokenizes the system prompt plus tool schemas; returns the prefix token count. */
    external fun begin(systemPrompt: String, toolsJson: String): Int

    /** Runs one completion; returns the engine's UTF-8 JSON answer or null. */
    external fun complete(input: String, maxNewTokens: Int): ByteArray?

    /** Why the most recent call failed, in the engine's own words. */
    external fun lastError(): String

    /** Clears conversation state, keeping the loaded checkpoint. */
    external fun reset()
}

/**
 * The seam between the run path and the native engine.
 *
 * Declaring it as an interface is what makes the run path testable: the tests
 * substitute a [FakeNeedle3Bridge] and can therefore drive every state -
 * bundled, unbundled, rejected checkpoint, failed completion - without a device,
 * without the NDK, and without ever inventing model output.
 */
interface Needle3NativeBridge {

    /** Human-readable ABI, used in the engine version string and the diagnostics line. */
    val abiDescription: String

    /** Whether this build carries the Cactus engine for this ABI. */
    val engineLinked: Boolean

    /** @return the engine's status, or a negative failure code */
    fun loadWeights(path: String): Int

    /** @return the prefix token count, or a negative failure code */
    fun beginSession(systemPrompt: String, toolsJson: String): Int

    /** @return the engine's answer, or null when the call failed */
    fun complete(input: String, maxNewTokens: Int): String?

    /** The reason for the most recent failure. */
    fun lastError(): String

    /** Clears conversation state. */
    fun reset()
}

/**
 * The real bridge: the Cactus engine through JNI.
 *
 * `System.loadLibrary` is wrapped rather than left to throw, because it fails for
 * a legitimate, expected reason: the app ships an x86_64 APK for emulators and
 * Cactus publishes no x86_64 engine. In that case [engineLinked] is false, every
 * call returns [FAILED] instead of throwing `UnsatisfiedLinkError`, and the run
 * path reports the state to the user. A crash is not an acceptable answer.
 */
class JniNeedle3Bridge : Needle3NativeBridge {

    /** Loaded in a `runCatching` so a missing ABI is a value, not an exception. */
    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary(NATIVE_LIBRARY_NAME)
    }.isSuccess

    override val abiDescription: String
        get() = if (libraryLoaded) runCatching { Needle3Jni.abi() }.getOrDefault("unknown") else "not loaded"

    override val engineLinked: Boolean
        get() = libraryLoaded && runCatching { Needle3Jni.engineLinked() }.getOrDefault(false)

    override fun loadWeights(path: String): Int =
        if (engineLinked) Needle3Jni.loadWeights(path) else FAILED

    override fun beginSession(systemPrompt: String, toolsJson: String): Int =
        if (engineLinked) Needle3Jni.begin(systemPrompt, toolsJson) else FAILED

    override fun complete(input: String, maxNewTokens: Int): String? {
        if (!engineLinked) return null
        // The C side hands back UTF-8 bytes; decoding happens here, once, with the
        // charset the engine promises.
        val bytes = Needle3Jni.complete(input, maxNewTokens) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    override fun lastError(): String =
        if (libraryLoaded) runCatching { Needle3Jni.lastError() }.getOrDefault(NO_REASON)
        else NO_REASON

    override fun reset() {
        if (engineLinked) Needle3Jni.reset()
    }

    private companion object {
        /** Matches the CMake target `alphapulse_needle` -> `libalphapulse_needle.so`. */
        const val NATIVE_LIBRARY_NAME = "alphapulse_needle"

        const val FAILED = -1
        const val NO_REASON = "the native engine is not loaded in this build"
    }
}

/**
 * A scripted bridge for tests.
 *
 * It records the calls made to it, so a specification can assert not only the
 * result but the order of the steps that produced it.
 */
class FakeNeedle3Bridge(
    override val abiDescription: String = "arm64-v8a",
    override val engineLinked: Boolean = true,
    private val loadStatus: Int = 0,
    private val beginStatus: Int = 12,
    private val answer: String? = null,
    private var error: String = "scripted failure"
) : Needle3NativeBridge {

    /** Every call this bridge received, in order. */
    val calls = mutableListOf<String>()

    override fun loadWeights(path: String): Int {
        calls += "loadWeights($path)"
        return loadStatus
    }

    override fun beginSession(systemPrompt: String, toolsJson: String): Int {
        calls += "beginSession"
        return beginStatus
    }

    override fun complete(input: String, maxNewTokens: Int): String? {
        calls += "complete($maxNewTokens)"
        return answer
    }

    override fun lastError(): String = error

    override fun reset() {
        calls += "reset"
    }

    /** Lets a test make the next failure explain itself differently. */
    fun failWith(reason: String) {
        error = reason
    }
}
