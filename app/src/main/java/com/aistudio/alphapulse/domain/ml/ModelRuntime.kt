package com.aistudio.alphapulse.domain.ml

import java.io.Closeable
import java.io.File

/**
 * On-device neural inference runtime (spec §13, §24-27, §27 backend capability test).
 *
 * The quant/ML domain must stay a plain-JVM surface, so the runtime is modelled
 * as a small set of pure interfaces. Android-specific implementations
 * (`ai.onnxruntime` ORT Mobile bindings, ExecuTorch JNI, NNAPI delegate) are
 * plugged in through [ModelRuntimeRegistry] at composition time; unit tests can
 * register a fake runtime and exercise the exact production inference contract
 * without a device, an NPU or a model file.
 *
 * Every engine built on top of this layer follows the same rule mandated by the
 * PRD: the neural path runs when a deployable artifact + a compatible runtime
 * are present, and the deterministic implementation is used (and labelled) as an
 * explicit fallback otherwise. Nothing silently pretends to be neural.
 */

/** Hardware/runtime backend a model can be executed on. */
enum class InferenceBackend(val displayName: String, val accelerator: String) {
    /** `ai.onnxruntime` ORT Mobile, optionally with the NNAPI execution provider. */
    ONNX_RUNTIME_MOBILE("ONNX Runtime Mobile", "CPU/GPU/NPU"),

    /** PyTorch ExecuTorch (`.pte`) with a Vulkan/NNAPI delegate. */
    EXECUTORCH("ExecuTorch", "CPU/GPU/NPU"),

    /** Android Neural Networks API delegate (quantized inference only). */
    NNAPI("Android NNAPI", "NPU/DSP"),

    /** Plain CPU math — the deterministic fallback tier, never a neural claim. */
    DETERMINISTIC("Deterministic", "CPU")
}

/**
 * A model file on device plus the metadata required to deploy it safely.
 *
 * [licenseApproved] exists because the PRD explicitly requires model licensing
 * validation before a production checkpoint is selected (TimesFM 3.0 weights, for
 * example, carry restrictions). An artifact that is not approved is never loaded,
 * even when the file and the runtime are both present.
 */
data class ModelArtifact(
    val modelId: String,
    val version: String,
    val file: File?,
    val quantization: String,
    val preferredBackend: InferenceBackend,
    val parameterCount: Int,
    val sizeBytes: Long,
    val licenseId: String = "unknown",
    val licenseApproved: Boolean = false,
    val inputNames: List<String> = emptyList(),
    val outputNames: List<String> = emptyList(),
    val requiresDropoutSampling: Boolean = false
) {
    /** True when the artifact can actually be executed on device today. */
    fun isDeployable(): Boolean =
        licenseApproved && file != null && file.exists() && file.length() > 0L

    /** Human-readable provenance tag stamped onto every prediction (§55). */
    fun provenance(): String = "$modelId@$version/$quantization/${preferredBackend.name}"
}

/** An open, executable model session produced by an [InferenceRuntime]. */
interface InferenceSession : Closeable {
    val backend: InferenceBackend
    val artifact: ModelArtifact

    /**
     * Run one forward pass. Tensor names must match the artifact's declared
     * inputs; outputs are keyed by the model's declared output names.
     */
    fun run(inputs: Map<String, FloatArray>): Map<String, FloatArray>
}

/** A backend capable of opening [ModelArtifact] sessions on this device. */
interface InferenceRuntime {
    val backend: InferenceBackend

    /** Whether the runtime is present and usable on this device right now. */
    fun isAvailable(): Boolean

    /** Whether the runtime can execute this artifact (format/opset/quantization). */
    fun supports(artifact: ModelArtifact): Boolean

    /** Open an executable session, or null when the artifact cannot be loaded. */
    fun openSession(artifact: ModelArtifact): InferenceSession?
}

/** Result of the §27 per-model backend capability test. */
data class BackendCapability(
    val modelId: String,
    val backend: InferenceBackend,
    val available: Boolean,
    val supported: Boolean,
    val latencyMs: Int,
    val detail: String
) {
    val executable: Boolean get() = available && supported
}

