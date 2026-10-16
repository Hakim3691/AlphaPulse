package com.aistudio.alphapulse.data.network

import android.util.Log
import com.aistudio.alphapulse.domain.quant.DepthTier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class LiveStreamTelemetry(
    val symbol: String,
    val spotPrice: Double,
    val bids: List<DepthTier>,
    val asks: List<DepthTier>,
    val netImbalanceUsdt: Double,
    val bidRatioPct: Double,
    val empiricalShock: Double,
    val lambdaTurnover: Double,
    val lastUpdateTimestamp: Long
)

/**
 * Local liquidation heat derived from the exchange's own '<symbol>@forceOrder' stream (no-key,
 * spec FR-007 §9). muPath/spreadSkew follow the same convention as the remote heatmap:
 * positive muPath = downside magnet dominates (pull toward lower pain).
 */
data class LocalLiquidationTelemetry(
    val symbol: String,
    val muPath: Double,
    val spreadSkew: Double,
    val upperClusterPrice: Double?,
    val upperClusterNotional: Double,
    val lowerClusterPrice: Double?,
    val lowerClusterNotional: Double,
    val eventCount: Int,
    val lastUpdateTimestamp: Long
)

class BinanceWebSocketManager {

    companion object {
        private const val TAG = "BinanceWS"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite read timeout for WebSocket
        .retryOnConnectionFailure(true)
        .build()

    private var currentWebSocket: WebSocket? = null
    private var activeSymbol: String = ""
    private var coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _telemetryFlow = MutableStateFlow<LiveStreamTelemetry?>(null)
    val telemetryFlow: StateFlow<LiveStreamTelemetry?> = _telemetryFlow.asStateFlow()

    // Rolling 30-minute window of forced-closure events for the local liquidation heat
    private data class LiquidationEvent(val price: Double, val notional: Double, val timestamp: Long)
    private val liquidationBuffer = ArrayList<LiquidationEvent>()
    private val _liquidationTelemetry = MutableStateFlow<LocalLiquidationTelemetry?>(null)
    val liquidationTelemetryFlow: StateFlow<LocalLiquidationTelemetry?> = _liquidationTelemetry.asStateFlow()
    private var liquidationWindowMs: Long = 30 * 60 * 1000L

    // 500-element circular buffer for aggTrades to compute 95th percentile empirical shock
    private val aggTradeBuffer = ArrayList<Double>(500)
    private var prevDepthTotal = 0.0
    private var prevDepthTime = 0L
    private var currentLambda = 0.035
    private var latestSpotPrice = 0.0
    private var isClosed = false

    fun startStreaming(symbol: String) {
        if (symbol.isBlank() || (symbol == activeSymbol && currentWebSocket != null)) {
            return
        }

        activeSymbol = symbol
        closeCurrent()

        val cleanSym = symbol.lowercase()
        // Binance Futures combined stream endpoint (depth + trades + forced liquidations):
        val streamUrl = "wss://fstream.binance.com/stream?streams=${cleanSym}@depth20@250ms/${cleanSym}@aggTrade/${cleanSym}@forceOrder"

        val request = Request.Builder()
            .url(streamUrl)
            .build()

        currentWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to stream for $symbol")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: root
                    val stream = root.optString("stream")

                    if (stream.contains("@aggTrade") || data.has("p")) {
                        val p = data.optDouble("p", 0.0)
                        val q = data.optDouble("q", 0.0)
                        if (p > 0.0) {
                            latestSpotPrice = p
                            val notional = p * q
                            synchronized(aggTradeBuffer) {
                                if (aggTradeBuffer.size >= 500) {
                                    aggTradeBuffer.removeAt(0)
                                }
                                aggTradeBuffer.add(notional)
                            }
                        }
                    } else if (stream.contains("@forceOrder") || data.has("forceOrder") || data.optString("e") == "forceOrder") {
                        // Liquidation event: o.S = side of the FORCED order (SELL = long wiped), o.q/p = qty/price
                        val order = data.optJSONObject("o") ?: data.optJSONObject("forceOrder")?.optJSONObject("o")
                        if (order != null) {
                            val px = order.optDouble("ap", 0.0).takeIf { it > 0.0 }
                                ?: order.optDouble("p", 0.0).takeIf { it > 0.0 }
                                ?: order.optDouble("avgPrice", 0.0).takeIf { it > 0.0 }
                            val qty = order.optDouble("q", 0.0).takeIf { it > 0.0 }
                                ?: order.optDouble("origQty", 0.0).takeIf { it > 0.0 }
                            if (px != null && qty != null && px > 0.0 && qty > 0.0) {
                                val nowMs = order.optLong("T", System.currentTimeMillis())
                                synchronized(liquidationBuffer) {
                                    liquidationBuffer.add(LiquidationEvent(px, px * qty, nowMs))
                                    while (liquidationBuffer.isNotEmpty() && nowMs - liquidationBuffer.first().timestamp > liquidationWindowMs) {
                                        liquidationBuffer.removeAt(0)
                                    }
                                    rebuildLocalLiquidationTelemetryLocked(nowMs)
                                }
                            }
                        }
                    } else if (stream.contains("@depth20") || data.has("bids")) {
                        val bidsArr = data.optJSONArray("bids")
                        val asksArr = data.optJSONArray("asks")

                        if (bidsArr != null && asksArr != null) {
                            val bids = ArrayList<DepthTier>(bidsArr.length())
                            for (i in 0 until bidsArr.length()) {
                                val item = bidsArr.getJSONArray(i)
                                bids.add(DepthTier(item.getDouble(0), item.getDouble(1)))
                            }

                            val asks = ArrayList<DepthTier>(asksArr.length())
                            for (i in 0 until asksArr.length()) {
                                val item = asksArr.getJSONArray(i)
                                asks.add(DepthTier(item.getDouble(0), item.getDouble(1)))
                            }

                            // Compute OFI, Bid Ratio, and Lambda
                            val tiers = min(20, min(bids.size, asks.size))
                            var bidNotional = 0.0
                            var askNotional = 0.0
                            for (i in 0 until tiers) {
                                bidNotional += bids[i].price * bids[i].quantity
                                askNotional += asks[i].price * asks[i].quantity
                            }
                            val totalDepth = bidNotional + askNotional
                            val netImbalance = bidNotional - askNotional
                            val bidRatio = if (totalDepth > 0) (bidNotional / totalDepth) * 100.0 else 50.0

                            val now = System.currentTimeMillis()
                            if (prevDepthTotal > 0 && now > prevDepthTime) {
                                val dtMinutes = max(0.004, (now - prevDepthTime) / 60000.0) // 250ms is ~0.00416 min
                                val turnover = abs(totalDepth - prevDepthTotal) / prevDepthTotal
                                val estLambda = (turnover / dtMinutes).coerceIn(0.01, 0.15)
                                currentLambda = (currentLambda * 0.9) + (estLambda * 0.1)
                            }
                            prevDepthTotal = totalDepth
                            prevDepthTime = now

                            // Compute 95th percentile shock
                            var shock = 0.0
                            synchronized(aggTradeBuffer) {
                                if (aggTradeBuffer.isNotEmpty()) {
                                    val sorted = aggTradeBuffer.sorted()
                                    val idx95 = (sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)
                                    shock = sorted[idx95]
                                }
                            }
                            if (shock <= 0.0) {
                                shock = totalDepth * 0.10
                            }

                            val spot = if (latestSpotPrice > 0.0) {
                                latestSpotPrice
                            } else if (bids.isNotEmpty() && asks.isNotEmpty()) {
                                (bids[0].price + asks[0].price) / 2.0
                            } else 0.0

                            _telemetryFlow.value = LiveStreamTelemetry(
                                symbol = activeSymbol,
                                spotPrice = spot,
                                bids = bids,
                                asks = asks,
                                netImbalanceUsdt = netImbalance,
                                bidRatioPct = bidRatio,
                                empiricalShock = shock,
                                lambdaTurnover = currentLambda,
                                lastUpdateTimestamp = now
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error parsing stream frame: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "WebSocket failure: ${t.message}, scheduling reconnect...")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason ($code)")
                if (!isClosed) scheduleReconnect()
            }
        })
    }

