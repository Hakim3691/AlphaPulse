package com.aistudio.alphapulse.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phase 2 (Spec §4): Binance Public Futures + Spot API client.
 *
 * Zero-cloud mandate: plain HttpURLConnection and the platform's built-in org.json
 * parser — no new dependencies. Every call returns `null`/empty on any failure so
 * callers implement graceful degradation (Spec §37) instead of crashing on transient
 * network errors. Timestamps arrive as epoch millis (UTC) and are kept as-is.
 *
 * Binance kline REST arrays are positional:
 * [openTime, open, high, low, close, volume, closeTime, quoteVolume, trades,
 *  takerBuyBase, takerBuyQuote, ignore]
 */
object BinanceFuturesClient {

    const val BASE_FUTURES = "https://fapi.binance.com"
    const val BASE_SPOT = "https://api.binance.com"

    // ---------------------------------------------------------------- DTOs

    data class ExchangeSymbol(
        val symbol: String,
        val pair: String?,
        val contractType: String?,
        val status: String?,
        val baseAsset: String?,
        val quoteAsset: String?
    )

    data class ExchangeInfo(val symbols: List<ExchangeSymbol>)

    data class Ticker24h(
        val symbol: String,
        val lastPrice: Double?,
        val priceChangePercent: Double?,
        val quoteVolume: Double?
    )

    data class PremiumIndex(
        val symbol: String,
        val markPrice: Double?,
        val indexPrice: Double?,
        val lastFundingRate: Double?
    )

    data class OpenInterest(val symbol: String, val openInterest: Double?)

    /** Canonical OHLCV candle, timestamps UTC epoch millis (Spec §4 normalization). */
    data class Candle(
        val openTimeUtcMs: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val closeTimeUtcMs: Long,
        val quoteVolume: Double
    )

    /** L2 depth snapshot: (price, qty) pairs. */
    data class Depth(val bids: List<Pair<Double, Double>>, val asks: List<Pair<Double, Double>>)

    // ---------------------------------------------------------------- endpoints

    suspend fun fetchFuturesExchangeInfo(): ExchangeInfo? = withContext(Dispatchers.IO) {
        val body = getJson("$BASE_FUTURES/fapi/v1/exchangeInfo") ?: return@withContext null
        runCatching {
            val root = JSONObject(body)
            val symbols = root.optJSONArray("symbols") ?: JSONArray()
            ExchangeInfo(
                (0 until symbols.length()).mapNotNull { i ->
                    val s = symbols.optJSONObject(i) ?: return@mapNotNull null
                    ExchangeSymbol(
                        symbol = s.optString("symbol"),
                        pair = s.optStringOrNull("pair"),
                        contractType = s.optStringOrNull("contractType"),
                        status = s.optStringOrNull("status"),
                        baseAsset = s.optStringOrNull("baseAsset"),
                        quoteAsset = s.optStringOrNull("quoteAsset")
                    )
                }
            )
        }.getOrNull()
    }

