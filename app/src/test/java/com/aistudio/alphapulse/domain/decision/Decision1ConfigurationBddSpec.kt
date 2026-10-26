package com.aistudio.alphapulse.domain.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of Decision-1 configuration, specified as Given/When/Then.
 *
 * Decision-1 has no weights to download, so every failure is a settings failure.
 * These tests make each one a named, printable error instead of a silent
 * deterministic fallback that nobody can debug.
 *
 * Spec Ref: §29 Quantitative Decision Router, §42 Security Requirements, §43 API Key Architecture
 */
class Decision1ConfigurationBddSpec {

    private fun completeSettings() = Decision1Settings(
        providerId = "openrouter",
        model = "microsoft/decision-1",
        apiKey = "test-key-not-real"
    )

    // ------------------------------------------------------------- happy path

    @Test
    fun `given a provider, model and key when resolved then the configuration is ready with a normalised base url`() {
        val outcome = Decision1Configuration.resolve(completeSettings())

        assertTrue(outcome is Decision1ConfigOutcome.Ready)
        outcome as Decision1ConfigOutcome.Ready
        assertEquals(Decision1Provider.OPENROUTER, outcome.provider)
        assertEquals("https://openrouter.ai/api", outcome.baseUrl)
        assertEquals("microsoft/decision-1", outcome.model)
        assertEquals("test-key-not-real", outcome.apiKey)
        assertTrue(
            "The safe description must not leak the key: ${outcome.safeDescription}",
            !outcome.safeDescription.contains("test-key-not-real")
        )
    }

    @Test
    fun `given azure with a resource url when resolved then the url is trimmed of its trailing slash`() {
        val outcome = Decision1Configuration.resolve(
            Decision1Settings(
                providerId = "azure",
                baseUrl = "https://my-resource.openai.azure.com/",
                model = "decision-1-deployment",
                apiKey = "k"
            )
        )

        outcome as Decision1ConfigOutcome.Ready
        assertEquals(Decision1Provider.AZURE_FOUNDRY, outcome.provider)
        assertEquals(
            "Callers append /v1/... so a double slash must be impossible",
            "https://my-resource.openai.azure.com", outcome.baseUrl
        )
    }

    @Test
    fun `given no provider chosen when resolved then it defaults to openrouter rather than failing`() {
        val outcome = Decision1Configuration.resolve(
            Decision1Settings(model = "m", apiKey = "k")
        )

        outcome as Decision1ConfigOutcome.Ready
        assertEquals(Decision1Provider.OPENROUTER, outcome.provider)
    }

    // -------------------------------------------------------- typed failures

    @Test
    fun `given an unrecognised provider when resolved then the error names it and lists the valid options`() {
        val outcome = Decision1Configuration.resolve(
            Decision1Settings(providerId = "bedrock", model = "m", apiKey = "k")
        )

        outcome as Decision1ConfigOutcome.Unconfigured
        assertTrue(outcome.error is Decision1ConfigError.UnknownProvider)
        val message = outcome.userMessage
        assertTrue(message, message.contains("bedrock"))
        assertTrue(message, message.contains("Azure AI Foundry"))
        assertTrue(message, message.contains("OpenRouter"))
    }

    @Test
    fun `given azure without an endpoint when resolved then the error says an endpoint is required`() {
        val outcome = Decision1Configuration.resolve(
            Decision1Settings(providerId = "azure", model = "m", apiKey = "k")
        )

        outcome as Decision1ConfigOutcome.Unconfigured
        assertTrue(outcome.error is Decision1ConfigError.MissingEndpoint)
        assertTrue(outcome.userMessage, outcome.userMessage.contains("endpoint URL"))
    }

    @Test
    fun `given no api key when resolved then the error sends the user to settings and mentions the keystore`() {
        val outcome = Decision1Configuration.resolve(
            Decision1Settings(providerId = "openrouter", model = "m", apiKey = "   ")
        )

        outcome as Decision1ConfigOutcome.Unconfigured
        assertTrue(outcome.error is Decision1ConfigError.MissingApiKey)
        assertTrue(outcome.userMessage, outcome.userMessage.contains("Android Keystore"))
    }

    @Test
    fun `given no model or deployment when resolved then the error asks for one`() {
        val outcome = Decision1Configuration.resolve(
            Decision1Settings(providerId = "openrouter", apiKey = "k")
        )

        outcome as Decision1ConfigOutcome.Unconfigured
        assertTrue(outcome.error is Decision1ConfigError.MissingModel)
        assertTrue(outcome.userMessage, outcome.userMessage.contains("model or deployment name"))
    }

    // ------------------------------------------------- router integration

    @Test
    fun `given an unconfigured router when routed then the client is never called and the typed error is exposed`() {
        val spy = CountingClient()
        val router = Decision1ApiRouter(
            client = spy,
            configuration = Decision1ConfigOutcome.Unconfigured(
                Decision1ConfigError.MissingApiKey(Decision1Provider.OPENROUTER)
            )
        )

        val state = router.router(inputs())

        assertEquals("No request may be attempted without a key", 0, spy.calls)
        assertTrue("The router must still answer", state in DecisionState.entries)
        assertTrue(
            "The reason must be the actionable message: ${router.lastReason}",
            router.lastReason.contains("no API key")
        )
        assertTrue(router.configurationError is Decision1ConfigError.MissingApiKey)
    }

    @Test
    fun `given a configured router when routed then the call is attempted and no configuration error is reported`() {
        val spy = CountingClient(reply = Decision1Reply("LONG", 0.8, "momentum"))
        val router = Decision1ApiRouter(
            client = spy,
            configuration = Decision1Configuration.resolve(completeSettings())
        )

        val state = router.router(inputs())

        assertEquals(DecisionState.LONG, state)
        assertEquals("A configured router must attempt the call", 1, spy.calls)
        assertNull("Nothing is misconfigured", router.configurationError)
    }

    private fun inputs() = DecisionInputs(
        triFactorForecast = 0.4,
        tsfmForecast = 0.3,
        lobConfidence = 0.8,
        liquidityConfidence = 0.7,
        regimeConfidence = 0.6,
        modelDisagreement = 0.2,
        dataFreshness = 0.9
    )
}

/** Counts calls so a test can prove the network was never touched. */
private class CountingClient(
    private val reply: Decision1Reply? = Decision1Reply("LONG", 0.8, "test")
) : Decision1Client {

    var calls: Int = 0
        private set

    override suspend fun request(inputs: DecisionInputs): Decision1Reply? {
        calls += 1
        return reply
    }

    override fun describe(): String = "counting client"
}
