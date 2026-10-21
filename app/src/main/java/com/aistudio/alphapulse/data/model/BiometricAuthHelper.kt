package com.aistudio.alphapulse.data.model

import android.content.Context
import androidx.biometric.BiometricPrompt
import java.util.concurrent.Executor

/** BiometricAuthResult - The result of a biometric authentication attempt. */
data class BiometricAuthResult(
    val success: Boolean,
    val authenticatedName: String = "",
    val errorString: String? = null
)

/** BiometricAuthHelper - Helps the UI layer start the biometric prompt and
 * react to the result. The helper keeps a single BiometricPrompt instance
 * and exposes a simple callback-based API.
 */
object BiometricAuthHelper {

    private var biometricPrompt: BiometricPrompt? = null
    private var context: Context? = null
    private var currentCallback: ((BiometricAuthResult) -> Unit)? = null

    /** Initialise the helper with a context (must be a FragmentActivity for BiometricPrompt). */
    fun init(appContext: Context) {
        context = appContext
        if (appContext is androidx.fragment.app.FragmentActivity) {
            val executor = Executor { it.run() }
            biometricPrompt = BiometricPrompt(appContext, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val cb = currentCallback
                    currentCallback = null
                    cb?.invoke(BiometricAuthResult(success = true, authenticatedName = ""))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cb = currentCallback
                    currentCallback = null
                    cb?.invoke(BiometricAuthResult(success = false, errorString = errString.toString()))
                }

                override fun onAuthenticationFailed() {
                    val cb = currentCallback
                    currentCallback = null
                    cb?.invoke(BiometricAuthResult(success = false, errorString = "Authentication failed"))
                }
            })
        }
    }

    /** Request biometric authentication.
     * @param executor Executor on which the result callback runs.
     * @param cancelable true if the prompt should be cancelable via system back button.
     * @param callback called with BiometricAuthResult on completion.
     */
    fun authenticate(
        executor: Executor,
        cancelable: Boolean,
        callback: (BiometricAuthResult) -> Unit
    ) {
        currentCallback = callback
        biometricPrompt?.let {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authenticate to AlphaPulse")
                .setSubtitle("Verify your identity to access trading features")
                .setNegativeButtonText("Cancel")
                .build()
            it.authenticate(promptInfo)
        } ?: run {
            callback(BiometricAuthResult(success = false, errorString = "BiometricPrompt not initialised"))
        }
    }
}
