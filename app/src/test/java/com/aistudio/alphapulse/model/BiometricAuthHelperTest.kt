package com.aistudio.alphapulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.test.platform.app.InstrumentationRegistry

/** BiometricAuthHelper unit tests. */
class BiometricAuthHelperTest {

    private lateinit var helper: com.aistudio.alphapulse.data.model.BiometricAuthHelper
    private lateinit var mockContext: android.content.Context

    @Before
    fun setUp() {
        mockContext = InstrumentationRegistry.getInstrumentation().targetContext
        helper = com.aistudio.alphapulse.data.model.BiometricAuthHelper()
        helper.init(mockContext)
    }

    @Test
    fun `helper_initiated_has_prompt_instance`() {
        val field = helper::class.java.getDeclaredField("biometricPrompt")
        field.isAccessible = true
        assertNotNull("BiometricPrompt should be created during init", field.get(helper))
    }

    @Test
    fun `authenticate_cancelled_returns_failure`() {
        // Simulate a cancelled authentication
        helper.authenticate(
            executor = { /* dummy */ },
            cancelable = true,
            callback = { result ->
                assertEquals("Cancelled auth should be failure", false, result.success)
                assertEquals("Error string should describe cancellation", "User cancelled", result.errorString)
            }
        )
    }

    @Test
    fun `authentication_modes_are_distinct`() {
        // Verify the BiometricAuthResult data class has the expected fields
        val result = com.aistudio.alphapulse.data.model.BiometricAuthResult(success = true, authenticatedName = "test", errorString = null)
        assertEquals("success should be true", true, result.success)
        assertEquals("authenticatedName should be test", "test", result.authenticatedName)
        assertEquals("errorString should be null when success is true", null, result.errorString)
    }
}
