package com.aistudio.alphapulse

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlphaPulseInstrumentedTest {
    @Test
    fun packageNameMatches() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Source namespace was normalized in Phase 0; the process package
        // (applicationId suffix aside) resolves against the namespace.
        assertEquals("com.aistudio.alphapulse", appContext.packageName)
    }
}
