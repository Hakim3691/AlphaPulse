/**
 * BinanceProvider - ExchangeDataProvider implementation for Binance USDⓈ-M Futures.
 * Adapts ResilientBinanceClient and Binance WebSocket infrastructure to the
 * common ExchangeDataProvider contract. Primary provider for the AlphaPulse engine.
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
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.data.network.ResilientBinanceClient
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.suspendCoroutine
import java.util.*
import java.util.Locale

class BinanceProvider(
    private val client: ResilientBinanceClient = ResilientBinanceClient(),
    private val wsManager: BinanceWebSocketManager = BinanceWebSocketManager()
) : ExchangeDataProvider {

    // --- Watched symbols tracking ---
    private val _watchedSymbols = MutableStateFlow<List<String>>(emptyList())
    override val watchedSymbols: StateFlow<List<String>> = _watchedSymbols.asStateFlow()

    // --- Start/stop WebSocket streaming for a symbol ---
    fun startStreaming(symbol: String) {
        wsManager.startStreaming(symbol)
    }

    fun stopStreaming() {
        wsManager.stopStreaming()
    }

    // --- Ticker Snapshot ---
    override fun getTickerFlow(symbol: String): Flow<TickerSnapshot> = flow {
        try {
            val obj = client.getJsonObject("/fapi/v1/ticker/24hr?symbol=$symbol")
            emit(
                TickerSnapshot(
                    symbol = symbol,
                    lastPrice = obj.getDouble("lastPrice"),
                    priceChangePercent = obj.getDouble("priceChangePercent"),
                    highPrice = obj.getDouble("highPrice"),
                    lowPrice = obj.getDouble("lowPrice"),
                    volume = obj.getDouble("volume"),
                    quoteVolume = obj.getDouble("quoteVolume"),
                    openPrice = obj.getDouble("openPrice"),
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            // emit error state or retry logic
            emit(
                TickerSnapshot(
                    symbol = symbol,
                    lastPrice = 0.0,
                    priceChangePercent = 0.0,
                    highPrice = 0.0,
                    lowPrice = 0.0,
                    volume = 0.0,
                    quoteVolume = 0.0,
                    openPrice = 0.0,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    // --- Depth Flow (Order Book) ---
    // Binance WebSocket depth20 stream: ${symbol}@depth20@250ms
    override fun getDepthFlow(symbol: String): Flow<DepthSnapshot> = flow {
        // Start streaming if not already active for this symbol
        startStreaming(symbol)

        // Convert WebSocket telemetry to DepthSnapshot flow
        val depthFlow = wsManager.telemetryFlow
            .filter { it != null && it.symbol == symbol }
            .distinctUntilChanged()
            .map { telemetry ->
                DepthSnapshot(
                    bids = telemetry!!.bids,
                    asks = telemetry.asks,
                    timestamp = telemetry.lastUpdateTimestamp
                )
            }

        // Collect and emit from the transformed flow
        depthFlow.collect { emit(it) }
    }

    // --- Aggregate Trade Flow ---
    override fun getAggTradeFlow(symbol: String): Flow<AggTradeEvent> = flow {
        // Start streaming if not already active
        startStreaming(symbol)

        // Convert WebSocket channel to Flow using channel receive loop
        val channel = wsManager.aggTradeFlow
        while (true) {
            val event = channel.receive()
            if (event.symbol == symbol) {
                emit(event)
            }
        }
    }

    // --- Funding Rate Flow ---
    override fun getFundingRateFlow(symbol: String): Flow<FundingRateEvent> = flow {
        try {
            val rate = client.fetchFundingRate(symbol)
            emit(
                FundingRateEvent(
                    symbol = symbol,
                    fundingRate = rate,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            emit(
                FundingRateEvent(
                    symbol = symbol,
                    fundingRate = 0.04, // 4% fallback Rf
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    // --- Liquidation Flow ---
    override fun getLiquidationFlow(symbol: String): Flow<LiquidationEvent> = flow {
        // Start streaming if not already active
        startStreaming(symbol)

        // Convert WebSocket channel to Flow using channel receive loop
        val channel = wsManager.liquidationFlow
        while (true) {
            val event = channel.receive()
            if (event.symbol == symbol) {
                emit(event)
            }
        }
    }

    // --- Open Interest Flow ---
    override fun getOpenInterestFlow(symbol: String): Flow<OpenInterestEvent> = flow {
        // Start streaming if not already active
        startStreaming(symbol)

        // Convert WebSocket channel to Flow using channel receive loop
        val channel = wsManager.openInterestFlow
        while (true) {
            val oi = channel.receive()
            emit(OpenInterestEvent(
                symbol = symbol,
                openInterest = oi,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    // --- Kline Flow ---
    override fun getKlineFlow(symbol: String, interval: String): Flow<List<KlineData>> = flow {
        try {
            val arr = client.getJsonArray("/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=500")
            val klines = mutableListOf<KlineData>()
            for (i in 0 until arr.length()) {
                val k = arr.getJSONArray(i)
                klines.add(
                    KlineData(
                        openTime = k.getLong(0),
                        open = k.getDouble(1),
                        high = k.getDouble(2),
                        low = k.getDouble(3),
                        close = k.getDouble(4),
                        volume = k.getDouble(5),
                        quoteVolume = k.getDouble(7),
                        takerBuyQuoteVolume = k.getDouble(10)
                    )
                )
            }
            emit(klines)
        } catch (e: Exception) {
            emit(emptyList<KlineData>())
        }
    }

    // --- Symbol availability ---
    override fun isSymbolActive(symbol: String): Boolean = runBlocking {
        try {
            val obj = client.getJsonObject("/fapi/v1/ticker/24hr?symbol=$symbol")
            obj.getDouble("lastPrice") > 0.0 && obj.getDouble("quoteVolume") > 1000000.0
        } catch (e: Exception) {
            false
        }
    }

    // --- Health check ---
    override fun checkHealth(): Boolean = runBlocking {
        try {
            // Ping a known symbol
            val obj = client.getJsonObject("/fapi/v1/ticker/24hr?symbol=BTCUSDT")
            obj.getDouble("lastPrice") > 0.0
        } catch (e: Exception) {
            false
        }
    }

    // --- Update watched symbols (called from repository screening) ---
    fun updateWatchedSymbols(symbols: List<String>) {
        _watchedSymbols.value = symbols
    }
}