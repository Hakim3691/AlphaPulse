/**
 * BybitProvider - ExchangeDataProvider implementation for Bybit USDT Perpetual Futures.
 * Provides failover capability when Binance is unavailable or geo-restricted.
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BybitProvider : ExchangeDataProvider {

    companion object {
        private const val TAG = "BybitProvider"
        private const val REST_BASE = "https://api.bybit.com"
        private const val WS_URL = "wss://stream.bybit.com/v5/public/linear"
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
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/v5/market/tickers?category=linear&symbol=$symbol")
                    .build()
            ).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val list = json.optJSONObject("result")?.optJSONArray("list")
            val ticker = list?.getJSONObject(0)
            emit(TickerSnapshot(
                symbol = symbol,
                lastPrice = ticker?.optDouble("lastPrice") ?: 0.0,
                priceChangePercent = ticker?.optDouble("price24hPcnt") ?: 0.0 * 100,
                highPrice = ticker?.optDouble("highPrice24h") ?: 0.0,
                lowPrice = ticker?.optDouble("lowPrice24h") ?: 0.0,
                volume = ticker?.optDouble("volume24h") ?: 0.0,
                quoteVolume = ticker?.optDouble("turnover24h") ?: 0.0,
                openPrice = ticker?.optDouble("prevPrice24h") ?: 0.0,
                timestamp = System.currentTimeMillis()
            ))
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

    override fun getAggTradeFlow(symbol: String): Flow<AggTradeEvent> = flow {
        startStreaming(symbol)

        // Convert channel to flow using consumeEach inside flow builder
        _aggTradeChannel.consumeEach { event ->
            if (event.symbol == symbol) emit(event)
        }
    }

    override fun getFundingRateFlow(symbol: String): Flow<FundingRateEvent> = flow {
        try {
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/v5/market/funding/history?category=linear&symbol=$symbol&limit=1")
                    .build()
            ).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val list = json.optJSONObject("result")?.optJSONArray("list")
            val rate = list?.getJSONObject(0)?.optDouble("fundingRate") ?: 0.0
            _fundingRate.value = rate * 3 * 365 // Annualize
            emit(FundingRateEvent(symbol = symbol, fundingRate = _fundingRate.value, timestamp = System.currentTimeMillis()))
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
        val bybitInterval = when (interval) {
            "1m" -> "1"
            "5m" -> "5"
            "15m" -> "15"
            "30m" -> "30"
            "1h" -> "60"
            "4h" -> "240"
            "1d" -> "D"
            else -> "60"
        }
        try {
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/v5/market/kline?category=linear&symbol=$symbol&interval=$bybitInterval&limit=500")
                    .build()
            ).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val list = json.optJSONObject("result")?.optJSONArray("list")
            val klines = mutableListOf<KlineData>()
            if (list != null) {
                for (i in 0 until list.length()) {
                    val k = list.getJSONArray(i)
                    klines.add(KlineData(
                        openTime = k.getLong(0),
                        open = k.getDouble(1),
                        high = k.getDouble(2),
                        low = k.getDouble(3),
                        close = k.getDouble(4),
                        volume = k.getDouble(5),
                        quoteVolume = k.getDouble(6),
                        takerBuyQuoteVolume = 0.0 // Not provided by Bybit
                    ))
                }
            }
            emit(klines)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }

    override fun isSymbolActive(symbol: String): Boolean = runBlocking {
        try {
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/v5/market/tickers?category=linear&symbol=$symbol")
                    .build()
            ).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val list = json.optJSONObject("result")?.optJSONArray("list")
            val ticker = list?.getJSONObject(0)
            (ticker?.optDouble("lastPrice") ?: 0.0) > 0.0 && (ticker?.optDouble("turnover24h") ?: 0.0) > 1000000.0
        } catch (e: Exception) {
            false
        }
    }

    override fun checkHealth(): Boolean = runBlocking {
        try {
            val response = client.newCall(
                Request.Builder()
                    .url("$REST_BASE/v5/market/tickers?category=linear&symbol=BTCUSDT")
                    .build()
            ).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            json.optString("retCode") == "0"
        } catch (e: Exception) {
            false
        }
    }

    fun startStreaming(symbol: String) {
        if (symbol.isBlank() || (symbol == activeSymbol && currentWebSocket != null)) return
        activeSymbol = symbol
        closeCurrent()

        val cleanSym = symbol.uppercase()
        val request = Request.Builder().url(WS_URL).build()
        currentWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                val subs = JSONObject().apply {
                    put("op", "subscribe")
                    put("args", JSONObject().apply {
                        put("depth", 20)
                        put("symbol", cleanSym)
                    }.toString())
                }
                webSocket.send(subs.toString())
                // Also subscribe to trades, liquidations, OI
                val tradesSub = JSONObject().apply {
                    put("op", "subscribe")
                    put("args", "publicTrade.$cleanSym")
                }
                webSocket.send(tradesSub.toString())
                val liqSub = JSONObject().apply {
                    put("op", "subscribe")
                    put("args", "liquidation.$cleanSym")
                }
                webSocket.send(liqSub.toString())
                val oiSub = JSONObject().apply {
                    put("op", "subscribe")
                    put("args", "openInterest.$cleanSym")
                }
                webSocket.send(oiSub.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val topic = root.optString("topic")
                    val data = root.optJSONArray("data")
                    if (data == null) return

                    when {
                        topic.contains("orderbook") -> parseDepth(data, cleanSym)
                        topic.contains("publicTrade") -> parseTrades(data, cleanSym)
                        topic.contains("liquidation") -> parseLiquidations(data, cleanSym)
                        topic.contains("openInterest") -> parseOI(data, cleanSym)
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

    private fun parseDepth(data: JSONArray, symbol: String) {
        val bids = mutableListOf<DepthTier>()
        val asks = mutableListOf<DepthTier>()
        for (i in 0 until data.length()) {
            val item = data.getJSONArray(i)
            val side = item.getString(0)
            val price = item.getDouble(1)
            val qty = item.getDouble(2)
            if (side == "Buy" || side == "buy") bids.add(DepthTier(price, qty))
            else asks.add(DepthTier(price, qty))
        }
        _depthFlow.value = DepthSnapshot(bids = bids, asks = asks, timestamp = System.currentTimeMillis())
    }

    private fun parseTrades(data: JSONArray, symbol: String) {
        for (i in 0 until data.length()) {
            val t = data.getJSONObject(i)
            val p = t.optDouble("p", t.optDouble("price", 0.0))
            val q = t.optDouble("v", t.optDouble("size", 0.0))
            if (p > 0) {
                scope.launch { _aggTradeChannel.send(AggTradeEvent(
                    tradeId = t.optLong("T", t.optLong("timestamp", System.currentTimeMillis())),
                    price = p, quantity = q, quoteVolume = p * q,
                    buyerMaker = t.optString("S") == "Sell",
                    timestamp = t.optLong("T", t.optLong("timestamp", System.currentTimeMillis())),
                    symbol = symbol
                )) }
            }
        }
    }

    private fun parseLiquidations(data: JSONArray, symbol: String) {
        for (i in 0 until data.length()) {
            val l = data.getJSONObject(i)
            val p = l.optDouble("p", l.optDouble("price", 0.0))
            val q = l.optDouble("v", l.optDouble("size", 0.0))
            if (p > 0) {
                scope.launch { _liquidationChannel.send(LiquidationEvent(
                    price = p, notional = p * q,
                    side = if (l.optString("S") == "Buy") "short" else "long",
                    timestamp = l.optLong("T", l.optLong("timestamp", System.currentTimeMillis())),
                    symbol = symbol
                )) }
            }
        }
    }

    private fun parseOI(data: JSONArray, symbol: String) {
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val oi = o.optDouble("oi", o.optDouble("openInterest", 0.0))
            if (oi > 0) scope.launch { _openInterestChannel.send(oi) }
        }
    }

    private fun scheduleReconnect() {
        if (isClosed || activeSymbol.isBlank()) return
        scope.launch {
            kotlinx.coroutines.delay(3000)
            if (!isClosed && activeSymbol.isNotBlank()) startStreaming(activeSymbol)
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job())

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