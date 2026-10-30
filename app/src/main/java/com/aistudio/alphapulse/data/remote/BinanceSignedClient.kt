package com.aistudio.alphapulse.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 4 (Spec §22, §25, §33): Binance Futures **signed** endpoints for real ladder
 * execution — place, amend, cancel, cancel-all, position reconciliation and user-data
 * listen-key lifecycle.
 *
 * Security posture (Spec §3, Appendix B): credentials arrive as CharArrays copied from
 * [com.aistudio.alphapulse.engine.SessionSecurityManager]; the HMAC secret is converted to bytes in
 * a scratch buffer that is zeroized immediately after signing. The API key must transit
 * a header string (HttpURLConnection API limitation) and is never logged or persisted.
 * Every call returns a failure result instead of throwing, so the §33 guardrails can
 * count rejects and pause the venue deterministically.
 */
/**
 * The HTTP seam every signed and header request travels through.
 *
 * Production uses [SocketWire]; a spec substitutes a recording implementation so the
 * method, query and signature that would reach the venue can be asserted without a
 * network or real keys.
 */
internal fun interface Wire {
    fun send(method: String, url: String, apiKey: CharArray): Pair<Int, String?>
}

internal object SocketWire : Wire {
    override fun send(method: String, url: String, apiKey: CharArray): Pair<Int, String?> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 7_000
                requestMethod = method
                setRequestProperty("X-MBX-APIKEY", String(apiKey))
                setRequestProperty("Accept", "application/json")
                useCaches = false
            }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }
            Pair(code, body)
        } catch (_: Exception) {
            Pair(0, null)
        } finally {
            connection?.disconnect()
        }
    }
}

object BinanceSignedClient {

    const val BASE_FUTURES = BinanceFuturesClient.BASE_FUTURES
    private const val RECV_WINDOW_MS = 5_000L

    /** Replaced by specs; production talks to the venue through [SocketWire]. */
    internal var wire: Wire = SocketWire

