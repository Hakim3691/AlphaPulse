package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.remote.BinanceSignedClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Given a live user-data stream, when it is torn down, then the venue-side listen key
 * is released instead of being left to expire on its own.
 *
 * A key nobody reads still counts as a live stream at the venue, so leaving it behind
 * on a mode change, a sign-out or a teardown leaks a stream for up to an hour.
 *
 * Spec Ref: §25 Monitoring
 */
class ListenKeyTeardownBddSpec {

    /** Records the release and replays a scripted venue answer. */
    private class RecordingPort(
        private val closeResult: Boolean = true,
        private val closeThrows: Boolean = false
    ) : SignedOrderPort {
        var closed = 0

        override suspend fun placeOrder(
            symbol: String,
            side: String,
            orderType: String,
            qty: Double,
            price: Double?,
            stopPrice: Double?,
            reduceOnly: Boolean,
            clientOrderId: String,
            apiKey: CharArray,
            secret: CharArray
        ) = BinanceSignedClient.OrderResult.failure("not used here")

        override suspend fun cancelOrder(
            symbol: String,
            clientOrderId: String,
            apiKey: CharArray,
            secret: CharArray
        ) = BinanceSignedClient.OrderResult.failure("not used here")

        override suspend fun cancelAllOpenOrders(
            symbol: String,
            apiKey: CharArray,
            secret: CharArray
        ) = false

        override suspend fun fetchOpenOrders(
            symbol: String,
            apiKey: CharArray,
            secret: CharArray
        ): List<BinanceSignedClient.OpenOrder> = emptyList()

        override suspend fun createListenKey(apiKey: CharArray): String = "listen-key"

        override suspend fun keepAliveListenKey(apiKey: CharArray): Boolean = true

        override suspend fun closeListenKey(apiKey: CharArray): Boolean {
            closed++
            if (closeThrows) error("venue unreachable")
            return closeResult
        }
    }

    @Test
    fun `given a live stream when the session ends then the listen key is released`() = runBlocking {
        val port = RecordingPort()
        val released = LiveOrderCoordinator(port = port).closeListenKey("key".toCharArray())

        assertTrue("The venue accepted the release", released)
        assertEquals("Exactly one release, not one per reconnect", 1, port.closed)
    }

    @Test
    fun `given the venue refuses the release when the session ends then shutdown still completes`() =
        runBlocking {
            val port = RecordingPort(closeResult = false)
            val released = LiveOrderCoordinator(port = port).closeListenKey("key".toCharArray())

            assertFalse("A refused release is reported, not thrown", released)
            assertEquals(1, port.closed)
        }

    @Test
    fun `given a throwing venue when the key is released then the failure is swallowed`() = runBlocking {
        val port = RecordingPort(closeThrows = true)
        val released = LiveOrderCoordinator(port = port).closeListenKey("key".toCharArray())

        assertFalse("Teardown must not fail because the venue was unreachable", released)
    }
}
