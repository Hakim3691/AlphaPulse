package com.aistudio.alphapulse.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phase 2 (Spec §4): CoinGlass Public Futures API client — mandatory derivatives
 * context feeds (funding, open interest, basis).
 *
 * CoinGlass response envelopes vary by endpoint generation:
 *   v3: {"code": "0", "msg": "success", "data": ...}
 *   v2: {"code": 0,  "msg": "success", "data": {...}}
 *   v4: {"code": "0", ..., "data": {"dataList": [...]}}
 * Parsing is therefore tolerant: unwrap `data` (and optional `dataList`), then accept
 * camelCase or snake_case field spellings. Any failure degrades to null/empty — the
 * screener treats missing CoinGlass coverage as "excluded" (Spec §4 strict inclusion).
 *
 * Optional API key: when present it is sent as `CG-API-KEY` (v3 header) with an
 * `access-key` query fallback for legacy endpoints. The key lives only in the process
 * (BuildConfig field via the secrets plugin); it is never persisted (Spec §3).
 */
object CoinGlassClient {

    const val BASE = "https://open-api.coinglass.com"

    /** Set from the secrets-managed COINGLASS_API_KEY (empty = anonymous, lower limits). */
    @Volatile
    var apiKey: String = ""

    data class DerivativesSnapshot(
        val fundingRate: Double?,
        val predictedFundingRate: Double?,
        val openInterestUsd: Double?,
        val basisAnnualizedPct: Double?
    )

    // ---------------------------------------------------------------- endpoints

    /** Funding rates for all symbols: /api/fundingRate/current (v3) with v2 fallback. */
    suspend fun fetchFundingRates(): Map<String, Pair<Double, Double?>> = withContext(Dispatchers.IO) {
        val direct = getJson("$BASE/api/fundingRate/current")?.let { body ->
            parseRateList(body, rateKeyCandidates = listOf("rate", "currentFundingRate", "fundingRate"))
        } ?: emptyMap()
        if (direct.isNotEmpty()) return@withContext direct
        // v2 fallback: /api/fundingRate/v2/current
        getJson("$BASE/api/fundingRate/v2/current")?.let { body ->
            parseRateList(body, rateKeyCandidates = listOf("rate", "currentFundingRate", "fundingRate"))
        } ?: emptyMap()
    }

    /** Open interest (USD) per symbol: /api/openInterest/v3/list (v4 shape tolerant). */
    suspend fun fetchOpenInterestUsd(): Map<String, Double> = withContext(Dispatchers.IO) {
        val body = getJson("$BASE/api/openInterest/v3/list?symbol=&exchange=Binance")
            ?: return@withContext emptyMap()
        parseOis(body)
    }

    /**
     * Basis (annualized %) per symbol: /api/futures/basis (best effort). Empty map on
     * absence — basis is only a secondary screener input, so degradation is safe.
     */
    suspend fun fetchBasis(): Map<String, Double> = withContext(Dispatchers.IO) {
        val body = getJson("$BASE/api/futures/basis?symbol=&interval=h1") ?: return@withContext emptyMap()
        parseBasis(body)
    }