    suspend fun fetchSpotExchangeInfo(): List<String> = withContext(Dispatchers.IO) {
        // Returns symbols with a TRADING spot market, base-asset keyed for cross-validation.
        val body = getJson("$BASE_SPOT/api/v3/exchangeInfo") ?: return@withContext emptyList()
        runCatching {
            val symbols = JSONObject(body).optJSONArray("symbols") ?: JSONArray()
            (0 until symbols.length()).mapNotNull { i ->
                val s = symbols.optJSONObject(i) ?: return@mapNotNull null
                if (s.optString("status") == "TRADING") s.optStringOrNull("baseAsset")?.uppercase() else null
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetch24hTickers(): List<Ticker24h> = withContext(Dispatchers.IO) {
        val body = getJson("$BASE_FUTURES/fapi/v1/ticker/24hr") ?: return@withContext emptyList()
        runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                Ticker24h(
                    symbol = t.optString("symbol"),
                    lastPrice = t.optDoubleOrNull("lastPrice"),
                    priceChangePercent = t.optDoubleOrNull("priceChangePercent"),
                    quoteVolume = t.optDoubleOrNull("quoteVolume")
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchPremiumIndexes(): List<PremiumIndex> = withContext(Dispatchers.IO) {
        val body = getJson("$BASE_FUTURES/fapi/v1/premiumIndex") ?: return@withContext emptyList()
        runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                PremiumIndex(
                    symbol = p.optString("symbol"),
                    markPrice = p.optDoubleOrNull("markPrice"),
                    indexPrice = p.optDoubleOrNull("indexPrice"),
                    lastFundingRate = p.optDoubleOrNull("lastFundingRate")
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchOpenInterest(symbol: String): Double? = withContext(Dispatchers.IO) {
        val body = getJson("$BASE_FUTURES/fapi/v1/openInterest?symbol=${urlEncode(symbol)}")
            ?: return@withContext null
        runCatching { JSONObject(body).optDoubleOrNull("openInterest") }.getOrNull()
    }

    suspend fun fetchKlines(symbol: String, interval: String, limit: Int = 200): List<Candle> =
        withContext(Dispatchers.IO) {
            val q = "?symbol=${urlEncode(symbol)}&interval=${urlEncode(interval)}&limit=$limit"
            val body = getJson("$BASE_FUTURES/fapi/v1/klines$q") ?: return@withContext emptyList()
            runCatching {
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    val row = arr.optJSONArray(i) ?: return@mapNotNull null
                    if (row.length() < 8) return@mapNotNull null
                    Candle(
                        openTimeUtcMs = row.optLongOrNull(0) ?: return@mapNotNull null,
                        open = row.optDoubleOrNull(1) ?: return@mapNotNull null,
                        high = row.optDoubleOrNull(2) ?: return@mapNotNull null,
                        low = row.optDoubleOrNull(3) ?: return@mapNotNull null,
                        close = row.optDoubleOrNull(4) ?: return@mapNotNull null,
                        volume = row.optDoubleOrNull(5) ?: return@mapNotNull null,
                        closeTimeUtcMs = row.optLongOrNull(6) ?: return@mapNotNull null,
                        quoteVolume = row.optDoubleOrNull(7) ?: 0.0
                    )
                }
            }.getOrDefault(emptyList())
        }

    suspend fun fetchDepth(symbol: String, limit: Int = 20): Depth? = withContext(Dispatchers.IO) {
        val q = "?symbol=${urlEncode(symbol)}&limit=$limit"
        val body = getJson("$BASE_FUTURES/fapi/v1/depth$q") ?: return@withContext null
        runCatching {
            val root = JSONObject(body)
            Depth(
                bids = parseLevels(root.optJSONArray("bids")),
                asks = parseLevels(root.optJSONArray("asks"))
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------- plumbing

    private fun parseLevels(arr: JSONArray?): List<Pair<Double, Double>> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val pair = arr.optJSONArray(i) ?: return@mapNotNull null
            val price = pair.optDoubleOrNull(0) ?: return@mapNotNull null
            val qty = pair.optDoubleOrNull(1) ?: return@mapNotNull null
            Pair(price, qty)
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || optIsNull(key)) return null
        return optDouble(key, Double.NaN).takeUnless { it.isNaN() }
    }

    private fun JSONArray.optDoubleOrNull(index: Int): Double? {
        val v = optDouble(index, Double.NaN)
        return v.takeUnless { it.isNaN() }
    }

    private fun JSONArray.optLongOrNull(index: Int): Long? {
        val v = optLong(index, Long.MIN_VALUE)
        return v.takeUnless { it == Long.MIN_VALUE }
    }

    private fun JSONObject.optIsNull(key: String): Boolean = opt(key) == JSONObject.NULL

    /** All failures (non-2xx, timeout, malformed URL) collapse to null; never throws. */
    internal fun getJson(urlSpec: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlSpec).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 7_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                useCaches = false
            }
            val code = connection.responseCode
            if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
