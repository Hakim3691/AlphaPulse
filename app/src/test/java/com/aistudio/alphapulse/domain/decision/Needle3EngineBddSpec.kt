package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the Needle 3 run path, specified as Given/When/Then.
 *
 * The engine is real in this build, so these specifications no longer assert "it
 * never runs". They assert the two things that must stay true now that it can run:
 *
 *  1. a successful turn returns the model's own words, never a reconstruction;
 *  2. **every** failure is a typed refusal that explains itself, because a silent
 *     fallback that invented a sentence would break §59.
 *
 * The native library cannot be loaded into a JVM test process, so the run path is
 * driven through [FakeNeedle3Bridge]. That is the point of the seam: the logic is
 * proved here, and `VendoredEngineArtifactBddSpec` proves the binary the seam
 * loads is the byte-exact published engine.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §59 AI Narrator Requirements
 */
class Needle3EngineBddSpec {

    private val request = Needle3Request(
        symbol = "BTCUSDT",
        regime = "Momentum Burst",
        kinematic = 0.00042,
        painPath = 0.00031,
        alpha = 0.00028,
        tsfm = 0.00051,
        lobConfidence = 0.87,
        liquidationBias = "UPPER",
        coneBreaches = 2
    )

    private val checkpoint = "/data/user/0/app/files/models/needle3/needle3.cact"

    /** What the Cactus engine returns for one turn. */
    private val engineAnswer =
        """{"function_calls":[],"reasoning":"Kinematic and alpha agree; a close below the """ +
            """cone low would falsify it.","confidence":0.82}"""

    // ------------------------------------------------------------- happy path

    @Test
    fun `given the engine is linked and a checkpoint is mounted when the status is read then it is ready`() {
        val engine = Needle3Engine(FakeNeedle3Bridge())

        val status = engine.status(checkpoint)

        assertTrue("Expected Ready, got $status", status is Needle3EngineStatus.Ready)
        status as Needle3EngineStatus.Ready
        assertEquals("cactus-needle3/arm64-v8a", status.engineVersion)
        assertTrue(engine.isAvailable(checkpoint))
    }

    @Test
    fun `given a mounted checkpoint when a brief is requested then the model's own reasoning and confidence are returned`() {
        val bridge = FakeNeedle3Bridge(answer = engineAnswer)
        val result = Needle3Engine(bridge).generateBrief(request, checkpoint)

        assertTrue("Expected Generated, got $result", result is Needle3Result.Generated)
        result as Needle3Result.Generated
        assertTrue(
            "The reasoning must be the model's text, not a placeholder",
            result.reasoning.startsWith("Kinematic and alpha agree")
        )
        assertEquals(0.82, result.confidence!!, 0.0001)
        assertEquals(engineAnswer, result.rawJson)
        assertEquals("cactus-needle3/arm64-v8a", result.engineVersion)
    }

    @Test
    fun `given a mounted checkpoint when two briefs are asked for then the checkpoint is mapped once and the session starts once`() {
        val bridge = FakeNeedle3Bridge(answer = engineAnswer)
        val engine = Needle3Engine(bridge)

        engine.generateBrief(request, checkpoint)
        engine.generateBrief(request, checkpoint)

        // One 34 MB mmap and one prefix tokenization per session, not per turn.
        assertEquals(1, bridge.calls.count { it.startsWith("loadWeights(") })
        assertEquals(1, bridge.calls.count { it == "beginSession" })
        assertEquals(2, bridge.calls.count { it.startsWith("complete(") })
        assertEquals(
            "Load must precede the session, which must precede each turn",
            listOf(
                "loadWeights($checkpoint)",
                "beginSession",
                "complete(160)",
                "complete(160)"
            ),
            bridge.calls
        )
    }

    // --------------------------------------------------------- typed refusals

