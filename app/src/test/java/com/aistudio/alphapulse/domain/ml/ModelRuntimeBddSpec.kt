package com.aistudio.alphapulse.domain.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Behaviour of the on-device neural inference runtime, specified as Given/When/Then.
 *
 * Spec Ref: §3 (Zero-Cloud Mobile Architecture, Hardware Acceleration),
 * §13 (Core Market-Structure Objects), §24-27 (Model Management, Hardware)
 *
 * Covers:
 * - ModelRuntimeRegistry backend registration and selection
 * - InferenceBackend enum (CPU/GPU/NPU/Automatic)
 * - RuntimeCapabilityProbe per-model backend capability test (§27)
 * - ModelArtifact deployability and provenance (§55 versioning)
 */
class ModelRuntimeBddSpec {

    // Fake runtime for testing without ONNX/ExecuTorch native libs
    private class FakeRuntime(private val backendValue: InferenceBackend, private val available: Boolean) :
        InferenceRuntime {
        override val backend: InferenceBackend get() = backendValue
        override fun isAvailable(): Boolean = available
        override fun supports(artifact: ModelArtifact): Boolean = true
        override fun openSession(artifact: ModelArtifact): InferenceSession? {
            return if (available && artifact.licenseApproved && artifact.file != null) {
                object : InferenceSession {
                    override val backend: InferenceBackend get() = backendValue
                    override val artifact: ModelArtifact get() = artifact
                    override fun run(inputs: Map<String, FloatArray>): Map<String, FloatArray> {
                        return inputs.mapValues { (_, v) -> v.copyOf() }
                    }
                    override fun close() {}
                }
            } else null
        }
    }

    private fun makeArtifact(
        modelId: String = "test-model",
        version: String = "1.0",
        sizeBytes: Long = 1024,
        licenseApproved: Boolean = true,
        file: java.io.File? = null,
        preferredBackend: InferenceBackend = InferenceBackend.ONNX_RUNTIME_MOBILE,
        quantization: String = "q4"
    ): ModelArtifact {
        return ModelArtifact(
            modelId = modelId,
            version = version,
            file = file,
            quantization = quantization,
            preferredBackend = preferredBackend,
            parameterCount = 1000,
            sizeBytes = sizeBytes,
            licenseId = "test-license",
            licenseApproved = licenseApproved
        )
    }

    @Test
    fun `given no runtimes registered when selecting then no runtime is returned`() {
        ModelRuntimeRegistry.clear()
        val artifact = makeArtifact()

        val selected = ModelRuntimeRegistry.select(artifact)

        assertEquals(null, selected)
    }

