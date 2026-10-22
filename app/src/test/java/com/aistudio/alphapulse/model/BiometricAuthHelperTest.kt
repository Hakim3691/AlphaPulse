package com.aistudio.alphapulse.model

import com.aistudio.alphapulse.data.model.BiometricAuthHelper
import com.aistudio.alphapulse.data.model.BiometricAuthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** BiometricAuthHelper unit tests (spec §28 biometric auth). */
class BiometricAuthHelperTest {

    @Test
    fun `authenticate_without_prompt_fails_gracefully`() {
        // No init() has run, so there is no prompt to show: the helper must report
        // a failed attempt instead of throwing or hanging.
        var captured: BiometricAuthResult? = null
        BiometricAuthHelper.authenticate(
            executor = Executor { it.run() },
            cancelable = true
        ) { result -> captured = result }

        val result = captured
        assertNotNull("Callback must always fire", result)
        assertFalse("Auth without a prompt cannot succeed", result!!.success)
        assertNotNull("Failure must carry an explanatory error", result.errorString)
    }

    @Test
    fun `successful_result_carries_name_and_no_error`() {
        val result = BiometricAuthResult(success = true, authenticatedName = "test", errorString = null)
        assertTrue(result.success)
        assertEquals("test", result.authenticatedName)
        assertNull(result.errorString)
    }
}