    @Test
    fun `given this abi has no cactus engine when the status is read then it names the engine and the supported abis`() {
        val engine = Needle3Engine(FakeNeedle3Bridge(engineLinked = false, abiDescription = "x86_64"))

        val status = engine.status(checkpoint)

        assertTrue("Expected NotBundled, got $status", status is Needle3EngineStatus.NotBundled)
        status as Needle3EngineStatus.NotBundled
        assertTrue("Must name the archive: ${status.reason}", status.reason.contains("Cactus engine"))
        assertTrue(
            "Must name the ABIs that are supported: ${status.reason}",
            status.reason.contains("arm64-v8a") && status.reason.contains("armeabi-v7a")
        )
        assertTrue("Must tell the user what to do: ${status.remedy}", status.remedy.contains("arm64-v8a APK"))
        assertFalse(engine.isAvailable(checkpoint))
    }

    @Test
    fun `given no checkpoint is mounted when the status is read then it asks for a download instead of failing silently`() {
        val engine = Needle3Engine(FakeNeedle3Bridge())

        val status = engine.status(null)

        assertTrue("Expected WeightsNotMounted, got $status", status is Needle3EngineStatus.WeightsNotMounted)
        status as Needle3EngineStatus.WeightsNotMounted
        assertTrue("Reason must name the file: ${status.reason}", status.reason.contains("needle3.cact"))
        assertTrue("Remedy must point at the dock: ${status.remedy}", status.remedy.contains("model dock"))
        assertTrue("A blank path is the same as no path", engine.status("  ") is Needle3EngineStatus.WeightsNotMounted)
    }

    @Test
    fun `given the engine rejects the checkpoint when a brief is requested then the refusal carries the engine's own words`() {
        val bridge = FakeNeedle3Bridge(loadStatus = -7, answer = engineAnswer).apply {
            failWith("Cactus engine rejected the checkpoint: bad magic")
        }
        val result = Needle3Engine(bridge).generateBrief(request, checkpoint)

        assertTrue("Expected Unavailable, got $result", result is Needle3Result.Unavailable)
        val status = (result as Needle3Result.Unavailable).status
        assertTrue("Expected LoadFailed, got $status", status is Needle3EngineStatus.LoadFailed)
        status as Needle3EngineStatus.LoadFailed
        assertEquals("Cactus engine rejected the checkpoint: bad magic", status.reason)
        assertTrue("Remedy must tell the user to re-download: ${status.remedy}", status.remedy.contains("download"))
        // A rejected checkpoint must not be followed by a turn.
        assertEquals(1, bridge.calls.size)
    }

    @Test
    fun `given the prefix does not fit the context when a brief is requested then the failure names the prompt and schemas`() {
        val bridge = FakeNeedle3Bridge(beginStatus = -3, answer = engineAnswer).apply {
            failWith("prefix is 9000 tokens, context is 8192")
        }
        val result = Needle3Engine(bridge).generateBrief(request, checkpoint)

        val status = (result as Needle3Result.Unavailable).status
        assertTrue("Expected InferenceFailed, got $status", status is Needle3EngineStatus.InferenceFailed)
        status as Needle3EngineStatus.InferenceFailed
        assertEquals("prefix is 9000 tokens, context is 8192", status.reason)
        assertTrue(
            "Remedy must point at the prefix: ${status.remedy}",
            status.remedy.contains("system prompt") || status.remedy.contains("tool schemas")
        )
    }

    @Test
    fun `given the completion fails when a brief is requested then no text is returned and the reason is surfaced`() {
        val bridge = FakeNeedle3Bridge(answer = null).apply { failWith("out of memory") }
        val result = Needle3Engine(bridge).generateBrief(request, checkpoint)

        assertTrue("A failed turn can never generate", result !is Needle3Result.Generated)
        val status = (result as Needle3Result.Unavailable).status
        assertTrue("Expected InferenceFailed, got $status", status is Needle3EngineStatus.InferenceFailed)
        assertEquals("out of memory", (status as Needle3EngineStatus.InferenceFailed).reason)
    }

