package com.aistudio.alphapulse.domain.decision

/**
 * Decision1Configuration - decides whether Microsoft-Decision-1 can be called at all.
 *
 * Microsoft-Decision-1 is closed-weights: there is nothing to download and nothing
 * to run on the phone. It is reached over HTTPS through Azure AI Foundry or
 * OpenRouter (§29). That means "is it configured?" is a real question with three
 * separate answers - unknown provider, no endpoint, no key - and each of them needs
 * a message a user can act on rather than a silent fallback.
 *
 * This object turns the stored settings into either a ready-to-call configuration
 * or a typed error. The API key is supplied by the caller (read from the Android
 * Keystore, §42/§43); it is never stored or logged here.
 *
 * Spec Ref: §29 Quantitative Decision Router, §42 Security Requirements, §43 API Key Architecture
 */

/**
 * The hosted providers that serve a Decision-1 style model.
 *
 * @property displayName name shown in settings
 * @property defaultBaseUrl endpoint root when the provider has a single public one,
 *   or null when the URL is per-resource (Azure gives every deployment its own host)
 * @property apiStyle how the endpoint is addressed, for the settings hint text
 */
enum class Decision1Provider(
    val displayName: String,
    val defaultBaseUrl: String?,
    val apiStyle: String
) {
    /** Microsoft's own hosting; the resource URL is unique per deployment. */
    AZURE_FOUNDRY(
        displayName = "Azure AI Foundry",
        defaultBaseUrl = null,
        apiStyle = "OpenAI-compatible deployment endpoint (one host per resource)"
    ),

    /** A public gateway that serves many models, including decision models. */
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api",
        apiStyle = "OpenAI-compatible /v1/chat/completions"
    );

    companion object {
        /**
         * Parse a stored provider id.
         *
         * @return the provider, or null when the id is not one we know
         */
        fun fromId(id: String?): Decision1Provider? = when (id?.trim()?.lowercase()) {
            "azure", "azure-foundry", "azure_foundry", "azureopenai" -> AZURE_FOUNDRY
            "openrouter", "open-router", "open_router" -> OPENROUTER
            else -> null
        }
    }
}

/**
 * Why Decision-1 cannot be called.
 *
 * Every case carries a sentence that can be shown to a user as-is, because a
 * decision router that silently degrades is impossible to diagnose from the UI.
 *
 * @property userMessage actionable explanation, safe to display
 */
sealed class Decision1ConfigError(val userMessage: String) {

    /** The stored provider id is not one of the supported providers. */
    class UnknownProvider(val providerId: String) : Decision1ConfigError(
        "Decision-1 is not configured: '$providerId' is not a supported provider. " +
            "Choose ${Decision1Provider.AZURE_FOUNDRY.displayName} or " +
            "${Decision1Provider.OPENROUTER.displayName}."
    )

    /** No endpoint, and the provider has no default one. */
    class MissingEndpoint(val provider: Decision1Provider) : Decision1ConfigError(
        "Decision-1 is not configured: ${provider.displayName} needs an endpoint URL " +
            "for your deployment."
    )

    /** No API key was supplied by the secure store. */
    class MissingApiKey(val provider: Decision1Provider) : Decision1ConfigError(
        "Decision-1 is not configured: no API key was found for ${provider.displayName}. " +
            "Add one in settings; it is stored in the Android Keystore."
    )

    /** No deployment/model name, so the request could not be addressed. */
    class MissingModel(val provider: Decision1Provider) : Decision1ConfigError(
        "Decision-1 is not configured: ${provider.displayName} needs a model or " +
            "deployment name."
    )
}

/** Raw settings as they come out of storage. Any field may be blank. */
data class Decision1Settings(
    val providerId: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val apiKey: String? = null
)

/** The result of resolving settings into something callable. */
sealed class Decision1ConfigOutcome {

    /**
     * Everything needed to make a call is present.
     *
     * @property provider which host serves the model
     * @property baseUrl endpoint root without a trailing slash
     * @property model deployment or model name
     * @property apiKey the secret, passed straight to the HTTP client and never stored
     */
    data class Ready(
        val provider: Decision1Provider,
        val baseUrl: String,
        val model: String,
        val apiKey: String
    ) : Decision1ConfigOutcome() {

        /** Short, secret-free description for logs. */
        val safeDescription: String
            get() = "${provider.displayName} $baseUrl model=$model"
    }

    /** Configuration is incomplete; the router must not attempt a call. */
    data class Unconfigured(val error: Decision1ConfigError) : Decision1ConfigOutcome() {

        /** The sentence to show the user. */
        val userMessage: String
            get() = error.userMessage
    }
}

object Decision1Configuration {

    /** Provider used when nothing has been chosen yet. */
    const val DEFAULT_PROVIDER_ID: String = "openrouter"

    /**
     * Resolve stored settings into a callable configuration or a typed error.
     *
     * Order matters, because it decides which message the user sees first: an
     * unknown provider, then a missing endpoint, then a missing key, then a missing
     * model. Each check is only reached when the previous one passed.
     */
    fun resolve(settings: Decision1Settings): Decision1ConfigOutcome {
        val providerId = settings.providerId?.takeIf { it.isNotBlank() } ?: DEFAULT_PROVIDER_ID
        val provider = Decision1Provider.fromId(providerId)
            ?: return Decision1ConfigOutcome.Unconfigured(
                Decision1ConfigError.UnknownProvider(providerId)
            )

        val baseUrl = settings.baseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: provider.defaultBaseUrl
            ?: return Decision1ConfigOutcome.Unconfigured(
                Decision1ConfigError.MissingEndpoint(provider)
            )

        val apiKey = settings.apiKey?.trim()?.takeIf { it.isNotBlank() }
            ?: return Decision1ConfigOutcome.Unconfigured(
                Decision1ConfigError.MissingApiKey(provider)
            )

        val model = settings.model?.trim()?.takeIf { it.isNotBlank() }
            ?: return Decision1ConfigOutcome.Unconfigured(
                Decision1ConfigError.MissingModel(provider)
            )

        return Decision1ConfigOutcome.Ready(
            provider = provider,
            // Normalise so callers can append "/v1/..." without doubling a slash.
            baseUrl = baseUrl.trimEnd('/'),
            model = model,
            apiKey = apiKey
        )
    }
}
