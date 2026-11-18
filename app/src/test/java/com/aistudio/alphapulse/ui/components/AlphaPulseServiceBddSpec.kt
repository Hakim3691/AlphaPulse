package com.aistudio.alphapulse.ui.components

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager
import com.aistudio.alphapulse.service.AlphaPulseService

/**
 * BDD spec for the persistent background service behaviour.
 *
 * Issue: each time the app goes into the background or the device locks, whatever was
 * happening started all over again. The pipeline must keep running until the user closes
 * the app: the service holds a partial wake lock that no background/lock transition
 * releases, and it asks the system to restart it (`START_STICKY`) rather than drop it.
 *
 * The assertions read the real wake lock the service acquired, so a regression that
 * releases it on a start-intent (a stand-in for backgrounding) fails here.
 *
 * Spec Ref: §24 Persistent Background Engine, §39 User Interface
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class AlphaPulseServiceBddSpec {

    @Test
    fun `given service running when app goes to background then the wake lock and telemetry continue`() {
        AlphaPulseService.updateTelemetry(
            symbol = "BTCUSDT",
            spotPrice = 100.0,
            secondsToTarget = 60,
            regime = "Live"
        )

        val controller = Robolectric.buildService(AlphaPulseService::class.java)
        val service = controller.create().get()

        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("the service must acquire a wake lock so background work is not suspended", wakeLock)
        assertTrue("the wake lock must be held while the service runs", wakeLock.isHeld)
        assertFalse(
            "the lock must be non-reference-counted so a background transition cannot drop the last reference",
            Shadow.extract<ShadowPowerManager.ShadowWakeLock>(wakeLock).isReferenceCounted
        )
        assertTrue("telemetry must be live while the service runs", AlphaPulseService.serviceTelemetry.value.isRunning)

        // Returning START_STICKY is what keeps the pipeline alive instead of letting the
        // system discard the service when the task is backgrounded.
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue("backgrounding must not release the wake lock", wakeLock.isHeld)
        assertTrue("backgrounding must not stop telemetry", AlphaPulseService.serviceTelemetry.value.isRunning)

        controller.destroy()
        assertFalse("only an explicit shutdown releases the lock", wakeLock.isHeld)
        assertFalse("an explicit shutdown stops telemetry", AlphaPulseService.serviceTelemetry.value.isRunning)
    }

    @Test
    fun `given service running when the screen locks then forecast work is not torn down`() {
        val controller = Robolectric.buildService(AlphaPulseService::class.java)
        val service = controller.create().get()

        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wakeLock)
        assertTrue(wakeLock.isHeld)

        // A lock transition re-delivers the start intent; it must not tear the pipeline down.
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue("a lock transition must not release the wake lock", wakeLock.isHeld)

        controller.destroy()
        assertFalse(wakeLock.isHeld)
    }
}
