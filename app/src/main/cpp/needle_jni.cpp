// The JNI bridge between Kotlin and the Cactus Needle 3 engine (§30).
//
// What this file is
// -----------------
// Kotlin cannot call a C library directly, so this thin layer translates each
// Kotlin call into the matching `needle_*` call and translates the answer back.
// Every rule the Cactus header states is honoured here:
//
//   * the engine holds ONE process-global model and is NOT thread safe, so every
//     call is taken under a mutex;
//   * `needle_load` maps the checkpoint in place, so the bytes must stay alive
//     for as long as the engine is loaded - they are mmap'd, never freed;
//   * a negative return means failure and `needle_last_error()` carries the
//     reason, so no failure is ever guessed at or invented here.
//
// JNI naming rule: `Java_<package>_<Class>_<method>` with dots replaced by
// underscores, which is why the function names below look so long.

#include <jni.h>

#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#if ALPHAPULSE_NEEDLE_ENGINE_BUNDLED
#include "needle.h"
#endif

namespace {

constexpr const char* kLogTag = "Needle3Jni";

/** Room for the engine's UTF-8 JSON answer: reasoning text plus tool calls. */
constexpr int kOutputCapacity = 64 * 1024;

/** The engine is process-global and not thread safe, so calls are serialised. */
std::mutex g_engine_mutex;

/** True once a checkpoint has been mapped into the engine. */
bool g_weights_loaded = false;

/**
 * The checkpoint stays mapped because `needle_load` reads it in place, so this
 * pointer outlives the call that set it and is only replaced on a reload.
 */
void* g_mapped_weights = nullptr;
size_t g_mapped_size = 0;
int g_mapped_fd = -1;

/**
 * The reason for the last failure, in the engine's own words where possible.
 *
 * Kotlin reads this through `lastError()`, so a user never sees a bare -1.
 */
std::string g_last_error = "no engine call has failed yet";

void remember_error(const std::string& detail) {
    g_last_error = detail;
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", detail.c_str());
}

/** Releases a previous checkpoint mapping so a reload cannot leak it. */
void release_mapping_locked() {
    if (g_mapped_weights != nullptr) {
        munmap(g_mapped_weights, g_mapped_size);
        g_mapped_weights = nullptr;
        g_mapped_size = 0;
    }
    if (g_mapped_fd >= 0) {
        close(g_mapped_fd);
        g_mapped_fd = -1;
    }
    g_weights_loaded = false;
}

/** Copies a C string into a Java string, tolerating a null pointer. */
jstring to_jstring(JNIEnv* env, const char* text) {
    return env->NewStringUTF(text == nullptr ? "" : text);
}

/** Reads a Java string as a UTF-8 std::string ("" when the argument is null). */
std::string from_jstring(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string copy(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

/** Trims an engine answer to the bytes the engine actually wrote. */
std::string read_output(const char* buffer, int capacity, int returned) {
    size_t length;
    if (returned > 0 && returned < capacity && buffer[returned] == '\0') {
        // The engine obeyed the header's contract and returned the byte count.
        length = static_cast<size_t>(returned);
    } else {
        // Fall back to the NUL terminator so a shorter buffer cannot truncate.
        length = strnlen(buffer, static_cast<size_t>(capacity));
    }
    return std::string(buffer, length);
}

}  // namespace

extern "C" {

/**
 * Which ABI this bridge was compiled for.
 *
 * Reported to Kotlin so the diagnostics line can name the ABI that has no
 * published engine instead of just saying "unavailable".
 */
JNIEXPORT jstring JNICALL
Java_com_aistudio_alphapulse_domain_decision_Needle3Jni_abi(JNIEnv* env, jobject /* this */) {
#if defined(__aarch64__)
    return to_jstring(env, "arm64-v8a");
#elif defined(__arm__)
    return to_jstring(env, "armeabi-v7a");
#elif defined(__x86_64__)
    return to_jstring(env, "x86_64");
#else
    return to_jstring(env, "unknown");
#endif
}

/** True when this build linked the Cactus static engine. */
JNIEXPORT jboolean JNICALL
Java_com_aistudio_alphapulse_domain_decision_Needle3Jni_engineLinked(
    JNIEnv* /* env */, jobject /* this */) {
#if ALPHAPULSE_NEEDLE_ENGINE_BUNDLED
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * Maps the downloaded `needle3.cact` checkpoint and loads it into the engine.
 *
 * @return the engine's own tokenizer/model status (>= 0), or a negative value on
 *         failure; the reason is then available from [lastError].
 */
JNIEXPORT jint JNICALL
Java_com_aistudio_alphapulse_domain_decision_Needle3Jni_loadWeights(
    JNIEnv* env, jobject /* this */, jstring path) {
#if !ALPHAPULSE_NEEDLE_ENGINE_BUNDLED
    remember_error(
        "This build has no Cactus engine for the device ABI. The publisher ships "
        "engines for arm64-v8a and armeabi-v7a only.");
    return -1;
#else
    std::lock_guard<std::mutex> guard(g_engine_mutex);

    const std::string weights_path = from_jstring(env, path);
    if (weights_path.empty()) {
        remember_error("No checkpoint path was supplied to the Needle 3 engine.");
        return -1;
    }

    // Open read-only: the engine only ever reads the container.
    const int fd = open(weights_path.c_str(), O_RDONLY);
    if (fd < 0) {
        remember_error("Checkpoint could not be opened: " + weights_path + " (" +
                       std::strerror(errno) + ")");
        return -1;
    }

    struct stat info {};
    if (fstat(fd, &info) != 0 || info.st_size <= 0) {
        close(fd);
        remember_error("Checkpoint is empty or unreadable: " + weights_path);
        return -1;
    }

    const size_t size = static_cast<size_t>(info.st_size);
    void* mapped = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapped == MAP_FAILED) {
        close(fd);
        remember_error("Checkpoint could not be mapped into memory: " + weights_path);
        return -1;
    }

    // The engine reads these bytes in place for as long as it is loaded, so an
    // earlier mapping is only released now that its replacement is ready.
    release_mapping_locked();

    g_mapped_fd = fd;
    g_mapped_weights = mapped;
    g_mapped_size = size;

    const int status = needle_load(
        static_cast<const unsigned char*>(mapped),
        static_cast<unsigned long long>(size));

    if (status < 0) {
        const std::string engine_error =
            "Cactus engine rejected the checkpoint: " + std::string(needle_last_error());
        release_mapping_locked();
        remember_error(engine_error);
        return status;
    }

    g_weights_loaded = true;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "needle3.cact loaded (%zu bytes)", size);
    return status;
#endif
}

/**
 * Tokenizes the static prefix (system prompt plus tool schemas) for this session.
 *
 * @return the prefix length in tokens on success, negative on failure.
 */
JNIEXPORT jint JNICALL
Java_com_aistudio_alphapulse_domain_decision_Needle3Jni_begin(
    JNIEnv* env, jobject /* this */, jstring system_prompt, jstring tools_json) {
#if !ALPHAPULSE_NEEDLE_ENGINE_BUNDLED
    remember_error("This build has no Cactus engine for the device ABI.");
    return -1;
#else
    std::lock_guard<std::mutex> guard(g_engine_mutex);

    if (!g_weights_loaded) {
        remember_error("Load the needle3 checkpoint before starting a session.");
        return -1;
    }

    const std::string prompt = from_jstring(env, system_prompt);
    const std::string tools = from_jstring(env, tools_json);

    // A null third argument means "no retrieval index": the declared tools are
    // the whole catalogue, which is what AlphaPulse sends.
    const int prefix_tokens = needle_init(
        prompt.c_str(),
        tools.empty() ? nullptr : tools.c_str(),
        nullptr);

    if (prefix_tokens < 0) {
        remember_error(
            "System prompt plus tool schemas do not fit the model context: " +
            std::string(needle_last_error()));
        return prefix_tokens;
    }

    return prefix_tokens;
#endif
}

/**
 * Runs one completion.
 *
 * @return the engine's UTF-8 JSON answer, or null when the call failed; the
 *         reason is then available from [lastError].
 */
JNIEXPORT jbyteArray JNICALL
Java_com_aistudio_alphapulse_domain_decision_Needle3Jni_complete(
    JNIEnv* env, jobject /* this */, jstring input, jint max_new_tokens) {
#if !ALPHAPULSE_NEEDLE_ENGINE_BUNDLED
    remember_error("This build has no Cactus engine for the device ABI.");
    return nullptr;
#else
    std::lock_guard<std::mutex> guard(g_engine_mutex);

    if (!g_weights_loaded) {
        remember_error("Load the needle3 checkpoint before asking for a completion.");
        return nullptr;
    }

    const std::string text = from_jstring(env, input);
    if (text.empty()) {
        remember_error("An empty prompt cannot be completed.");
        return nullptr;
    }

    std::vector<char> buffer(static_cast<size_t>(kOutputCapacity), '\0');

    // pcm is null because this is a text turn, not a speech turn.
    const int returned = needle_complete(
        text.c_str(),
        nullptr,
        0,
        static_cast<int>(max_new_tokens),
        buffer.data(),
        kOutputCapacity);

    if (returned < 0) {
        remember_error("Cactus engine failed to complete the turn: " +
                       std::string(needle_last_error()));
        return nullptr;
    }

    const std::string answer = read_output(buffer.data(), kOutputCapacity, returned);
    if (answer.empty()) {
        remember_error("Cactus engine returned no text for this turn.");
        return nullptr;
    }

    // Returned as raw bytes: the answer is UTF-8, and Java strings are UTF-16, so
    // decoding is done once, on the Kotlin side, with the correct charset.
    jbyteArray out = env->NewByteArray(static_cast<jsize>(answer.size()));
    if (out == nullptr) {
        remember_error("Not enough memory to copy the engine's answer.");
        return nullptr;
    }
    env->SetByteArrayRegion(
        out, 0, static_cast<jsize>(answer.size()),
        reinterpret_cast<const jbyte*>(answer.data()));
    return out;
#endif
}

/** The reason for the most recent failure, in plain words. */
JNIEXPORT jstring JNICALL
Java_com_aistudio_alphapulse_domain_decision_Needle3Jni_lastError(
    JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> guard(g_engine_mutex);
    return to_jstring(env, g_last_error.c_str());
}

/** Clears the engine's conversation state, keeping the loaded checkpoint. */
JNIEXPORT void JNICALL
Java_com_aistudio_alphapulse_domain_decision_Needle3Jni_reset(
    JNIEnv* /* env */, jobject /* this */) {
#if ALPHAPULSE_NEEDLE_ENGINE_BUNDLED
    std::lock_guard<std::mutex> guard(g_engine_mutex);
    if (g_weights_loaded) {
        needle_reset();
    }
#endif
}

}  // extern "C"