/**
 * Process-wide registry of inference backends.
 *
 * Selection prefers the artifact's declared backend, then falls back through the
 * remaining available runtimes in declaration order. The registry is safe to
 * call from any thread and is intentionally mutable so the Android composition
 * root can install ORT/ExecuTorch backends after the JNI libraries load.
 */
object ModelRuntimeRegistry {

    private val runtimes = LinkedHashMap<InferenceBackend, InferenceRuntime>()

    @Synchronized
    fun register(runtime: InferenceRuntime) {
        runtimes[runtime.backend] = runtime
    }

    @Synchronized
    fun unregister(backend: InferenceBackend) {
        runtimes.remove(backend)
    }

    @Synchronized
    fun clear() {
        runtimes.clear()
    }

    /** Backends currently registered, in registration order. */
    @Synchronized
    fun registered(): List<InferenceBackend> = runtimes.keys.toList()

    /** Backends registered *and* usable on this device. */
    @Synchronized
    fun available(): List<InferenceBackend> = runtimes.values.filter { it.isAvailable() }.map { it.backend }

    /**
     * Resolve the runtime that should execute [artifact], preferring the
     * artifact's declared backend and ignoring licensing-blocked artifacts.
     */
    @Synchronized
    fun select(artifact: ModelArtifact): InferenceRuntime? {
        if (!artifact.licenseApproved) return null
        val candidates = buildList {
            runtimes[artifact.preferredBackend]?.let { add(it) }
            addAll(runtimes.values.filter { it.backend != artifact.preferredBackend })
        }
        return candidates.firstOrNull { it.isAvailable() && it.supports(artifact) }
    }

    /** Convenience: open a session for [artifact] through the best runtime. */
    fun openSession(artifact: ModelArtifact): InferenceSession? =
        select(artifact)?.openSession(artifact)
}

/**
 * Backend capability test (§27): "Each model shall undergo a backend capability
 * test." Runs one warm-up pass so reported latency reflects a loaded session
 * rather than first-call JIT/allocator noise.
 */
object RuntimeCapabilityProbe {

    fun probe(
        artifact: ModelArtifact,
        warmInputs: Map<String, FloatArray> = emptyMap(),
        iterations: Int = 1
    ): BackendCapability {
        if (!artifact.licenseApproved) {
            return BackendCapability(
                modelId = artifact.modelId,
                backend = artifact.preferredBackend,
                available = false,
                supported = false,
                latencyMs = 0,
                detail = "license not approved: ${artifact.licenseId}"
            )
        }
        if (!artifact.isDeployable()) {
            return BackendCapability(
                modelId = artifact.modelId,
                backend = artifact.preferredBackend,
                available = false,
                supported = false,
                latencyMs = 0,
                detail = "artifact not deployed"
            )
        }

        val runtime = ModelRuntimeRegistry.select(artifact)
            ?: return BackendCapability(
                modelId = artifact.modelId,
                backend = artifact.preferredBackend,
                available = ModelRuntimeRegistry.available().isNotEmpty(),
                supported = false,
                latencyMs = 0,
                detail = "no registered runtime supports ${artifact.preferredBackend}"
            )

        val session = runtime.openSession(artifact)
            ?: return BackendCapability(
                modelId = artifact.modelId,
                backend = runtime.backend,
                available = true,
                supported = false,
                latencyMs = 0,
                detail = "session open failed"
            )

        return try {
            var totalNanos = 0L
            var ran = 0
            repeat(iterations.coerceAtLeast(1)) {
                val start = System.nanoTime()
                val ok = runCatching { session.run(warmInputs) }.isSuccess
                totalNanos += System.nanoTime() - start
                if (ok) ran++
            }
            BackendCapability(
                modelId = artifact.modelId,
                backend = runtime.backend,
                available = true,
                supported = ran > 0,
                latencyMs = if (ran > 0) (totalNanos / (ran * 1_000_000L)).toInt() else 0,
                detail = if (ran > 0) "warm pass ok ($ran/$iterations)" else "forward pass failed"
            )
        } finally {
            runCatching { session.close() }
        }
    }
}
