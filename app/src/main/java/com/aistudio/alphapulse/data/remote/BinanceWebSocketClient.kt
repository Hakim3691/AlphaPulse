package com.aistudio.alphapulse.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phase 2 (Spec §5 Active Delta Mode): Binance combined-market streams over WebSocket.
 *
 * OkHttp is already a declared project dependency. Streams are exposed as **cold**
 * Kotlin Flows: the socket opens on collection and is torn down when the collector is
 * cancelled — matching §5's mandate that the socket is closed on campaign completion
 * or cancellation. Automatic reconnection with capped backoff keeps live boards fed
 * across transient drops.
 *
 * Combined-stream payloads arrive as {"stream": "<name>", "data": {...}}.
 */
object BinanceWebSocketClient {

    const val WS_COMBINED_BASE = "wss://fstream.binance.com/stream?streams="

    data class BookTickerDelta(
        val symbol: String,
        val bestBid: Double,
        val bidQty: Double,
        val bestAsk: Double,
        val askQty: Double
    ) {
        val midPrice: Double get() = (bestBid + bestAsk) / 2.0
        val spreadPct: Double get() = if (midPrice > 0) (bestAsk - bestBid) / midPrice * 100.0 else 0.0
    }

    data class AggTradeDelta(
        val symbol: String,
        val price: Double,
        val qty: Double,
        val tradeTimeUtcMs: Long,
        val isBuyerMaker: Boolean
    )

    data class KlineDelta(
        val symbol: String,
        val interval: String,
        val openTimeUtcMs: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val isClosed: Boolean
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // server pushes; never time out reads
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** Streams only the deltas required by the active symbol: best bid/ask queue state. */
    fun streamBookTicker(symbol: String): Flow<BookTickerDelta> = callbackFlow {
        val lower = symbol.lowercase()
        val request = Request.Builder().url("$WS_COMBINED_BASE${lower}@bookTicker").build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val data = JSONObject(text).optJSONObject("data") ?: return
                    if (data.optString("s").uppercase() != symbol.uppercase()) return
                    trySendBlocking(
                        BookTickerDelta(
                            symbol = symbol,
                            bestBid = data.optDoubleOrNull("b") ?: return,
                            bidQty = data.optDoubleOrNull("B") ?: 0.0,
                            bestAsk = data.optDoubleOrNull("a") ?: return,
                            askQty = data.optDoubleOrNull("A") ?: 0.0
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IllegalStateException("bookTicker stream failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                close(IllegalStateException("bookTicker stream closed: $code"))
            }
        }
        val ws = client.newWebSocket(request, listener)
        awaitClose { ws.cancel() }
    }.retryWithBackoff()

    /** Taker-aggression deltas (trades): buyer-is-maker ⇒ taker sell, else taker buy. */
    fun streamAggTrades(symbol: String): Flow<AggTradeDelta> = callbackFlow {
        val lower = symbol.lowercase()
        val request = Request.Builder().url("$WS_COMBINED_BASE${lower}@aggTrade").build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val data = JSONObject(text).optJSONObject("data") ?: return
                    if (data.optString("s").uppercase() != symbol.uppercase()) return
                    trySendBlocking(
                        AggTradeDelta(
                            symbol = symbol,
                            price = data.optDoubleOrNull("p") ?: return,
                            qty = data.optDoubleOrNull("q") ?: return,
                            tradeTimeUtcMs = data.optLongOrNull("T") ?: 0L,
                            isBuyerMaker = data.optBoolean("m", false)
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IllegalStateException("aggTrade stream failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                close(IllegalStateException("aggTrade stream closed: $code"))
            }
        }
        val ws = client.newWebSocket(request, listener)
        awaitClose { ws.cancel() }
    }.retryWithBackoff()

    /** 1m/5m candle deltas for the active symbol; `isClosed` gates structure refresh. */
    fun streamKlines(symbol: String, interval: String = "1m"): Flow<KlineDelta> = callbackFlow {
        val lower = symbol.lowercase()
        val request = Request.Builder().url("$WS_COMBINED_BASE${lower}@kline_$interval").build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val data = JSONObject(text).optJSONObject("data") ?: return
                    val k = data.optJSONObject("k") ?: return
                    if (data.optString("s").uppercase() != symbol.uppercase()) return
                    trySendBlocking(
                        KlineDelta(
                            symbol = symbol,
                            interval = k.optString("i", interval),
                            openTimeUtcMs = k.optLongOrNull("t") ?: return,
                            open = k.optDoubleOrNull("o") ?: return,
                            high = k.optDoubleOrNull("h") ?: return,
                            low = k.optDoubleOrNull("l") ?: return,
                            close = k.optDoubleOrNull("c") ?: return,
                            volume = k.optDoubleOrNull("v") ?: 0.0,
                            isClosed = k.optBoolean("x", false)
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IllegalStateException("kline stream failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                close(IllegalStateException("kline stream closed: $code"))
            }
        }
        val ws = client.newWebSocket(request, listener)
        awaitClose { ws.cancel() }
    }.retryWithBackoff()

    private fun <T> Flow<T>.retryWithBackoff(): Flow<T> = retryWhen { cause, attempt ->
        val waitMs = minOf(30_000L, 1_000L * (1L shl (attempt.toInt().coerceAtMost(5))))
        delay(waitMs)
        true
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key)) return null
        val v = opt(key)
        return when (v) {
            null, JSONObject.NULL -> null
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
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