    @Test
    fun `given the engine answers with something that is not the promised json when a brief is requested then it is refused`() {
        val engine = Needle3Engine(FakeNeedle3Bridge(answer = "Sure! The market looks bullish."))

        val result = engine.generateBrief(request, checkpoint)

        assertTrue("Unstructured prose must never be shown as an attributed brief", result !is Needle3Result.Generated)
        val status = (result as Needle3Result.Unavailable).status
        assertTrue(status is Needle3EngineStatus.InferenceFailed)
        assertTrue(
            "The reason must say why it was refused: ${(status as Needle3EngineStatus.InferenceFailed).reason}",
            status.reason.contains("not the promised JSON")
        )
    }

    @Test
    fun `given the engine answers with an empty reasoning field when a brief is requested then it is refused`() {
        val engine = Needle3Engine(FakeNeedle3Bridge(answer = """{"function_calls":[],"reasoning":""}"""))

        val result = engine.generateBrief(request, checkpoint)

        assertTrue("An empty brief is not a brief", result !is Needle3Result.Generated)
    }

    @Test
    fun `given no engine at all when ten briefs are requested then not one of them produces text`() {
        val engine = Needle3Engine(FakeNeedle3Bridge(engineLinked = false, answer = engineAnswer))

        repeat(10) { attempt ->
            val result = engine.generateBrief(request, checkpoint)
            assertTrue("Attempt $attempt produced text without an engine", result !is Needle3Result.Generated)
        }
    }

    // -------------------------------------------------------------- side effects

    @Test
    fun `given the status is read when the ui asks then nothing is loaded because status has no side effects`() {
        val bridge = FakeNeedle3Bridge()
        val engine = Needle3Engine(bridge)

        engine.status(checkpoint)
        engine.status(checkpoint)

        // Reading the status must not map 34 MB on the calling (UI) thread.
        assertTrue("status() must be pure, but it called ${bridge.calls}", bridge.calls.isEmpty())
    }

    @Test
    fun `given a session was started when release is called then the engine conversation is cleared and restarted on the next turn`() {
        val bridge = FakeNeedle3Bridge(answer = engineAnswer)
        val engine = Needle3Engine(bridge)

        engine.generateBrief(request, checkpoint)
        engine.release()
        engine.generateBrief(request, checkpoint)

        assertEquals(1, bridge.calls.count { it == "reset" })
        assertEquals("Release must force a fresh session", 2, bridge.calls.count { it == "beginSession" })
        assertEquals("The checkpoint must not be remapped", 1, bridge.calls.count { it.startsWith("loadWeights(") })
    }

    @Test
    fun `given an engine with a checkpoint when the status is described then the line names the engine version and the abi`() {
        val line = Needle3Engine(FakeNeedle3Bridge()).describeStatus(checkpoint)

        assertTrue("Must name the version: $line", line.contains("cactus-needle3/arm64-v8a"))
        assertTrue("Must say it will run on device: $line", line.contains("on device"))
    }

    @Test
    fun `given no engine when the status is described then the line says it cannot run rather than staying silent`() {
        val line = Needle3Engine(FakeNeedle3Bridge(engineLinked = false)).describeStatus(checkpoint)

        assertTrue("Must state the blocker: $line", line.contains("cannot run on this build"))
    }

    // ------------------------------------------------------------ prompt shapes

    @Test
    fun `given the shipped constants when inspected then no tools are declared and the persona forbids invented numbers`() {
        // Declaring tools would let the model answer with an action the app cannot
        // run; the persona restates §59 so the model is told the same rule we enforce.
        assertEquals("[]", Needle3Engine.TOOLS_JSON)
        assertTrue(Needle3Engine.SYSTEM_PROMPT.contains("Never invent"))
        assertTrue(Needle3Engine.SYSTEM_PROMPT.contains("Never promise a return"))
        assertTrue("The cap keeps a brief a paragraph", Needle3Engine.DEFAULT_MAX_NEW_TOKENS in 1..512)
    }
}
