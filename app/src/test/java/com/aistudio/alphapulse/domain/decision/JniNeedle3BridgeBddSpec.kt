package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real JNI bridge's behaviour when the native library is absent.
 *
 * This runs on the JVM, where `libalphapulse_needle.so` cannot be loaded, which is
 * exactly the situation the bridge has to survive: an x86_64 emulator, or any host
 * without the arm engine. The requirement from §30 is that this is a *reported
 * state*, never a crash - `System.loadLibrary` throws `UnsatisfiedLinkError`, and an
 * unhandled one would take the app down the moment the model was mentioned.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §59 AI Narrator Requirements
 */
class JniNeedle3BridgeBddSpec {

    @Test
    fun `given a process without the android native library when the bridge is built then it reports the engine as absent`() {
        val bridge = JniNeedle3Bridge()

        assertFalse("A missing .so must be reported, not thrown", bridge.engineLinked)
        assertEquals("not loaded", bridge.abiDescription)
    }

    @Test
    fun `given no native engine when the bridge is called then every call returns a refusal instead of throwing`() {
        val bridge = JniNeedle3Bridge()

        assertEquals(-1, bridge.loadWeights("/data/user/0/app/files/models/needle3/needle3.cact"))
        assertEquals(-1, bridge.beginSession("system", "[]"))
        assertNull(bridge.complete("what is the ensemble testing?", 16))
        bridge.reset()
        assertTrue("The refusal must explain itself", bridge.lastError().isNotBlank())
    }

    @Test
    fun `given the jni declarations when inspected then their names match the c entry points the bridge exports`() {
        // A typo here would surface only at runtime as UnsatisfiedLinkError, so the
        // names are pinned: Cactus's own function names for the four it implements,
        // plus the two build facts and the accessor for the reason.
        val declared = Needle3Jni::class.java.declaredMethods.map { it.name }.toSet()

        listOf(
            "abi",
            "engineLinked",
            "loadWeights",
            "begin",
            "complete",
            "lastError",
            "reset"
        ).forEach { name ->
            assertTrue("Needle3Jni must declare $name (JNI resolves it by name)", name in declared)
        }
        // JaCoCo injects a synthetic `$jacocoInit` into instrumented classes, so the
        // compiler-generated members are excluded before the rule is applied.
        val notNative = Needle3Jni::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .filterNot { java.lang.reflect.Modifier.isNative(it.modifiers) }
            .map { it.name }
        assertTrue(
            "Every engine call must be native, or the C symbols would never be used. " +
                "Not native: $notNative",
            notNative.isEmpty()
        )
        // Kotlin mangles the names of internal members, which would silently break
        // JNI resolution, so the holder must stay public.
        assertTrue(
            "Needle3Jni must be public so its native names are not mangled",
            java.lang.reflect.Modifier.isPublic(Needle3Jni::class.java.modifiers)
        )
    }
}
