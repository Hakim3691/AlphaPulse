package com.aistudio.alphapulse.data.network

import android.util.Log
import com.aistudio.alphapulse.domain.quant.DepthSnapshot
import com.aistudio.alphapulse.domain.quant.DepthTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ResilientBinanceClient {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    // Primary Binance Futures REST endpoint
    private val futuresEndpoints = listOf(
        "https://fapi.binance.com"
    )

    // Binance Spot public market data mirrors (globally reachable without regional futures restrictions)
    private val spotEndpoints = listOf(
        "https://data-api.binance.vision",
        "https://api.binance.com",
        "https://api1.binance.com",
        "https://api2.binance.com",
        "https://api3.binance.com"
    )

    suspend fun getJsonString(path: String): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 1. If path is explicitly a spot endpoint (/api/v3/ or /api/), route directly to spot mirrors
        if (path.startsWith("/api/")) {
            for (baseUrl in spotEndpoints) {
                val url = baseUrl + path
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()?.trim()
                            if (!body.isNullOrBlank() && !body.startsWith("<")) {
                                return@withContext body
                            } else {
                                val preview = body?.take(30) ?: "empty"
                                lastException = IOException("Non-JSON HTML response ($preview) from $url")
                            }
                        } else {
                            val msg = "HTTP ${response.code} from spot mirror $url"
                            Log.d("ResilientBinanceClient", msg)
                            lastException = IOException(msg)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("ResilientBinanceClient", "Spot mirror failed on $url: ${e.message}")
                    lastException = e
                }
            }
            throw lastException ?: IOException("All spot endpoints unreachable for path: $path")
        }

        // 2. Otherwise (e.g. /fapi/v1/ or /futures/), attempt futures endpoint first
        for (baseUrl in futuresEndpoints) {
            val url = baseUrl + path
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()?.trim()
                        if (!body.isNullOrBlank() && !body.startsWith("<")) {
                            return@withContext body
                        } else {
                            val preview = body?.take(30) ?: "empty"
                            val msg = "Non-JSON response ($preview) from futures $url"
                            Log.d("ResilientBinanceClient", msg)
                            lastException = IOException(msg)
                        }
                    } else {
                        val msg = "HTTP ${response.code} from $url: ${response.message}"
                        Log.d("ResilientBinanceClient", msg)
                        lastException = IOException(msg)
                    }
                }
            } catch (e: Exception) {
                Log.d("ResilientBinanceClient", "Failed on $url: ${e.message}")
                lastException = e
            }
        }

        // 3. Fallback: If /fapi/v1/ failed (geo-restricted or down), try spot mirrors with /api/v3/
        if (path.startsWith("/fapi/v1/")) {
            val spotPath = path.replace("/fapi/v1/", "/api/v3/")
            for (baseUrl in spotEndpoints) {
                val url = baseUrl + spotPath
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()?.trim()
                            if (!body.isNullOrBlank() && !body.startsWith("<")) {
                                return@withContext body
                            } else {
                                val preview = body?.take(30) ?: "empty"
                                lastException = IOException("Non-JSON HTML payload ($preview) from spot fallback $url")
                            }
                        } else {
                            val msg = "HTTP ${response.code} from spot fallback $url"
                            Log.d("ResilientBinanceClient", msg)
                            lastException = IOException(msg)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("ResilientBinanceClient", "Spot fallback failed on $url: ${e.message}")
                    lastException = e
                }
            }
        }

        throw lastException ?: IOException("All endpoints unreachable for path: $path")
    }

    suspend fun getJsonArray(path: String): JSONArray = withContext(Dispatchers.IO) {
        val str = getJsonString(path)
        JSONArray(str)
    }

    suspend fun getJsonObject(path: String): JSONObject = withContext(Dispatchers.IO) {
        val str = getJsonString(path)
        JSONObject(str)
    }

    suspend fun fetchCoinglassOI(symbol: String): Double? = withContext(Dispatchers.IO) {
        try {
            val cleanSymbol = symbol.replace("USDT", "")
            val url = "https://open-api.coinglass.com/public/v2/open_interest?symbol=$cleanSymbol"
            val request = Request.Builder()
                .url(url)
                .header("accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val root = JSONObject(body)
                        val dataArr = root.optJSONArray("data")
                        if (dataArr != null && dataArr.length() > 0) {
                            for (i in 0 until dataArr.length()) {
                                val item = dataArr.getJSONObject(i)
                                if (item.optString("exchangeName").equals("Binance", ignoreCase = true)) {
                                    val oi = item.optDouble("openInterestAmount", Double.NaN)
                                    if (!oi.isNaN()) return@withContext oi
                                }
                            }
                            val fallbackOi = dataArr.getJSONObject(0).optDouble("openInterestAmount", Double.NaN)
                            if (!fallbackOi.isNaN()) return@withContext fallbackOi
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("ResilientBinanceClient", "Coinglass OI fallback failed: ${e.message}")
        }
        return@withContext null
    }

    /**
     * Provider health tracking.
     *
     * SmartMoneyAPI's public (no-key) liquidation-heatmap endpoint returns a JSON object with a
     * top-level `clusters` array — each entry carrying `price` + `notional` — NOT a bare `data`
     * array. Requests are documented as `symbol=<ASSET>` with optional `window_minutes` (5-1440)
     * and `price_buckets` (5-100). Listing "USDT" also resolves. Consecutive failures trip a
     * health bit so a dead provider is skipped for 5 minutes instead of called-and-ignored on
     * every 10-second evaluation cycle.
     */
    private val heatmapFailures = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var heatmapCooldownUntil = 0L
    private val heatmapCooldownMs = 5 * 60 * 1000L

    suspend fun fetchLiquidityHeatmap(symbol: String, spotPrice: Double): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        // Health bit: skip calls for 5 minutes after repeated consecutive failures
        if (heatmapCooldownUntil > System.currentTimeMillis()) {
            return@withContext null
        }
        try {
            val asset = symbol.removeSuffix("USDT").ifBlank { symbol }
            val url = "https://api.smartmoneyapi.com/v1/liquidations/heatmap" +
                    "?symbol=$asset&window_minutes=240&price_buckets=100"
            val request = Request.Builder()
                .url(url)
                .header("accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        // The API nests clusters at top level; older wrappers exposed `data`. Try both.
                        val root: JSONObject? = try {
                            JSONObject(body)
                        } catch (_: Exception) {
                            null
                        }

                        if (root != null) {
                            val clusters: JSONArray = root.optJSONArray("clusters")
                                ?: root.optJSONObject("data")?.optJSONArray("clusters")
                                ?: root.optJSONArray("data")
                                ?: JSONArray("[]")

                            val pair = pickStrongestLiquidationClusters(clusters, spotPrice)
                            if (pair != null) {
                                heatmapFailures.set(0)
                                return@withContext pair
                            }
                        }
                        heatmapFailures.incrementAndGet()
                        if (heatmapFailures.get() >= 3) {
                            heatmapCooldownUntil = System.currentTimeMillis() + heatmapCooldownMs
                        }
                    }
                } else {
                    heatmapFailures.incrementAndGet()
                    if (heatmapFailures.get() >= 3) {
                        heatmapCooldownUntil = System.currentTimeMillis() + heatmapCooldownMs
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("ResilientBinanceClient", "Heatmap API error: ${e.message}")
            heatmapFailures.incrementAndGet()
            if (heatmapFailures.get() >= 3) {
                heatmapCooldownUntil = System.currentTimeMillis() + heatmapCooldownMs
            }
        }
        return@withContext null
    }

    /** Strongest magnet (max notional) above and below spot from a normalized liquidation-cluster array. */
    private fun pickStrongestLiquidationClusters(clusters: JSONArray, spotPrice: Double): Pair<Double, Double>? {
        var maxUpperPrice: Double? = null
        var maxUpperVol = 0.0
        var maxLowerPrice: Double? = null
        var maxLowerVol = 0.0

        for (i in 0 until clusters.length()) {
            val c = clusters.optJSONObject(i) ?: continue
            val p = c.optDouble("price", c.optDouble("p", c.optDouble("level", Double.NaN)))
            val v = c.optDouble("notional", c.optDouble("size", c.optDouble("v", c.optDouble("volume", 1.0))))

            if (!p.isNaN() && !v.isNaN() && p > 0) {
                if (p > spotPrice) {
                    if (maxUpperPrice == null || v > maxUpperVol) {
                        maxUpperPrice = p
                        maxUpperVol = v
                    }
                } else if (p < spotPrice) {
                    if (maxLowerPrice == null || v > maxLowerVol) {
                        maxLowerPrice = p
                        maxLowerVol = v
                    }
                }
            }
        }

        if (maxUpperPrice == null || maxLowerPrice == null) return null
        val distUp = (maxUpperPrice - spotPrice) / spotPrice
        val distDown = (spotPrice - maxLowerPrice) / spotPrice
        val muPath = (distDown - distUp) * 0.5
        val spreadSkew = if (muPath >= 0) 1.15 else 0.85
        return Pair(muPath, spreadSkew)
    }

    suspend fun fetchDepth(symbol: String, limit: Int = 50): DepthSnapshot = withContext(Dispatchers.IO) {
        val json = getJsonObject("/fapi/v1/depth?symbol=$symbol&limit=$limit")
        val bidsArr = json.optJSONArray("bids") ?: JSONArray()
        val asksArr = json.optJSONArray("asks") ?: JSONArray()

        val bids = mutableListOf<DepthTier>()
        for (i in 0 until bidsArr.length()) {
            val tier = bidsArr.getJSONArray(i)
            bids.add(DepthTier(price = tier.getDouble(0), quantity = tier.getDouble(1)))
        }

        val asks = mutableListOf<DepthTier>()
        for (i in 0 until asksArr.length()) {
            val tier = asksArr.getJSONArray(i)
            asks.add(DepthTier(price = tier.getDouble(0), quantity = tier.getDouble(1)))
        }

        DepthSnapshot(bids = bids, asks = asks)
    }

    suspend fun fetchAggTrades(symbol: String, limit: Int = 500): List<Double> = withContext(Dispatchers.IO) {
        val arr = getJsonArray("/fapi/v1/aggTrades?symbol=$symbol&limit=$limit")
        val notionals = mutableListOf<Double>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val p = obj.getDouble("p")
            val q = obj.getDouble("q")
            notionals.add(p * q)
        }
        notionals.sorted()
    }

    suspend fun fetchHistoricalOi(symbol: String, limit: Int = 10): List<Double> = withContext(Dispatchers.IO) {
        try {
            val arr = getJsonArray("/futures/data/openInterestHist?symbol=$symbol&period=1d&limit=$limit")
            val list = mutableListOf<Double>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sumOiVal = obj.optDouble("sumOpenInterestValue", 0.0)
                list.add(sumOiVal)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchFundingRate(symbol: String = "BTCUSDT"): Double = withContext(Dispatchers.IO) {
        try {
            val arr = getJsonArray("/fapi/v1/fundingRate?symbol=$symbol&limit=1")
            if (arr.length() > 0) {
                val rate = arr.getJSONObject(0).optDouble("fundingRate", Double.NaN)
                if (!rate.isNaN()) {
                    // Annualize 8h funding rate: rate * 3 (periods/day) * 365 (days/yr)
                    val annualized = rate * 3.0 * 365.0
                    return@withContext annualized.coerceIn(-0.50, 0.50)
                }
            }
        } catch (e: Exception) {
            Log.d("ResilientBinanceClient", "Failed to fetch funding rate: ${e.message}")
        }
        return@withContext 0.04 // Default 4.0%
    }
}