    /** Replaced by specs so a signed query's timestamp is deterministic. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // ---------------------------------------------------------------- DTOs

    data class OrderResult(
        val ok: Boolean,
        val orderId: Long?,
        val clientOrderId: String?,
        val status: String?,
        val message: String?
    ) {
        companion object {
            fun failure(message: String) = OrderResult(false, null, null, null, message)
        }
    }

    data class PositionRisk(
        val symbol: String,
        val positionAmt: Double,   // signed: >0 long, <0 short, 0 flat
        val entryPrice: Double,
        val unrealizedPnl: Double
    )

    data class OpenOrder(
        val orderId: Long,
        val symbol: String,
        val clientOrderId: String,
        val price: Double,
        val origQty: Double,
        val executedQty: Double,
        val status: String,
        val type: String,
        val side: String
    ) {
        /**
         * True only when the venue reports the whole quantity executed. A partially
         * filled order is still open, so it is not a filled slice.
         */
        val isFullyExecuted: Boolean
            get() = origQty > 0.0 && executedQty >= origQty - 1e-9
    }

    // ---------------------------------------------------------------- signing

    /** HMAC-SHA256 hex signature; secret scratch buffers are zeroized after use. */
    internal fun sign(query: String, secret: CharArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretBytes = ByteArray(secret.size)
        for (i in secret.indices) secretBytes[i] = (secret[i].code and 0xFF).toByte()
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val queryBytes = query.toByteArray(Charsets.UTF_8)
        try {
            return mac.doFinal(queryBytes).joinToString("") { "%02x".format(it) }
        } finally {
            Arrays.fill(secretBytes, 0)
            Arrays.fill(queryBytes, 0)
        }
    }

    private fun encode(params: List<Pair<String, String>>): String =
        params.joinToString("&") { "${it.first}=${urlEncode(it.second)}" }

    /** Signed request over the same HttpURLConnection plumbing as the public client. */
    private fun signedRequest(
        method: String,
        path: String,
        params: List<Pair<String, String>>,
        apiKey: CharArray,
        secret: CharArray
    ): Pair<Int, String?> {
        if (apiKey.isEmpty() || secret.isEmpty()) return Pair(0, null)
        // A signed query never opens with '&': with no business parameters the payload
        // is the timestamp window alone, not an empty parameter followed by it.
        val base = encode(params)
        val window = "timestamp=${clock()}&recvWindow=$RECV_WINDOW_MS"
        val payload = if (base.isEmpty()) window else "$base&$window"
        val signature = sign(payload, secret)
        return wire.send(method, "$BASE_FUTURES$path?$payload&signature=$signature", apiKey)
    }

    /** Unsigned endpoint that only needs the API-key header (listen-key lifecycle). */
    private fun headerRequest(method: String, path: String, apiKey: CharArray): Pair<Int, String?> {
        if (apiKey.isEmpty()) return Pair(0, null)
        return wire.send(method, "$BASE_FUTURES$path", apiKey)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    // ---------------------------------------------------------------- order endpoints

    /**
     * §22 place one ladder slice. LIMIT (passive) or STOP (breakout/breakdown stop-limit
     * entries). `clientOrderId` encodes campaign + slice so user-data fills reconcile
     * deterministically (36-char Binance limit respected by the caller).
     */
    suspend fun placeOrder(
        symbol: String,
        side: String,
        orderType: String,
        qty: Double,
        price: Double?,
        stopPrice: Double?,
        reduceOnly: Boolean = false,
        clientOrderId: String,
        apiKey: CharArray,
        secret: CharArray
    ): OrderResult = withContext(Dispatchers.IO) {
        if (qty <= 0.0) return@withContext OrderResult.failure("Non-positive quantity")
        val params = mutableListOf(
            "symbol" to symbol,
            "side" to side,
            "type" to orderType,
            "quantity" to formatQty(qty),
            "newClientOrderId" to clientOrderId.take(36)
        )
        if (reduceOnly) params.add("reduceOnly" to "true")
        // LIMIT and STOP are both price-and-persist orders: a STOP is a stop-limit on
        // the venue, so omitting the limit price and time-in-force gets the entry
        // rejected outright and a breakout/breakdown campaign never rests.
        if (orderType == "LIMIT" || orderType == "STOP") {
            params.add("price" to formatPrice(price ?: return@withContext OrderResult.failure("$orderType requires price")))
            params.add("timeInForce" to "GTC")
        }
        if (stopPrice != null) params.add("stopPrice" to formatPrice(stopPrice))
        val (code, body) = signedRequest("POST", "/fapi/v1/order", params, apiKey, secret)
        parseOrderResult(code, body)
    }

    /** §22 amend a resting ladder slice (price and/or quantity) by clientOrderId. */
    suspend fun amendOrder(
        symbol: String,
        clientOrderId: String,
        qty: Double?,
        price: Double?,
        apiKey: CharArray,
        secret: CharArray
    ): OrderResult = withContext(Dispatchers.IO) {
        val params = mutableListOf(
            "symbol" to symbol,
            "origClientOrderId" to clientOrderId.take(36)
        )
        if (qty != null) params.add("quantity" to formatQty(qty))
        if (price != null) params.add("price" to formatPrice(price))
        if (qty == null && price == null) return@withContext OrderResult.failure("Amend requires quantity or price")
        val (code, body) = signedRequest("PUT", "/fapi/v1/order", params, apiKey, secret)
        parseOrderResult(code, body)
    }

    /** §22/§27 cancel one resting ladder slice by clientOrderId. */
    suspend fun cancelOrder(
        symbol: String,
        clientOrderId: String,
        apiKey: CharArray,
        secret: CharArray
    ): OrderResult = withContext(Dispatchers.IO) {
        val (code, body) = signedRequest(
            "DELETE", "/fapi/v1/order",
            listOf("symbol" to symbol, "origClientOrderId" to clientOrderId.take(36)),
            apiKey, secret
        )
        parseOrderResult(code, body)
    }

    /** §27/§33 emergency: cancel every open order on the symbol (hard flatten / lock). */
    suspend fun cancelAllOpenOrders(
        symbol: String,
        apiKey: CharArray,
        secret: CharArray
    ): Boolean = withContext(Dispatchers.IO) {
        val (code, _) = signedRequest(
            "DELETE", "/fapi/v1/allOpenOrders",
            listOf("symbol" to symbol), apiKey, secret
        )
        code in 200..299
    }

    /** §33 position reconciliation: signed exchange-side position for the symbol. */
    suspend fun fetchPositionRisk(
        symbol: String,
        apiKey: CharArray,
        secret: CharArray
    ): PositionRisk? = withContext(Dispatchers.IO) {
        val (code, body) = signedRequest(
            "GET", "/fapi/v2/positionRisk", listOf("symbol" to symbol), apiKey, secret
        )
        if (code !in 200..299 || body == null) return@withContext null
        runCatching {
            val json = JSONArray(body)
            (0 until json.length()).mapNotNull { i ->
                val p = json.optJSONObject(i) ?: return@mapNotNull null
                if (p.optString("symbol") != symbol) return@mapNotNull null
                PositionRisk(
                    symbol = symbol,
                    positionAmt = p.optDouble("positionAmt", 0.0),
                    entryPrice = p.optDouble("entryPrice", 0.0),
                    unrealizedPnl = p.optDouble("unRealizedProfit", 0.0)
                )
            }.firstOrNull()
        }.getOrNull()
    }

    /** Open-order snapshot for reconciliation of resting ladder slices. */
    suspend fun fetchOpenOrders(
        symbol: String,
        apiKey: CharArray,
        secret: CharArray
    ): List<OpenOrder> = withContext(Dispatchers.IO) {
        val (code, body) = signedRequest(
            "GET", "/fapi/v1/openOrders", listOf("symbol" to symbol), apiKey, secret
        )
        if (code !in 200..299 || body == null) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                OpenOrder(
                    orderId = o.optLong("orderId", -1L),
                    symbol = o.optString("symbol"),
                    clientOrderId = o.optString("clientOrderId"),
                    price = o.optDouble("price", 0.0),
                    origQty = o.optDouble("origQty", 0.0),
                    executedQty = o.optDouble("executedQty", 0.0),
                    status = o.optString("status"),
                    type = o.optString("type"),
                    side = o.optString("side")
                )
            }.filter { it.orderId >= 0 }
        }.getOrDefault(emptyList())
    }

    /**
     * §36 live-mode wallet: real USDT-margined futures wallet balance so the UI shows
     * the actual Binance balance instead of the paper wallet. Null on any failure.
     */
    suspend fun fetchWalletBalance(
        apiKey: CharArray,
        secret: CharArray
    ): Double? = withContext(Dispatchers.IO) {
        val (code, body) = signedRequest("GET", "/fapi/v2/balance", emptyList(), apiKey, secret)
        if (code !in 200..299 || body == null) return@withContext null
        runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val entry = arr.optJSONObject(i) ?: return@mapNotNull null
                if (entry.optString("asset").uppercase() != "USDT") return@mapNotNull null
                entry.optDouble("balance", Double.NaN)
            }.firstOrNull()?.takeIf { !it.isNaN() }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- listen key

    suspend fun createListenKey(apiKey: CharArray): String? = withContext(Dispatchers.IO) {
        val (code, body) = headerRequest("POST", "/fapi/v1/listenKey", apiKey)
        if (code !in 200..299 || body == null) return@withContext null
        runCatching { JSONObject(body).optString("listenKey").ifEmpty { null } }.getOrNull()
    }

    suspend fun keepAliveListenKey(apiKey: CharArray): Boolean = withContext(Dispatchers.IO) {
        val (code, _) = headerRequest("PUT", "/fapi/v1/listenKey", apiKey)
        code in 200..299
    }

    suspend fun closeListenKey(apiKey: CharArray): Boolean = withContext(Dispatchers.IO) {
        val (code, _) = headerRequest("DELETE", "/fapi/v1/listenKey", apiKey)
        code in 200..299
    }

    // ---------------------------------------------------------------- parsing

    private fun parseOrderResult(code: Int, body: String?): OrderResult {
        if (code in 200..299 && body != null) {
            return runCatching {
                val json = JSONObject(body)
                OrderResult(
                    ok = true,
                    orderId = json.optLongOrNull("orderId"),
                    clientOrderId = json.optStringOrNull("clientOrderId"),
                    status = json.optStringOrNull("status"),
                    message = null
                )
            }.getOrDefault(OrderResult.failure("Malformed order response"))
        }
        val reason = runCatching {
            val json = JSONObject(body ?: "")
            "${json.optStringOrNull("msg") ?: "HTTP $code"}"
        }.getOrDefault("HTTP $code")
        return OrderResult.failure(reason)
    }

    private fun formatQty(qty: Double): String {
        val rounded = Math.round(qty * 1000.0) / 1000.0
        return if (rounded == Math.floor(rounded) && rounded >= 1.0) rounded.toInt().toString() else rounded.toString()
    }

    private fun formatPrice(price: Double): String {
        val rounded = Math.round(price * 1000.0) / 1000.0
        return rounded.toString()
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key)) return null
        val v = opt(key)
        return when (v) {
            null, JSONObject.NULL -> null
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }
}
