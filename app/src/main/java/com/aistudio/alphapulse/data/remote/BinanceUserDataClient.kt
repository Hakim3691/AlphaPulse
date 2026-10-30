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
 * Phase 4 (Spec §22, §25): Binance **user-data** stream over WebSocket.
 *
 * Emits ORDER_TRADE_UPDATE events as [FillEvent]s; `clientOrderId` is authored by
 * [BinanceSignedClient.placeOrder] as `<campaignId>_<sliceIndex>`, so fills reconcile
 * deterministically onto [com.aistudio.alphapulse.data.model.LadderSlice] states without a
 * network-order lookup. Cold flow: the socket opens on collection and closes with the
 * collector (§5 socket lifecycle), reconnecting with capped backoff on drops.
 */
object BinanceUserDataClient {

    const val WS_USER_BASE = "wss://fstream.binance.com/ws/"

    data class FillEvent(
        val symbol: String,
        val clientOrderId: String,
        val orderId: Long,
        val orderStatus: String,     // NEW / PARTIALLY_FILLED / FILLED / CANCELED / EXPIRED / REJECTED
        val side: String,            // BUY / SELL
        val lastFilledQty: Double,
        val lastFilledPrice: Double,
        val eventTimeUtcMs: Long
    ) {
        val isFill: Boolean get() = orderStatus == "FILLED" || orderStatus == "PARTIALLY_FILLED"
        val isTerminalCancel: Boolean
            get() = orderStatus == "CANCELED" || orderStatus == "EXPIRED" || orderStatus == "REJECTED"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // server pushes; never time out reads
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** Streams user-data order events for the active listen key. */
    fun streamUserEvents(listenKey: String): Flow<FillEvent> = callbackFlow {
        val request = Request.Builder().url("$WS_USER_BASE$listenKey").build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    if (root.optString("e") != "ORDER_TRADE_UPDATE") return
                    val o = root.optJSONObject("o") ?: return
                    trySendBlocking(
                        FillEvent(
                            symbol = o.optString("s"),
                            clientOrderId = o.optString("c"),
                            orderId = o.optLong("i", -1L),
                            orderStatus = o.optString("X"),
                            side = o.optString("S"),
                            lastFilledQty = o.optDoubleOrZero("l"),
                            lastFilledPrice = o.optDoubleOrZero("L"),
                            eventTimeUtcMs = root.optLong("E", 0L)
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IllegalStateException("user-data stream failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                close(IllegalStateException("user-data stream closed: $code"))
            }
        }
        val ws = client.newWebSocket(request, listener)
        awaitClose { ws.cancel() }
    }.retryWithBackoff()

    private fun <T> Flow<T>.retryWithBackoff(): Flow<T> = retryWhen { _, attempt ->
        delay(minOf(30_000L, 1_000L * (1L shl (attempt.toInt().coerceAtMost(5)))))
        true
    }

    private fun JSONObject.optDoubleOrZero(key: String): Double {
        val v = opt(key)
        return when (v) {
            null, JSONObject.NULL -> 0.0
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}
