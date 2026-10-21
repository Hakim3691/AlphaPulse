/***
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.suspendCoroutine
import java.util.*
import java.util.Locale

class BinanceProvider(
    private val client: ResilientBinanceClient = ResilientBinanceClient()
) : ExchangeDataProvider {

    // --- Watched symbols tracking ---
    private val _watchedSymbols = MutableStateFlow<List<String>>(emptyList())
    override val watchedSymbols: StateFlow<List<String>> = _watchedSymbols.asStateFlow()

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
        val cleanSym = symbol.replace("USDT", "").uppercase(Locale.US)
        val url = "wss://fstream.binance.com/stream?streams=${cleanSym}@depth20@250ms"
        // Use existing BinanceWebSocketManager infrastructure adapted here
        // For now, delegate to the shared WS manager via repository
        // This is a stub - full WS integration will be in Phase 2
        try {
            // Placeholder: emit initial snapshot and suspend
            // Actual WS integration will use BinanceWebSocketManager depth streaming
            emit(
                DepthSnapshot(
                    bids = emptyList<DepthTier>(),
                    asks = emptyList<DepthTier>(),
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            emit(
                DepthSnapshot(
                    bids = emptyList<DepthTier>(),
                    asks = emptyList<DepthTier>(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    // --- Aggregate Trade Flow ---
    override fun getAggTradeFlow(symbol: String): Flow<AggTradeEvent> = flow {
        // Use fetchAggTrades from ResilientBinanceClient with rolling window
        try {
            val trades = client.fetchAggTrades(symbol, 500)
            // Convert first trade as initial event, then WS will take over
            if (trades.isNotEmpty()) {
                val first = trades.first() as List<*>
                emit(
                    AggTradeEvent(
                        tradeId = (first[0] as Number).toLong(),
                        price = (first[1] as Number).toDouble(),
                        quantity = (first[2] as Number).toDouble(),
                        quoteVolume = (first[7] as Number).toDouble(),
                        buyerMaker = first[5] == 1,
                        timestamp = (first[6] as Number).toLong(),
                        symbol = symbol
                    )
                )
            }
        } catch (e: Exception) {
            // emit placeholder on error
        }
        // Suspend - WS stream will take over in production
        // Use a never-completing flow for placeholder
        while (true) { kotlinx.coroutines.delay(Long.MAX_VALUE) }
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
        // Will be integrated with Binance @forceOrder WS stream in Phase 2
        // For now, emit empty flow; fallback to smartmoneyapi.com via ResilientBinanceClient if needed
        try {
            // Placeholder: try REST endpoint if available
            val obj = client.getJsonObject("/fapi/v1/premiumIndex?symbol=$symbol")
            emit(
                LiquidationEvent(
                    price = obj.getDouble("markPrice"),
                    notional = 0.0,
                    side = "long",
                    timestamp = System.currentTimeMillis(),
                    symbol = symbol
                )
            )
        } catch (e: Exception) {
            // Fallback to smartmoneyapi.com (no-key public endpoint)
            try {
                val url = "https://api.smartmoneyapi.com/v1/liquidations/heatmap?symbol=$symbol"
                val obj = client.getJsonObject(url)
                emit(
                    LiquidationEvent(
                        price = (obj["price"] as? Number)?.toDouble() ?: 0.0,
                        notional = (obj["notional"] as? Number)?.toDouble() ?: 0.0,
                        side = obj["side"]?.toString() ?: "long",
                        timestamp = System.currentTimeMillis(),
                        symbol = symbol
                    )
                )
            } catch (e2: Exception) {
                // Final placeholder
                emit(
                    LiquidationEvent(
                        price = 0.0,
                        notional = 0.0,
                        side = "long",
                        timestamp = System.currentTimeMillis(),
                        symbol = symbol
                    )
                )
            }
        }
    }

    // --- Open Interest Flow ---
    override fun getOpenInterestFlow(symbol: String): Flow<OpenInterestEvent> = flow {
        try {
            val obj = client.getJsonObject("/fapi/v1/openInterest?symbol=$symbol")
            val rawOi = obj.optDouble("openInterest", 0.0)
            emit(
                OpenInterestEvent(
                    symbol = symbol,
                    openInterest = if (rawOi > 0) rawOi else 1.0,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            emit(
                OpenInterestEvent(
                    symbol = symbol,
                    openInterest = 1.0,
                    timestamp = System.currentTimeMillis()
                )
            )
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