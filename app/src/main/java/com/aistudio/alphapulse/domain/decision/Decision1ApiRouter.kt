package com.aistudio.alphapulse.domain.decision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Decision1ApiRouter - calls a hosted Decision-1 style model over HTTPS and maps
 * its reply onto our own DecisionState.
 *
 * §29 says Microsoft-Decision-1 must stay a *replaceable interface*, never a hard
 * dependency. So this router:
 *   - asks [Decision1Client] for a verdict (the HTTP details live behind it),
 *   - falls back to [fallback] whenever the network, the timeout, the payload or
 *     the authentication fails, and
 *   - always records why it answered what it answered.
 *
 * The API key never appears here: the client reads it from the Android Keystore
 * (§42/§43), so no secret is ever compiled into the app.
 *
 * Spec Ref: §29 (Quantitative Decision Router), §45 (<100 ms routing target),
 * §44 (failure fallback chain)
 */
class Decision1ApiRouter(
    private val client: Decision1Client,
    private val fallback: DecisionRouter = DeterministicDecisionRouter(),
    private val timeoutMs: Long = 900L
) : DecisionRouter {

    override var lastReason: String = "Decision-1 router: not called yet"
        private set

    override var decisionConfidence: Double = 0.0
        private set

    /**
     * Route the decision, returning a DecisionState.
     *
     * The whole point of this method is that it cannot throw: a dead API must
     * degrade into the deterministic verdict instead of killing the forecast.
     */
    override fun router(inputs: DecisionInputs): DecisionState {
        val raw = try {
            // withTimeoutOrNull returns null instead of throwing on timeout.
            kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    client.request(inputs)
                }
            }
        } catch (e: Exception) {
            null
        }

        // No answer (timeout, offline, malformed reply) -> deterministic fallback.
        if (raw == null) {
            lastReason = "Decision-1 unavailable (${client.describe()}); " +
                "fell back to deterministic router. Previous: ${fallback.lastReason}"
            val state = fallback.router(inputs)
            decisionConfidence = fallback.decisionConfidence
            return state
        }

        // A reply must be one of our six states; anything else is not trusted.
        val state = parseState(raw.verdict)
            ?: run {
                lastReason = "Decision-1 returned an unknown verdict '${raw.verdict}'; " +
                    "using deterministic router instead."
                decisionConfidence = 0.0
                return fallback.router(inputs)
            }

        decisionConfidence = raw.confidence.coerceIn(0.0, 1.0)
        lastReason = "Decision-1: ${raw.verdict} (confidence ${"%.0f".format(raw.confidence * 100)}%) " +
            "- ${raw.reason.ifBlank { "no reason supplied by model" }}"
        return state
    }

    /** Map the model's wording onto our enum; null means "we do not know that word". */
    private fun parseState(verdict: String): DecisionState? = when (verdict.uppercase()
        .replace('-', '_').replace(' ', '_')) {
        "STRONG_LONG", "STRONGLONG" -> DecisionState.STRONG_LONG
        "LONG" -> DecisionState.LONG
        "NEUTRAL" -> DecisionState.NEUTRAL
        "SHORT" -> DecisionState.SHORT
        "STRONG_SHORT", "STRONGSHORT" -> DecisionState.STRONG_SHORT
        "NO_TRADE", "NOTRADE" -> DecisionState.NO_TRADE
        else -> null
    }
}

/** One reply from a hosted decision model. */
data class Decision1Reply(
    /** One of: STRONG LONG, LONG, NEUTRAL, SHORT, STRONG SHORT, NO TRADE. */
    val verdict: String,
    /** Model's own confidence, 0.0..1.0. */
    val confidence: Double,
    /** Why it answered this - stored for the audit trail (§29). */
    val reason: String
)

/**
 * Decision1Client - the seam between the router and the network.
 *
 * Keeping it an interface means the router can be tested without sockets, and
 * swapping Azure for OpenRouter (or a local model) is a one-line change.
 */
interface Decision1Client {
    /** Ask the model for a verdict; null means "no usable answer". */
    suspend fun request(inputs: DecisionInputs): Decision1Reply?

    /** Short description used in the fallback log line. */
    fun describe(): String
}

/**
 * HttpDecision1Client - real HTTPS implementation against a
 * Decision-1-compatible chat-completions endpoint (Azure AI Foundry or OpenRouter).
 *
 * @param baseUrl endpoint root, e.g. "https://<resource>.openai.azure.com"
 * @param model deployed model name
 * @param apiKeyProvider returns the API key at call time (read from Keystore)
 */
class HttpDecision1Client(
    private val baseUrl: String,
    private val model: String,
    private val apiKeyProvider: () -> String?
) : Decision1Client {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    override fun describe(): String = "HTTPS $baseUrl model=$model"

    override suspend fun request(inputs: DecisionInputs): Decision1Reply? =
        withContext(Dispatchers.IO) {
            try {
                val key = apiKeyProvider()
                if (key.isNullOrBlank()) return@withContext null

                // The prompt is pure numbers - the model decides, it is never told
                // what to answer (§29: signal convergence + confidence + regime).
                val payload = JSONObject().apply {
                    put("model", model)
                    put(
                        "messages",
                        org.json.JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", buildPrompt(inputs))
                            }
                        )
                    )
                    put("temperature", 0)
                }

                val request = Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val content = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    parseReply(content)
                }
            } catch (e: Exception) {
                // Network/parse problems must never reach the caller - we return
                // null and the router falls back to the deterministic verdict.
                null
            }
        }

    /** Ask the model for strict JSON so the reply can be parsed without guesswork. */
    private fun buildPrompt(inputs: DecisionInputs): String = buildString {
        appendLine("You are a quantitative trade decision router.")
        appendLine("Given the market inputs as JSON, reply with ONLY this JSON:")
        appendLine("{\"verdict\":\"STRONG LONG|LONG|NEUTRAL|SHORT|STRONG SHORT|NO TRADE\",")
        appendLine(" \"confidence\":0.0-1.0,\"reason\":\"one short sentence\"}")
        appendLine("Inputs:")
        appendLine(
            JSONObject().apply {
                put("triFactorForecast", inputs.triFactorForecast)
                put("tsfmForecast", inputs.tsfmForecast)
                put("lobConfidence", inputs.lobConfidence)
                put("liquidityConfidence", inputs.liquidityConfidence)
                put("regimeConfidence", inputs.regimeConfidence)
                put("modelDisagreement", inputs.modelDisagreement)
                put("dataFreshness", inputs.dataFreshness)
                put("coneBreaches", inputs.coneBreaches)
                put("recentErrors", inputs.recentErrors)
            }.toString()
        )
    }

    /** Parse the model's JSON reply; null when it is not usable. */
    private fun parseReply(content: String): Decision1Reply? {
        return try {
            // Models sometimes wrap JSON in prose or code fences - take the braces.
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) {
                null
            } else {
                val json = JSONObject(content.substring(start, end + 1))
                Decision1Reply(
                    verdict = json.getString("verdict"),
                    confidence = json.optDouble("confidence", 0.0),
                    reason = json.optString("reason", "")
                )
            }
        } catch (e: Exception) {
            // A reply we cannot read is treated exactly like no reply at all.
            null
        }
    }
}