    /**
     * Aggregated 24h liquidation history per coin (USD), best-effort.
     * Uses the CoinGlass futures aggregated-liq-history path for per-coin context.
     * Empty map on absence — liquidation context is advisory for evidence/pain copy.
     */
    suspend fun fetchLiquidationHistory24h(): Map<String, Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val body = getJson("$BASE/api/futures/liquidation/aggregated-history") ?: return@withContext emptyMap()
        parseLiquidationHistory24h(body)
    }

    // ---------------------------------------------------------------- tolerant parsers

    private fun parseRateList(body: String, rateKeyCandidates: List<String>): Map<String, Pair<Double, Double?>> {
        return runCatching {
            val rows = unwrapRows(body)
            val out = mutableMapOf<String, Pair<Double, Double?>>()
            (0 until rows.length()).forEach { i ->
                val row = rows.optJSONObject(i) ?: return@forEach
                val symbol = row.optStringOrNull("symbol")?.uppercase() ?: return@forEach
                val rate = rateKeyCandidates.firstNotNullOfOrNull { row.optDoubleOrNull(it) }
                if (rate != null) out[symbol] = Pair(rate, null)
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun parseOis(body: String): Map<String, Double> = runCatching {
        val rows = unwrapRows(body)
        val out = mutableMapOf<String, Double>()
        (0 until rows.length()).forEach { i ->
            val row = rows.optJSONObject(i) ?: return@forEach
            val symbol = row.optStringOrNull("symbol")?.uppercase() ?: return@forEach
            val oi = listOf("openInterest", "openInterestUsd", "open_interest_usd", "oiUsd")
                .firstNotNullOfOrNull { row.optDoubleOrNull(it) }
                ?: row.optDoubleOrNull("openInterestAmount")?.let { amount ->
                    // Amounts in coin units need a price to become USD; skip if missing.
                    row.optDoubleOrNull("price")?.let { amount * it }
                }
            if (oi != null) out[symbol] = oi
        }
        out
    }.getOrDefault(emptyMap())

    private fun parseBasis(body: String): Map<String, Double> = runCatching {
        val rows = unwrapRows(body)
        val out = mutableMapOf<String, Double>()
        (0 until rows.length()).forEach { i ->
            val row = rows.optJSONObject(i) ?: return@forEach
            val symbol = row.optStringOrNull("symbol")?.uppercase() ?: return@forEach
            val basis = listOf("annualizedBasis", "basis", "annualizedBasisRate")
                .firstNotNullOfOrNull { row.optDoubleOrNull(it) }
            if (basis != null) out[symbol] = basis
        }
        out
    }.getOrDefault(emptyMap())

    private fun parseLiquidationHistory24h(body: String): Map<String, Pair<Double, Double>> = runCatching {
        val rows = unwrapRows(body)
        val bySymbol = mutableMapOf<String, MutableList<Pair<Long, Double>>>()
        (0 until rows.length()).forEach { i ->
            val row = rows.optJSONObject(i) ?: return@forEach
            val symbol = row.optStringOrNull("symbol")?.uppercase() ?: return@forEach
            val longLiq = row.optDoubleOrNull("aggregatedLongLiquidationUsd")
                ?: row.optDoubleOrNull("longAmount")
            val shortLiq = row.optDoubleOrNull("aggregatedShortLiquidationUsd")
                ?: row.optDoubleOrNull("shortAmount")
            val ts = (row.optDoubleOrNull("time") ?: row.optLongOrNull("time") ?: 0.0).toLong()
            if (longLiq != null || shortLiq != null) {
                bySymbol.getOrPut(symbol) { mutableListOf() }.add(ts to (longLiq ?: 0.0))
                bySymbol.getOrPut(symbol) { mutableListOf() }.add(ts to (shortLiq ?: 0.0))
            }
        }
        val out = mutableMapOf<String, Pair<Double, Double>>()
        bySymbol.forEach { (sym, samples) ->
            val longSum = samples.filter { it.second > 0 }.sumOf { it.second }
            val shortSum = samples.filter { it.second < 0 }.sumOf { it.second }
            if (longSum != 0.0 || shortSum != 0.0) {
                out[sym] = longSum to -shortSum
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** Unwraps the v3/v2/v4 envelope into a JSONArray of rows. */
    private fun unwrapRows(body: String): JSONArray {
        val root = JSONObject(body)
        val code = root.opt("code")
        val codeOk = when (code) {
            is Number -> code.toInt() == 0
            is String -> code == "0"
            else -> true // some endpoints omit code on success
        }
        if (!codeOk) return JSONArray()
        val data = root.opt("data") ?: return JSONArray()
        return when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("dataList") ?: JSONArray()
            else -> JSONArray()
        }
    }

    // ---------------------------------------------------------------- plumbing

    internal fun getJson(urlSpec: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val target = if (apiKey.isNotBlank() && !urlSpec.contains("access-key=")) {
                "$urlSpec${if (urlSpec.contains('?')) "&" else "?"}access-key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}"
            } else urlSpec
            connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 7_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("CG-API-KEY", apiKey)
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

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
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
