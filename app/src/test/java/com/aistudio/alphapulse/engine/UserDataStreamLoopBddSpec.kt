package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.remote.BinanceSignedClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The live user-data stream loop itself, driven under virtual time: reconnect with
 * backoff, a refused keep-alive forcing a fresh key, updates that still arrive after a
 * gap, and teardown that releases the key without restarting.
 *
 * The loop is exercised through its injected stream factory and reconcile callbacks, so
 * no socket, thread or clock is real.
 *
 * Spec Ref: §25 Monitoring
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UserDataStreamLoopBddSpec {

    private val creds = "key".toCharArray() to "secret".toCharArray()

    /** Listen-key surface of the venue, plus the answers the loop must react to. */
    private class LoopPort(var keepAliveOk: Boolean = true) : SignedOrderPort {
        var keysCreated = 0
        var closed = 0
        var keepAlives = 0

        override suspend fun createListenKey(apiKey: CharArray): String {
            keysCreated++
            return "key-$keysCreated"
        }

        override suspend fun keepAliveListenKey(apiKey: CharArray): Boolean {
            keepAlives++
            return keepAliveOk
        }

        override suspend fun closeListenKey(apiKey: CharArray): Boolean {
            closed++
            return true
        }

        override suspend fun placeOrder(
            symbol: String, side: String, orderType: String, qty: Double, price: Double?,
            stopPrice: Double?, reduceOnly: Boolean, clientOrderId: String,
            apiKey: CharArray, secret: CharArray
        ) = BinanceSignedClient.OrderResult.failure("not used here")

        override suspend fun cancelOrder(
            symbol: String, clientOrderId: String, apiKey: CharArray, secret: CharArray
        ) = BinanceSignedClient.OrderResult.failure("not used here")

        override suspend fun cancelAllOpenOrders(symbol: String, apiKey: CharArray, secret: CharArray) = false

        override suspend fun fetchOpenOrders(
            symbol: String, apiKey: CharArray, secret: CharArray
        ): List<BinanceSignedClient.OpenOrder> = emptyList()
    }

    private fun loop(
        port: LoopPort,
        stream: (String) -> Flow<String>,
        reconcileSymbol: suspend (String) -> Unit = {},
        reconcileAll: suspend () -> Unit = {},
        refreshMs: Long = 1_000L,
        minBackoffMs: Long = 1_000L
    ) = UserDataStreamLoop(
        orders = LiveOrderCoordinator(port),
        stream = stream,
        shouldRun = { true },
        credentials = { creds },
        reconcileSymbol = reconcileSymbol,
        reconcileAll = reconcileAll,
        note = { _, _ -> },
        minBackoffMs = minBackoffMs,
        refreshMs = refreshMs,
        nowMs = { 0L }
    )

    @Test
    fun `given a dropped stream when it reconnects then the backoff is honoured and REST reconciles first`() =
        runTest {
            // Given: every socket drops straight away.
            val port = LoopPort()
            val opens = mutableListOf<String>()
            var restReconciles = 0
            val loop = loop(
                port,
                stream = { key ->
                    opens += key
                    flow { throw IOException("socket dropped") }
                },
                reconcileAll = { restReconciles++ }
            )

            // When: the loop runs.
            val job = launch { loop.run() }
            runCurrent()

            // Then: the first open happens at once, and nothing reopens inside the backoff.
            assertEquals(1, opens.size)
            advanceTimeBy(500)
            runCurrent()
            assertEquals("the loop must wait out the backoff before reopening", 1, opens.size)

            // And: once it elapses the stream reopens, with the missed gap reconciled.
            advanceTimeBy(700)
            runCurrent()
            assertEquals(2, opens.size)
            assertTrue("a gap must be reconciled from REST", restReconciles >= 2)
            job.cancelAndJoin()
        }

    @Test
    fun `given a refused keep-alive when the stream is open then a fresh key is requested instead of reusing it`() =
        runTest {
            // Given: the venue refuses to extend the key.
            val port = LoopPort(keepAliveOk = false)
            val opens = mutableListOf<String>()
            val loop = loop(
                port,
                stream = { key ->
                    opens += key
                    flow { awaitCancellation() }
                },
                refreshMs = 1_000L,
                minBackoffMs = 10L
            )

            // When:
            val job = launch { loop.run() }
            runCurrent()
            assertEquals(1, port.keysCreated)
            advanceTimeBy(1_050)
            runCurrent()

            // Then: the dead key is abandoned, a new one is requested and the socket is
            // reopened on it — a refused refresh never leaves the old key in use.
            assertTrue("the refresh must actually run", port.keepAlives >= 1)
            assertTrue("a fresh key must be requested: ${port.keysCreated}", port.keysCreated >= 2)
            assertTrue("the socket must reopen: ${opens.size}", opens.size >= 2)
            assertTrue("the reopened socket must not reuse the refused key", opens[0] != opens[1])
            job.cancelAndJoin()
        }

    @Test
    fun `given a gap when the stream reopens then an order update still reaches reconcile`() =
        runTest {
            // Given: the first socket drops, the second delivers a filled take-profit.
            val port = LoopPort()
            val opens = mutableListOf<String>()
            val reconciled = mutableListOf<String>()
            val frame =
                """{"e":"ORDER_TRADE_UPDATE","o":{"i":7,"s":"BTCUSDT","S":"SELL","o":"TAKE_PROFIT_MARKET","c":"BTCUSDT_LONG_1_tp","X":"FILLED","q":"10","z":"10"}}"""
            val loop = loop(
                port,
                stream = { key ->
                    opens += key
                    if (opens.size == 1) {
                        flow { throw IOException("socket dropped") }
                    } else {
                        flow {
                            emit(frame)
                            awaitCancellation()
                        }
                    }
                },
                reconcileSymbol = { reconciled += it }
            )

            // When:
            val job = launch { loop.run() }
            runCurrent()
            advanceTimeBy(1_100)
            runCurrent()

            // Then: the update that arrived after the gap was reconciled for its symbol.
            assertEquals(listOf("BTCUSDT"), reconciled)
            job.cancelAndJoin()
        }

    @Test
    fun `given an open stream when it is torn down then the key is released and the loop does not reopen`() =
        runTest {
            // Given: a healthy, idle stream.
            val port = LoopPort()
            val opens = mutableListOf<String>()
            val loop = loop(port, stream = { key ->
                opens += key
                flow { awaitCancellation() }
            })
            val job = launch { loop.run() }
            runCurrent()
            assertEquals(1, opens.size)

            // When: the owner stops it and releases the key, as the ViewModel does.
            job.cancelAndJoin()
            loop.releaseKey()

            // Then: the venue key is released once, and nothing resumes on its own.
            assertEquals(1, port.closed)
            delay(60_000)
            runCurrent()
            assertEquals("a torn-down loop must not reopen", 1, opens.size)
        }
}
