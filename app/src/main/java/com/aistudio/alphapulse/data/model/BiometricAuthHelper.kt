package com.aistudio.alphapulse.data.model

import androidx.biometric.BiometricPrompt
import android.content.Context
import androidx.annotation.WindowInspectionDuration
import kotlin.coroutines.experimental.CoroutineContext
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

    /** Initialise the helper with the application context. */
    fun init(appContext: Context) {
        context = appContext
        val executor = Executor { it.run() } // main thread executor
        biometricPrompt = BiometricPrompt(context) { result ->
            // Result is delivered via the callback; here we just log.
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
        biometricPrompt?.let {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authenticate to AlphaPulse")
                .setSubtitle("Verify your identity to access trading features")
                .setNegativeButton("Cancel", executor) { _, _ ->
                    callback(BiometricAuthResult(success = false, errorString = "User cancelled"))
                }
                .build()
            it.authenticate(promptInfo, executor) { result ->
                if (result.result) {
                    callback(BiometricAuthResult(
                        success = true,
                        authenticatedName = result.name
                    ))
                } else {
                    callback(BiometricAuthResult(
                        success = false,
                        errorString = result.errorString
                    ))
                }
            }
        } else {
            callback(BiometricAuthResult(success = false, errorString = "BiometricPrompt not initialised"))
        }
    }
}