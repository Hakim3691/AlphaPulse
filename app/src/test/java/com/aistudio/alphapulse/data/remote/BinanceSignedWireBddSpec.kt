package com.aistudio.alphapulse.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Given an order the coordinator wants staged or cancelled, when the signed client is
 * asked to send it, then the request that would reach Binance carries exactly the
 * parameters — and the signature — the venue expects.
 *
 * The wire is recorded instead of dialled, so these scenarios prove the request this
 * app builds. They do not prove a real venue accepted one.
 */
@RunWith(RobolectricTestRunner::class)
class BinanceSignedWireBddSpec {

    private class RecordingWire(private val response: Pair<Int, String?>) : Wire {
        val calls = mutableListOf<Triple<String, String, String>>()
        override fun send(method: String, url: String, apiKey: CharArray): Pair<Int, String?> {
            calls += Triple(method, url, String(apiKey))
            return response
        }
    }

    private val apiKey = "test-api-key".toCharArray()
    private val secret = "test-secret".toCharArray()
    private val now = 1_700_000_000_000L

    @Before
    fun pinTheClock() {
        BinanceSignedClient.clock = { now }
    }

    @After
    fun restoreTheWire() {
        BinanceSignedClient.wire = SocketWire
        BinanceSignedClient.clock = { System.currentTimeMillis() }
    }

    @Test
    fun `given a passive limit entry when placed then the venue receives a signed limit order`() = runBlocking {
        val wire = RecordingWire(200 to """{"orderId":41,"clientOrderId":"CP1-S0","status":"NEW"}""")
        BinanceSignedClient.wire = wire

        val result = BinanceSignedClient.placeOrder(
            symbol = "BTCUSDT",
            side = "BUY",
            orderType = "LIMIT",
            qty = 0.002,
            price = 65_000.5,
            stopPrice = null,
            clientOrderId = "CP1-S0",
            apiKey = apiKey,
            secret = secret
        )

        val (method, url, header) = wire.calls.single()
        assertEquals("POST", method)
        assertEquals("test-api-key", header)
        assertEquals("/fapi/v1/order", pathOf(url))
        val query = queryOf(url)
        val params = paramsOf(query)
        assertEquals("BTCUSDT", params["symbol"])
        assertEquals("BUY", params["side"])
        assertEquals("LIMIT", params["type"])
        assertEquals("0.002", params["quantity"])
        assertEquals("65000.5", params["price"])
        assertEquals("GTC", params["timeInForce"])
        assertEquals("CP1-S0", params["newClientOrderId"])
        assertEquals("$now", params["timestamp"])
        assertEquals("5000", params["recvWindow"])
        assertNull("a limit entry is not reduce-only", params["reduceOnly"])
        assertEquals(expectedSignature(query), params["signature"])
        assertTrue(result.ok)
        assertEquals(41L, result.orderId ?: -1L)
    }

    @Test
    fun `given a stop-limit entry when placed then the request carries the limit price and time in force`() = runBlocking {
        val wire = RecordingWire(200 to """{"orderId":42,"status":"NEW"}""")
        BinanceSignedClient.wire = wire

        BinanceSignedClient.placeOrder(
            symbol = "ETHUSDT",
            side = "SELL",
            orderType = "STOP",
            qty = 1.5,
            price = 3_100.0,
            stopPrice = 3_090.0,
            clientOrderId = "CP2-S0",
            apiKey = apiKey,
            secret = secret
        )

        val params = paramsOf(queryOf(wire.calls.single().second))
        assertEquals("STOP", params["type"])
        assertEquals("3090.0", params["stopPrice"])
        // A STOP on the venue is a stop-limit: without the limit price and a
        // time-in-force the exchange rejects it, so a breakout entry never rests.
        assertEquals("3100.0", params["price"])
        assertEquals("GTC", params["timeInForce"])
        assertEquals("1.5", params["quantity"])
    }

    @Test
    fun `given a take-profit when placed then it is reduce-only and priced by its stop`() = runBlocking {
        val wire = RecordingWire(200 to """{"orderId":43,"status":"NEW"}""")
        BinanceSignedClient.wire = wire

        BinanceSignedClient.placeOrder(
            symbol = "BTCUSDT",
            side = "SELL",
            orderType = "TAKE_PROFIT_MARKET",
            qty = 0.002,
            price = null,
            stopPrice = 70_000.0,
            reduceOnly = true,
            clientOrderId = "CP1-S0_tp",
            apiKey = apiKey,
            secret = secret
        )

        val params = paramsOf(queryOf(wire.calls.single().second))
        assertEquals("TAKE_PROFIT_MARKET", params["type"])
        assertEquals("true", params["reduceOnly"])
        assertEquals("70000.0", params["stopPrice"])
        assertNull("a market exit carries no limit price", params["price"])
        assertNull(params["timeInForce"])
    }

    @Test
    fun `given a staged order when cancelled then the venue is asked by client id`() = runBlocking {
        val wire = RecordingWire(200 to """{"orderId":41,"clientOrderId":"CP1-S0","status":"CANCELED"}""")
        BinanceSignedClient.wire = wire

        val result = BinanceSignedClient.cancelOrder("BTCUSDT", "CP1-S0", apiKey, secret)

        val (method, url, _) = wire.calls.single()
        val query = queryOf(url)
        val params = paramsOf(query)
        assertEquals("DELETE", method)
        assertEquals("/fapi/v1/order", pathOf(url))
        assertEquals("BTCUSDT", params["symbol"])
        assertEquals("CP1-S0", params["origClientOrderId"])
        assertEquals(expectedSignature(query), params["signature"])
        assertTrue(result.ok)
    }

    @Test
    fun `given the wallet balance when read then the signed query carries no empty parameter`() = runBlocking {
        val wire = RecordingWire(200 to """[{"asset":"USDT","balance":"1234.5"}]""")
        BinanceSignedClient.wire = wire

        val balance = BinanceSignedClient.fetchWalletBalance(apiKey, secret)

        val query = queryOf(wire.calls.single().second)
        val segments = query.split('&')
        assertTrue("signed query opens with an empty parameter: $query", segments.none { it.isEmpty() })
        assertEquals("timestamp=$now", segments.first())
        assertEquals(expectedSignature(query), paramsOf(query)["signature"])
        assertEquals(1234.5, balance ?: Double.NaN, 0.0)
    }

    @Test
    fun `given a venue refusal when an order is placed then the venue reason reaches the caller`() = runBlocking {
        BinanceSignedClient.wire = RecordingWire(400 to """{"code":-2019,"msg":"Margin is insufficient."}""")

        val result = BinanceSignedClient.placeOrder(
            symbol = "BTCUSDT",
            side = "BUY",
            orderType = "LIMIT",
            qty = 0.002,
            price = 65_000.0,
            stopPrice = null,
            clientOrderId = "CP1-S1",
            apiKey = apiKey,
            secret = secret
        )

        assertFalse(result.ok)
        assertEquals("Margin is insufficient.", result.message)
    }

    private fun pathOf(url: String): String =
        url.removePrefix(BinanceSignedClient.BASE_FUTURES).substringBefore('?')

    private fun queryOf(url: String): String = url.substringAfter('?', "")

    private fun paramsOf(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }

    private fun expectedSignature(query: String): String {
        val payload = query.substringBefore("&signature=")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-secret".toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
