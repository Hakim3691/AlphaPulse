/**
 * HyperliquidProvider - ExchangeDataProvider implementation for Hyperliquid Perpetual Futures.
 * Provides failover capability with unique L2 book and trade streams.
 *
 * Spec Ref: FR-004 §2, FR-005 §2, Gap §2 line 14
 */

package com.aistudio.alphapulse.data.network

import com.aistudio.alphapulse.domain.data.ExchangeDataProvider
import com.aistudio.alphapulse.domain.data.TickerSnapshot
import com.aistudio.alphapulse.domain.data.AggTradeEvent
import com.aistudio.alphapulse.domain.data.FundingRateEvent
import com.aistudio.alphapulse.domain.data.LiquidationEvent
import com.aistudio.alphapulse.domain.data.OpenInterestEvent
import com.aistudio.alphapulse.domain.quant.DepthSnapshot
import com.aistudio.alphapulse.domain.quant.DepthTier
import com.aistudio.alphapulse.domain.quant.KlineData
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HyperliquidProvider : ExchangeDataProvider {

    companion object {
        private const val TAG = "HyperliquidProvider"
        private const val REST_BASE = "https://api.hyperliquid.xyz"
        private const val WS_URL = "wss://api.hyperliquid.xyz/ws"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var currentWebSocket: WebSocket? = null
    private var activeSymbol: String = ""
    private var isClosed = false

    private val _watchedSymbols = MutableStateFlow<List<String>>(emptyList())
    override val watchedSymbols: StateFlow<List<String>> = _watchedSymbols.asStateFlow()

    private val _depthFlow = MutableStateFlow<DepthSnapshot?>(null)
    val depthFlow: StateFlow<DepthSnapshot?> = _depthFlow.asStateFlow()
    private val _aggTradeChannel = Channel<AggTradeEvent>(Channel.UNLIMITED)
    private val _liquidationChannel = Channel<LiquidationEvent>(Channel.UNLIMITED)
    private val _openInterestChannel = Channel<Double>(Channel.UNLIMITED)
    private val _fundingRate = MutableStateFlow<Double>(0.04)

    override fun getTickerFlow(symbol: String): Flow<TickerSnapshot> = flow {
        try {
            val body = JSONObject().apply { put("type", "metaAndAssetCtxs") }
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/info")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val json = JSONArray(response.body?.string() ?: "[]")
            if (json.length() > 1) {
                val meta = json.getJSONObject(0)
                val ctxs = json.getJSONArray(1)
                val universe = meta.optJSONArray("universe")
                for (i in 0 until ctxs.length()) {
                    val ctx = ctxs.getJSONObject(i)
                    val assetName = universe?.getJSONObject(i)?.optString("name") ?: ""
                    if (assetName == symbol.replace("USDT", "")) {
                        emit(TickerSnapshot(
                            symbol = symbol,
                            lastPrice = ctx.optDouble("markPx", ctx.optDouble("midPx", 0.0)),
                            priceChangePercent = ctx.optDouble("dayNtlVlm", 0.0),
                            highPrice = ctx.optDouble("markPx", 0.0),
                            lowPrice = ctx.optDouble("markPx", 0.0),
                            volume = 0.0,
                            quoteVolume = ctx.optDouble("dayNtlVlm", 0.0),
                            openPrice = ctx.optDouble("prevDayPx", 0.0),
                            timestamp = System.currentTimeMillis()
                        ))
                        return@flow
                    }
                }
            }
            emit(TickerSnapshot(symbol = symbol, lastPrice = 0.0, priceChangePercent = 0.0,
                highPrice = 0.0, lowPrice = 0.0, volume = 0.0, quoteVolume = 0.0,
                openPrice = 0.0, timestamp = System.currentTimeMillis()))
        } catch (e: Exception) {
            emit(TickerSnapshot(symbol = symbol, lastPrice = 0.0, priceChangePercent = 0.0,
                highPrice = 0.0, lowPrice = 0.0, volume = 0.0, quoteVolume = 0.0,
                openPrice = 0.0, timestamp = System.currentTimeMillis()))
        }
    }

    override fun getDepthFlow(symbol: String): Flow<DepthSnapshot> = flow {
        startStreaming(symbol)

        // Convert internal depthFlow to DepthSnapshot flow
        val transformedFlow = depthFlow
            .filter { it != null && it.bids.isNotEmpty() }
            .distinctUntilChanged()
            .map { it!! }

        // Collect and emit from the transformed flow
        transformedFlow.collect { emit(it) }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun getAggTradeFlow(symbol: String): Flow<AggTradeEvent> = flow {
        startStreaming(symbol)

        // Convert channel to flow using consumeEach inside flow builder
        _aggTradeChannel.consumeEach { event ->
            if (event.symbol == symbol) emit(event)
        }
    }

    override fun getFundingRateFlow(symbol: String): Flow<FundingRateEvent> = flow {
        try {
            val body = JSONObject().apply { put("type", "metaAndAssetCtxs") }
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/info")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val json = JSONArray(response.body?.string() ?: "[]")
            if (json.length() > 1) {
                val ctxs = json.getJSONArray(1)
                val universe = json.getJSONObject(0).optJSONArray("universe")
                for (i in 0 until ctxs.length()) {
                    val ctx = ctxs.getJSONObject(i)
                    val assetName = universe?.getJSONObject(i)?.optString("name") ?: ""
                    if (assetName == symbol.replace("USDT", "")) {
                        val rate = ctx.optDouble("funding", 0.0) // Hourly rate
                        _fundingRate.value = rate * 24 * 365 // Annualize
                        emit(FundingRateEvent(symbol = symbol, fundingRate = _fundingRate.value, timestamp = System.currentTimeMillis()))
                        return@flow
                    }
                }
            }
            emit(FundingRateEvent(symbol = symbol, fundingRate = 0.04, timestamp = System.currentTimeMillis()))
        } catch (e: Exception) {
            emit(FundingRateEvent(symbol = symbol, fundingRate = 0.04, timestamp = System.currentTimeMillis()))
        }
    }

    override fun getLiquidationFlow(symbol: String): Flow<LiquidationEvent> = flow {
        startStreaming(symbol)

        // Convert channel to flow using consumeEach inside flow builder
        _liquidationChannel.consumeEach { event ->
            if (event.symbol == symbol) emit(event)
        }
    }

    override fun getOpenInterestFlow(symbol: String): Flow<OpenInterestEvent> = flow {
        startStreaming(symbol)

        // Convert channel to flow using consumeEach inside flow builder
        _openInterestChannel.consumeEach { oi ->
            emit(OpenInterestEvent(symbol = symbol, openInterest = oi, timestamp = System.currentTimeMillis()))
        }
    }

    override fun getKlineFlow(symbol: String, interval: String): Flow<List<KlineData>> = flow {
        val hlInterval = when (interval) {
            "1m" -> "1m"
            "5m" -> "5m"
            "15m" -> "15m"
            "30m" -> "30m"
            "1h" -> "1h"
            "4h" -> "4h"
            "1d" -> "1d"
            else -> "1h"
        }
        val asset = symbol.replace("USDT", "")
        try {
            val body = JSONObject().apply {
                put("type", "candleSnapshot")
                put("req", JSONObject().apply {
                    put("coin", asset)
                    put("interval", hlInterval)
                    put("limit", 500)
                })
            }
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/info")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val json = JSONArray(response.body?.string() ?: "[]")
            val klines = mutableListOf<KlineData>()
            for (i in 0 until json.length()) {
                val c = json.getJSONObject(i)
                klines.add(KlineData(
                    openTime = c.optLong("t", c.optLong("T", 0)),
                    open = c.optDouble("o", 0.0),
                    high = c.optDouble("h", 0.0),
                    low = c.optDouble("l", 0.0),
                    close = c.optDouble("c", 0.0),
                    volume = c.optDouble("v", 0.0),
                    quoteVolume = c.optDouble("n", 0.0),
                    takerBuyQuoteVolume = 0.0
                ))
            }
            emit(klines)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }

    override fun isSymbolActive(symbol: String): Boolean = runBlocking {
        try {
            val body = JSONObject().apply { put("type", "metaAndAssetCtxs") }
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/info")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val json = JSONArray(response.body?.string() ?: "[]")
            if (json.length() > 1) {
                val ctxs = json.getJSONArray(1)
                val universe = json.getJSONObject(0).optJSONArray("universe")
                for (i in 0 until ctxs.length()) {
                    val ctx = ctxs.getJSONObject(i)
                    val assetName = universe?.getJSONObject(i)?.optString("name") ?: ""
                    if (assetName == symbol.replace("USDT", "")) {
                        return@runBlocking (ctx.optDouble("markPx", 0.0) > 0.0) && (ctx.optDouble("dayNtlVlm", 0.0) > 1000000.0)
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun checkHealth(): Boolean = runBlocking {
        try {
            val body = JSONObject().apply { put("type", "metaAndAssetCtxs") }
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/info")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val json = JSONArray(response.body?.string() ?: "[]")
            json.length() >= 2
        } catch (e: Exception) {
            false
        }
    }

    fun startStreaming(symbol: String) {
        if (symbol.isBlank() || (symbol == activeSymbol && currentWebSocket != null)) return
        activeSymbol = symbol
        closeCurrent()

        val asset = symbol.replace("USDT", "")
        val request = Request.Builder().url(WS_URL).build()
        currentWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                // Subscribe to L2 book
                val l2Sub = JSONObject().apply {
                    put("method", "subscribe")
                    put("subscription", JSONObject().apply {
                        put("type", "l2Book")
                        put("coin", asset)
                    })
                }
                webSocket.send(l2Sub.toString())
                // Subscribe to trades
                val tradesSub = JSONObject().apply {
                    put("method", "subscribe")
                    put("subscription", JSONObject().apply {
                        put("type", "trades")
                        put("coin", asset)
                    })
                }
                webSocket.send(tradesSub.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val channel = root.optString("channel")
                    val data = root.optJSONArray("data")
                    if (data == null) return

                    when (channel) {
                        "l2Book" -> parseL2Book(data, symbol)
                        "trades" -> parseTrades(data, symbol)
                    }
                } catch (e: Exception) {
                    // Log error
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isClosed) scheduleReconnect()
            }
        })
    }

    private fun parseL2Book(data: JSONArray, symbol: String) {
        // Hyperliquid sends full L2 book snapshots
        for (i in 0 until data.length()) {
            val level = data.getJSONObject(i)
            val bids = mutableListOf<DepthTier>()
            val asks = mutableListOf<DepthTier>()
            val levels = level.optJSONArray("levels")
            if (levels != null) {
                for (j in 0 until levels.length()) {
                    val l = levels.getJSONArray(j)
                    val price = l.getDouble(0)
                    val size = l.getDouble(1)
                    val side = l.optString(2, "B")
                    if (side == "B" || side == "b" || side == "buy") {
                        bids.add(DepthTier(price, size))
                    } else {
                        asks.add(DepthTier(price, size))
                    }
                }
            }
            _depthFlow.value = DepthSnapshot(bids = bids, asks = asks, timestamp = System.currentTimeMillis())
        }
    }

    private fun parseTrades(data: JSONArray, symbol: String) {
        for (i in 0 until data.length()) {
            val t = data.getJSONObject(i)
            val p = t.optDouble("px", 0.0)
            val q = t.optDouble("sz", 0.0)
            if (p > 0) {
                scope.launch { _aggTradeChannel.send(AggTradeEvent(
                    tradeId = t.optLong("tid", t.optLong("time", System.currentTimeMillis())),
                    price = p, quantity = q, quoteVolume = p * q,
                    buyerMaker = t.optString("side", "B") == "A", // A=ask (seller), B=bid (buyer)
                    timestamp = t.optLong("time", System.currentTimeMillis()),
                    symbol = symbol
                )) }
            }
        }
    }

    private fun scheduleReconnect() {
        if (isClosed || activeSymbol.isBlank()) return
        scope.launch {
            kotlinx.coroutines.delay(3000)
            if (!isClosed && activeSymbol.isNotBlank()) startStreaming(activeSymbol)
        }
    }

    private fun closeCurrent() {
        try { currentWebSocket?.cancel(); currentWebSocket = null } catch (_: Exception) {}
    }

    fun stopStreaming() {
        isClosed = true
        closeCurrent()
        activeSymbol = ""
    }

    fun updateWatchedSymbols(symbols: List<String>) {
        _watchedSymbols.value = symbols
    }
}