    /** Caller must hold the lock on liquidationBuffer. */
    private fun rebuildLocalLiquidationTelemetryLocked(nowMs: Long) {
        if (liquidationBuffer.isEmpty() || latestSpotPrice <= 0.0) return

        var maxUpperPrice: Double? = null
        var maxUpperVol = 0.0
        var maxLowerPrice: Double? = null
        var maxLowerVol = 0.0
        for (ev in liquidationBuffer) {
            if (ev.price > latestSpotPrice) {
                if (maxUpperPrice == null || ev.notional > maxUpperVol) {
                    maxUpperPrice = ev.price
                    maxUpperVol = ev.notional
                }
            } else if (ev.price < latestSpotPrice) {
                if (maxLowerPrice == null || ev.notional > maxLowerVol) {
                    maxLowerPrice = ev.price
                    maxLowerVol = ev.notional
                }
            }
        }

        if (maxUpperPrice == null || maxLowerPrice == null) return
        val distUp = (maxUpperPrice - latestSpotPrice) / latestSpotPrice
        val distDown = (latestSpotPrice - maxLowerPrice) / latestSpotPrice
        val muPath = (distDown - distUp) * 0.5
        val spreadSkew = if (muPath >= 0) 1.15 else 0.85

        _liquidationTelemetry.value = LocalLiquidationTelemetry(
            symbol = activeSymbol,
            muPath = muPath,
            spreadSkew = spreadSkew,
            upperClusterPrice = maxUpperPrice,
            upperClusterNotional = maxUpperVol,
            lowerClusterPrice = maxLowerPrice,
            lowerClusterNotional = maxLowerVol,
            eventCount = liquidationBuffer.size,
            lastUpdateTimestamp = nowMs
        )
    }

    private fun scheduleReconnect() {
        if (isClosed || activeSymbol.isBlank()) return
        coroutineScope.launch {
            delay(3000)
            if (!isClosed && activeSymbol.isNotBlank()) {
                startStreaming(activeSymbol)
            }
        }
    }

    private fun closeCurrent() {
        try {
            currentWebSocket?.cancel()
            currentWebSocket = null
        } catch (_: Exception) {}
    }

    fun connect(symbol: String) = startStreaming(symbol)
    fun disconnect() = stopStreaming()

    fun stopStreaming() {
        isClosed = true
        closeCurrent()
        activeSymbol = ""
        coroutineScope.cancel()
        coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
