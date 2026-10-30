package com.aistudio.alphapulse.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Phase 4 (Spec §22, §25): Binance **user-data** stream over WebSocket.
 *
 * Emits raw ORDER_TRADE_UPDATE frames. Parsing lives in
 * [com.aistudio.alphapulse.engine.UserDataOrderUpdate] so the mapping onto reconcile
 * inputs is pure and unit-tested against the venue's field names.
 *
 * Cold flow: the socket opens on collection and closes with the collector (§5 socket
 * lifecycle). There is deliberately no internal retry — a listen key that expired
 * cannot be retried into life, and a silent internal retry would hide missed fills from
 * the collector's REST fallback (§25). The collector re-creates the key and reopens.
 */
object BinanceUserDataClient {

    const val WS_USER_BASE = "wss://fstream.binance.com/ws/"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // server pushes; never time out reads
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** Streams user-data frames for the active listen key. */
    fun streamUserEvents(listenKey: String): Flow<String> = callbackFlow {
        val request = Request.Builder().url("$WS_USER_BASE$listenKey").build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                trySendBlocking(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IllegalStateException("user-data stream failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                close(IllegalStateException("user-data stream closed: $code $reason"))
            }
        }
        val ws = client.newWebSocket(request, listener)
        awaitClose { ws.cancel() }
    }
}