    @Test
    fun `given a runtime registered when selecting an approved artifact then the runtime is returned`() {
        ModelRuntimeRegistry.clear()
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.ONNX_RUNTIME_MOBILE, available = true))
        val artifact = makeArtifact(licenseApproved = true, file = java.io.File("/tmp/test.bin"))

        val selected = ModelRuntimeRegistry.select(artifact)

        assertTrue("Should select a runtime for approved artifact", selected != null)
        assertEquals(InferenceBackend.ONNX_RUNTIME_MOBILE, selected!!.backend)
    }

    @Test
    fun `given a runtime registered when selecting a license-blocked artifact then no runtime is returned`() {
        ModelRuntimeRegistry.clear()
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.ONNX_RUNTIME_MOBILE, available = true))
        val artifact = makeArtifact(licenseApproved = false)

        val selected = ModelRuntimeRegistry.select(artifact)

        assertEquals(null, selected)
    }

    @Test
    fun `given multiple runtimes registered when selecting then the preferred backend is chosen first`() {
        ModelRuntimeRegistry.clear()
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.DETERMINISTIC, available = true))
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.ONNX_RUNTIME_MOBILE, available = true))
        val artifact = makeArtifact(
            preferredBackend = InferenceBackend.ONNX_RUNTIME_MOBILE,
            file = java.io.File("/tmp/test.bin")
        )

        val selected = ModelRuntimeRegistry.select(artifact)

        assertEquals(InferenceBackend.ONNX_RUNTIME_MOBILE, selected!!.backend)
    }

    @Test
    fun `given only deterministic runtime when selecting then deterministic fallback is used`() {
        ModelRuntimeRegistry.clear()
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.DETERMINISTIC, available = true))
        val artifact = makeArtifact(
            preferredBackend = InferenceBackend.ONNX_RUNTIME_MOBILE,
            file = java.io.File("/tmp/test.bin")
        )

        val selected = ModelRuntimeRegistry.select(artifact)

        // Deterministic is available but not the preferred backend; it should still be selected
        // as it's the only available runtime
        assertEquals(InferenceBackend.DETERMINISTIC, selected!!.backend)
    }

    @Test
    fun `given a deployed artifact when probed then capability reports executable`() {
        ModelRuntimeRegistry.clear()
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.ONNX_RUNTIME_MOBILE, available = true))
        val artifact = makeArtifact(
            file = java.io.File("/tmp/test.bin"),
            licenseApproved = true
        )
        // Create the file so isDeployable() returns true
        artifact.file?.writeText("fake model")

        val capability = RuntimeCapabilityProbe.probe(artifact)

        assertTrue("Should be available", capability.available)
        assertTrue("Should be supported", capability.supported)
        assertTrue("Should be executable", capability.executable)
    }

    @Test
    fun `given a license-blocked artifact when probed then capability reports not executable`() {
        ModelRuntimeRegistry.clear()
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.ONNX_RUNTIME_MOBILE, available = true))
        val artifact = makeArtifact(licenseApproved = false)

        val capability = RuntimeCapabilityProbe.probe(artifact)

        assertFalse("Should not be available", capability.available)
        assertFalse("Should not be supported", capability.supported)
        assertFalse("Should not be executable", capability.executable)
        assertTrue("Detail should mention license", capability.detail.contains("license not approved"))
    }

    @Test
    fun `given no registered runtime when probed then capability reports no supporting runtime`() {
        ModelRuntimeRegistry.clear()
        val artifact = makeArtifact(file = java.io.File("/tmp/test.bin"), licenseApproved = true)
        artifact.file?.writeText("fake model")

        val capability = RuntimeCapabilityProbe.probe(artifact)

        assertFalse("Should not be available", capability.available)
        assertFalse("Should not be supported", capability.supported)
        assertFalse("Should not be executable", capability.executable)
        assertTrue("Detail should mention no supporting runtime", capability.detail.contains("no registered runtime supports"))
    }

    @Test
    fun `given InferenceBackend values then each has a display name and accelerator description`() {
        assertTrue(InferenceBackend.ONNX_RUNTIME_MOBILE.displayName.isNotEmpty())
        assertTrue(InferenceBackend.ONNX_RUNTIME_MOBILE.accelerator.isNotEmpty())
        assertTrue(InferenceBackend.EXECUTORCH.displayName.isNotEmpty())
        assertTrue(InferenceBackend.NNAPI.displayName.isNotEmpty())
        assertTrue(InferenceBackend.DETERMINISTIC.displayName.isNotEmpty())
        assertEquals("CPU", InferenceBackend.DETERMINISTIC.accelerator)
    }

    @Test
    fun `given a deployed artifact when provenance is requested then it includes model id version and backend`() {
        val artifact = makeArtifact(
            modelId = "chronos-bolt",
            version = "2.0",
            quantization = "q8",
            preferredBackend = InferenceBackend.ONNX_RUNTIME_MOBILE
        )

        val prov = artifact.provenance()

        assertTrue(prov.contains("chronos-bolt"))
        assertTrue(prov.contains("2.0"))
        assertTrue(prov.contains("q8"))
        assertTrue(prov.contains("ONNX_RUNTIME_MOBILE"))
    }

    @Test
    fun `given a non-deployable artifact when probed then capability reports artifact not deployed`() {
        ModelRuntimeRegistry.clear()
        ModelRuntimeRegistry.register(FakeRuntime(InferenceBackend.ONNX_RUNTIME_MOBILE, available = true))
        val artifact = makeArtifact(file = null, licenseApproved = true)

        val capability = RuntimeCapabilityProbe.probe(artifact)

        assertFalse("Should not be executable", capability.executable)
        assertTrue("Detail should mention not deployed", capability.detail.contains("artifact not deployed"))
    }
}
