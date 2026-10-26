package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the Needle 3 run path, specified as Given/When/Then.
 *
 * The rule under test is a refusal: this build has no Cactus native engine, so the
 * model must never produce text. A "best effort" fallback that invented a sentence
 * would violate §59, so each of these tests checks that the failure is explicit and
 * explains itself.
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

    @Test
    fun `given this build has no native cactus layer when the engine state is read then it reports not bundled`() {
        val status = Needle3Engine.status

        assertTrue("Status must be the explicit not-bundled state", status is Needle3EngineStatus.NotBundled)
        status as Needle3EngineStatus.NotBundled
        assertTrue(
            "The reason must name the missing artifact: ${status.reason}",
            status.reason.contains("libneedle.a")
        )
        assertTrue(
            "The remedy must tell the user what to do instead: ${status.remedy}",
            status.remedy.contains("deterministic narrator")
        )
        assertFalse("An unbundled engine is never available", Needle3Engine.isAvailable)
    }

    @Test
    fun `given no engine when a brief is requested then the call fails closed with the documented state`() {
        val result = Needle3Engine.generateBrief(request)

        assertTrue("No engine means no text", result is Needle3Result.Unavailable)
        val unavailable = result as Needle3Result.Unavailable
        assertTrue(
            "The failure must carry the same reason shown to the user",
            unavailable.status.reason.contains("not linked into this build")
        )
    }

    @Test
    fun `given the fail-closed path when inspected then it never returns generated text`() {
        // Ten calls, because a fallback that "sometimes" fabricates is the exact bug
        // this guards against.
        repeat(10) {
            val result = Needle3Engine.generateBrief(request)
            assertTrue("Attempt $it produced text without an engine", result !is Needle3Result.Generated)
        }
    }

    @Test
    fun `given the engine status when described then the line states both the download situation and the blocker`() {
        val line = Needle3Engine.describeStatus()

        assertTrue("Must mention that weights are downloadable: $line", line.contains("downloaded"))
        assertTrue("Must mention the missing engine: $line", line.contains("native engine is not bundled"))
    }

    @Test
    fun `given needle 3 weights are downloaded when the engine is still missing then the catalog keeps it gated as planned`() {
        // The weights are downloadable on a capable device...
        assertTrue(DecisionModelCatalog.isDownloadable("needle3"))

        // ...but the engine parts stay PLANNED, which is what makes the run path fail closed.
        DecisionModelCatalog.NEEDLE3_ENGINE.forEach { enginePart ->
            assertEquals(
                "${enginePart.id} must stay PLANNED until the NDK layer lands",
                ModelAvailability.PLANNED,
                enginePart.availability
            )
        }
    }
}
