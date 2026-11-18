package com.aistudio.alphapulse.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The throttle that stops the ten-second evaluation cycle from re-downloading data
 * that only changes once a minute or once every few minutes.
 *
 * Spec Ref: §24 Persistent Background Engine, C-30
 */
class FetchThrottleBddSpec {

    @Test
    fun `given a throttle when the same key is requested twice inside the ttl then it fetches once`() = runBlocking {
        var fetches = 0
        val throttle = FetchThrottle<Int>(ttlMs = 60_000L)

        val first = throttle.value("BTCUSDT", 1_000L) { fetches++; 40 }
        val second = throttle.value("BTCUSDT", 30_000L) { fetches++; 40 }

        assertEquals("the cached value must be returned", 40, first)
        assertEquals("the cached value must be reused inside the TTL", 40, second)
        assertEquals("only one network fetch may happen inside the TTL", 1, fetches)
    }

    @Test
    fun `given a throttle when the ttl has passed then the next request fetches again`() = runBlocking {
        var fetches = 0
        val throttle = FetchThrottle<Int>(ttlMs = 60_000L)

        throttle.value("BTCUSDT", 1_000L) { fetches++; 1 }
        throttle.value("BTCUSDT", 61_001L) { fetches++; 2 }

        assertEquals("a stale value must be refetched, not reused", 2, fetches)
    }

    @Test
    fun `given a throttle when another symbol is requested then it does not reuse the first symbol's data`() = runBlocking {
        var fetches = 0
        val throttle = FetchThrottle<String>(ttlMs = 60_000L)

        throttle.value("BTCUSDT", 1_000L) { fetches++; "btc" }
        val other = throttle.value("ETHUSDT", 2_000L) { fetches++; "eth" }

        assertEquals("a different key must never be served another symbol's value", "eth", other)
        assertEquals("a different key must refetch", 2, fetches)
    }

    @Test
    fun `given a throttle when a fetch fails then the failure is not cached`() = runBlocking {
        var fetches = 0
        val throttle = FetchThrottle<Int>(ttlMs = 300_000L)

        val failed = throttle.value("BTCUSDT", 1_000L) { fetches++; null }
        val recovered = throttle.value("BTCUSDT", 2_000L) { fetches++; 7 }

        assertNull("a failed fetch returns null", failed)
        assertEquals("the next cycle must retry instead of freezing the feature", 7, recovered)
        assertEquals(2, fetches)
    }
}
