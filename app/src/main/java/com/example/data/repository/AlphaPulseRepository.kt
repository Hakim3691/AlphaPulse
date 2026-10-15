package com.example.data.repository

import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.PredictionEntity
import com.example.data.db.ScreenedCoinEntity
import com.example.data.model.*
import com.example.data.network.BinanceWebSocketManager
import com.example.data.network.ResilientBinanceClient
import com.example.domain.quant.KlineData
import com.example.domain.quant.QuantMath
import com.example.domain.quant.QuantNarrativeAgent
import com.example.service.AlphaPulseService
import com.example.ui.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class AlphaPulseRepository(
    private val client: ResilientBinanceClient,
    private val database: AppDatabase,
    private val cacheDir: File? = null,
    val wsManager: BinanceWebSocketManager = BinanceWebSocketManager()
) {
    val allPredictionsFlow: Flow<List<PredictionEntity>> = database.predictionDao().getAllPredictionsFlow()
    fun getPredictionsBySymbolFlow(symbol: String): Flow<List<PredictionEntity>> =
        database.predictionDao().getPredictionsBySymbolFlow(symbol)

    // Per-asset rolling state
    private val kurtosisHistory = mutableListOf<Double>()
    private val state1Returns = mutableListOf<Double>()
    private val state0Returns = mutableListOf<Double>()
    private var prevDepthTotal = 0.0
    private var prevDepthTimestamp = 0L
    private var lambdaDecay = 0.035
    private var initializedWeightsForSymbol = ""

    val HORIZONS = listOf(
        HorizonConfig("1m", "1 Minute", 1, 60 * 1000L, "Tri-Factor"),
        HorizonConfig("5m", "5 Minutes", 5, 5 * 60 * 1000L, "Tri-Factor"),
        HorizonConfig("15m", "15 Minutes", 15, 15 * 60 * 1000L, "Tri-Factor"),
        HorizonConfig("30m", "30 Minutes", 30, 30 * 60 * 1000L, "Tri-Factor"),
        HorizonConfig("1h", "1 Hour", 60, 60 * 60 * 1000L, "Tri-Factor")
    )

    suspend fun runInstitutionalScreening(
        onProgressUpdate: (stepTitle: String, subtitle: String, progress: Float) -> Unit
    ): List<ScreenedCoin> = withContext(Dispatchers.IO) {
        onProgressUpdate("Step 1: Institutional 4-Filter Screen", "Retrieving global live ticker universe...", 0.05f)

        val tickerArr = try {
            client.getJsonArray("/fapi/v1/ticker/24hr")
        } catch (e: Exception) {
            Log.e("Repository", "Failed to fetch fapi 24hr tickers: ${e.message}, falling back to spot mirror")
            try {
                client.getJsonArray("/api/v3/ticker/24hr")
            } catch (e2: Exception) {
                Log.e("Repository", "Failed to fetch spot 24hr tickers: ${e2.message}")
                JSONArray()
            }
        }

        val activePairs = mutableListOf<Map<String, Any>>()
        for (i in 0 until tickerArr.length()) {
            val t = tickerArr.getJSONObject(i)
            val symbol = t.getString("symbol")
            val quoteVol = t.optDouble("quoteVolume", 0.0)
            if (symbol.endsWith("USDT") && quoteVol > 1000000.0) {
                activePairs.add(
                    mapOf(
                        "symbol" to symbol,
                        "lastPrice" to t.optDouble("lastPrice", 0.0),
                        "highPrice" to t.optDouble("highPrice", 0.0),
                        "lowPrice" to t.optDouble("lowPrice", 0.0),
                        "openPrice" to t.optDouble("openPrice", 0.0),
                        "quoteVolume" to quoteVol,
                        "priceChangePercent" to t.optDouble("priceChangePercent", 0.0)
                    )
                )
            }
        }

        onProgressUpdate("Step 1: Institutional 4-Filter Screen", "Screening active universe across ${activePairs.size} pairs...", 0.08f)

        val step1PassedCoins = mutableListOf<ScreenedCoin>()
        val batches = activePairs.chunked(15)

        for ((bIdx, batch) in batches.withIndex()) {
            val batchResults = batch.map { coin ->
                async {
                    val sym = coin["symbol"] as String
                    val lastP = coin["lastPrice"] as Double
                    val highP = coin["highPrice"] as Double
                    val lowP = coin["lowPrice"] as Double
                    val openP = coin["openPrice"] as Double
                    val changePct = coin["priceChangePercent"] as Double

                    try {
                        val klinesArr = client.getJsonArray("/fapi/v1/klines?symbol=$sym&interval=1d&limit=10")
                        if (klinesArr.length() > 0) {
                            val oiData = client.fetchHistoricalOi(sym, 10)

                            var liveOiVal: Double? = null
                            try {
                                val bLiveOi = client.getJsonObject("/fapi/v1/openInterest?symbol=$sym")
                                val rawOi = bLiveOi.optDouble("openInterest", 0.0)
                                if (rawOi > 0) {
                                    liveOiVal = rawOi * lastP
                                }
                            } catch (_: Exception) {}
                            if (liveOiVal == null) {
                                val cg = client.fetchCoinglassOI(sym)
                                if (cg != null && cg > 0) {
                                    liveOiVal = cg * lastP
                                }
                            }
                            val exactLiveOi = liveOiVal ?: 1.0

                            var daysPassedAll = 0

                            // Historical days
                            for (i in 0 until klinesArr.length() - 1) {
                                val k = klinesArr.getJSONArray(i)
                                val o = k.getDouble(1)
                                val h = k.getDouble(2)
                                val l = k.getDouble(3)
                                val c = k.getDouble(4)
                                val qVol = k.getDouble(7)
                                val takerQVol = k.getDouble(10)
                                val takerBuyRatio = if (qVol > 0) (takerQVol / qVol) * 100.0 else 0.0
                                val range = max(1e-6, h - l)
                                val displacement = abs(c - o) / range

                                var oiVal = 0.0
                                if (oiData.isNotEmpty()) {
                                    val oiDayIndex = oiData.size - 1 - (klinesArr.length() - 1 - i)
                                    if (oiDayIndex in 0 until oiData.size) {
                                        oiVal = oiData[oiDayIndex]
                                    }
                                }

                                val passF1 = qVol >= 5000000.0
                                val passF2 = takerBuyRatio >= 50.0
                                val passF3 = if (oiVal > 0) qVol > oiVal else false
                                val passF4 = displacement >= 0.40

                                if (passF1 && passF2 && passF3 && passF4) {
                                    daysPassedAll++
                                }
                            }

                            // Today candle
                            val todayK = klinesArr.getJSONArray(klinesArr.length() - 1)
                            val todayO = todayK.getDouble(1)
                            val todayH = todayK.getDouble(2)
                            val todayL = todayK.getDouble(3)
                            val todayC = todayK.getDouble(4)
                            val todayQuoteVol = todayK.getDouble(7)
                            val todayTakerQuoteVol = todayK.getDouble(10)
                            val todayTakerBuyRatio = if (todayQuoteVol > 0) (todayTakerQuoteVol / todayQuoteVol) * 100.0 else 0.0
                            val todayRange = max(1e-6, todayH - todayL)
                            val todayDisplacement = abs(todayC - todayO) / todayRange
                            val volToOiRatio = todayQuoteVol / exactLiveOi

                            val passTodayF1 = todayQuoteVol >= 5000000.0
                            val passTodayF2 = todayTakerBuyRatio >= 50.0
                            val passTodayF3 = volToOiRatio > 1.0
                            val passTodayF4 = todayDisplacement >= 0.40

                            val passedTodayAll = passTodayF1 && passTodayF2 && passTodayF3 && passTodayF4
                            if (passedTodayAll) daysPassedAll++

                            val passed10DRule = daysPassedAll > 0

                            if (passedTodayAll || passed10DRule) {
                                ScreenedCoin(
                                    symbol = sym,
                                    lastPrice = lastP,
                                    highPrice = highP,
                                    lowPrice = lowP,
                                    openPrice = openP,
                                    volume = todayQuoteVol,
                                    realTakerBuyRatio = todayTakerBuyRatio,
                                    displacement = todayDisplacement,
                                    exactOI = exactLiveOi,
                                    volToOiRatio = volToOiRatio,
                                    priceChangePercent = changePct,
                                    calmarRatio = 0.0,
                                    sharpeRatio = 0.0,
                                    annualizedVol = 0.0,
                                    maxDrawdown = 0.0,
                                    daysPassedAll = daysPassedAll,
                                    passedTodayAll = passedTodayAll,
                                    passed10DRule = passed10DRule,
                                    filter1 = FilterBadgeData(true, if (passed10DRule && !passedTodayAll) "Hist ($daysPassedAll/10)" else "$${(todayQuoteVol / 1000000).toInt()}M Vol"),
                                    filter2 = FilterBadgeData(true, if (passed10DRule && !passedTodayAll) "10D Rule" else "${String.format(Locale.US, "%.1f", todayTakerBuyRatio)}%"),
                                    filter3 = FilterBadgeData(true, if (passed10DRule && !passedTodayAll) "10D Rule" else "${String.format(Locale.US, "%.2f", volToOiRatio)}x"),
                                    filter4 = FilterBadgeData(true, if (passed10DRule && !passedTodayAll) "10D Rule" else "${String.format(Locale.US, "%.1f", todayDisplacement * 100)}% Ext")
                                )
                            } else null
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()

            step1PassedCoins.addAll(batchResults.filterNotNull())
            val processedCount = min(activePairs.size, (bIdx + 1) * 15)
            val prog = 0.08f + ((bIdx + 1).toFloat() / batches.size) * 0.52f
            onProgressUpdate(
                "Step 1: Institutional 4-Filter Screen",
                "Screened $processedCount/${activePairs.size} pairs (${step1PassedCoins.size} verified)...",
                prog
            )
        }

        // STEP 2: 1-Year Calmar Ratio Filter (Batches of 15)
        onProgressUpdate("Step 2: 1-Year Calmar Filter", "Analyzing 365-day series on ${step1PassedCoins.size} candidates...", 0.65f)

        val step2PassedCoins = mutableListOf<Pair<ScreenedCoin, List<Double>>>()
        val step2Batches = step1PassedCoins.chunked(15)

        for ((bIdx, batch) in step2Batches.withIndex()) {
            val batchResults = batch.map { coin ->
                async {
                    try {
                        val dailyArr = client.getJsonArray("/fapi/v1/klines?symbol=${coin.symbol}&interval=1d&limit=365")
                        if (dailyArr.length() >= 20) {
                            val closes = mutableListOf<Double>()
                            for (i in 0 until dailyArr.length()) {
                                closes.add(dailyArr.getJSONArray(i).getDouble(4))
                            }
                            val n = closes.size
                            val totalReturn = (closes.last() - closes.first()) / closes.first()
                            val annualizedReturn = totalReturn * (365.0 / n)

                            var peak = closes.first()
                            var maxDd = 0.0001
                            for (price in closes) {
                                if (price > peak) peak = price
                                val dd = (peak - price) / peak
                                if (dd > maxDd) maxDd = dd
                            }

                            val calmar = annualizedReturn / maxDd
                            if (calmar > 0.0) {
                                Pair(
                                    coin.copy(calmarRatio = calmar, maxDrawdown = maxDd),
                                    closes
                                )
                            } else null
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()

            step2PassedCoins.addAll(batchResults.filterNotNull())
            val prog = 0.65f + ((bIdx + 1).toFloat() / step2Batches.size.coerceAtLeast(1)) * 0.20f
            onProgressUpdate("Step 2: 1-Year Calmar Filter", "Evaluated Calmar ratio for ${step2PassedCoins.size} coins...", prog)
        }

        // STEP 3: 1-Year Sharpe Filter (Dynamic Rf from Perpetual Funding Rate)
        val dynamicRf = try {
            client.fetchFundingRate("BTCUSDT")
        } catch (_: Exception) { 0.04 }
        val rfPercentStr = String.format(Locale.US, "%.2f%%", dynamicRf * 100.0)
        onProgressUpdate("Step 3: 1-Year Sharpe Filter", "Ranking risk-adjusted Sharpe performance (Rf = $rfPercentStr)...", 0.88f)

        val finalRankedCoins = mutableListOf<ScreenedCoin>()
        for ((coin, closes) in step2PassedCoins) {
            val dailyReturns = mutableListOf<Double>()
            for (i in 1 until closes.size) {
                dailyReturns.add((closes[i] - closes[i - 1]) / closes[i - 1])
            }
            if (dailyReturns.size > 2) {
                val mean = dailyReturns.average()
                val variance = dailyReturns.map { (it - mean).pow(2) }.sum() / (dailyReturns.size - 1)
                val dailyStd = sqrt(variance)
                val annVol = dailyStd * sqrt(365.0)
                val annReturn = coin.calmarRatio * coin.maxDrawdown
                val sharpe = if (annVol > 0) (annReturn - dynamicRf) / annVol else 0.0

                if (sharpe > 0.0) {
                    finalRankedCoins.add(
                        coin.copy(
                            sharpeRatio = sharpe,
                            annualizedVol = annVol
                        )
                    )
                }
            }
        }

        finalRankedCoins.sortByDescending { it.sharpeRatio }

        // Automatically generate and save 4-tab universe CSV audit file (strict 5-decimal formatting)
        try {
            val csv4Tab = generate4TabUniverseCsv(activePairs, step1PassedCoins, step2PassedCoins, finalRankedCoins)
            cacheDir?.let { dir ->
                val auditFile = File(dir, "AlphaPulse_Filtered_Universe.csv")
                auditFile.writeText(csv4Tab)
                val timestampedFile = File(dir, "AlphaPulse_Filtered_Universe_${System.currentTimeMillis()}.csv")
                timestampedFile.writeText(csv4Tab)
                Log.d("AlphaPulseRepository", "Auto-saved 4-tab universe CSV to ${auditFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.d("AlphaPulseRepository", "Error saving 4-tab universe CSV: ${e.message}")
        }

        onProgressUpdate("Institutional Screening Completed", "Universe ready with ${finalRankedCoins.size} verified assets", 1.0f)

        // Cache in Room
        val entities = finalRankedCoins.mapIndexed { rank, c ->
            ScreenedCoinEntity(
                symbol = c.symbol,
                lastPrice = c.lastPrice,
                highPrice = c.highPrice,
                lowPrice = c.lowPrice,
                openPrice = c.openPrice,
                volume = c.volume,
                realTakerBuyRatio = c.realTakerBuyRatio,
                displacement = c.displacement,
                exactOI = c.exactOI,
                volToOiRatio = c.volToOiRatio,
                priceChangePercent = c.priceChangePercent,
                calmarRatio = c.calmarRatio,
                sharpeRatio = c.sharpeRatio,
                annualizedVol = c.annualizedVol,
                maxDrawdown = c.maxDrawdown,
                daysPassedAll = c.daysPassedAll,
                passedTodayAll = c.passedTodayAll,
                passed10DRule = c.passed10DRule,
                filter1Passed = c.filter1.passed,
                filter1Val = c.filter1.valueStr,
                filter2Passed = c.filter2.passed,
                filter2Val = c.filter2.valueStr,
                filter3Passed = c.filter3.passed,
                filter3Val = c.filter3.valueStr,
                filter4Passed = c.filter4.passed,
                filter4Val = c.filter4.valueStr,
                rank = rank + 1
            )
        }
        if (finalRankedCoins.isNotEmpty()) {
            database.screenedCoinDao().clearAll()
            database.screenedCoinDao().insertAll(entities)
            finalRankedCoins
        } else {
            val cached = loadCachedScreenedCoins()
            if (cached.isNotEmpty()) {
                Log.d("AlphaPulseRepository", "Preserved and loaded ${cached.size} cached coins from Room")
                cached
            } else {
                finalRankedCoins
            }
        }
    }

    suspend fun loadCachedScreenedCoins(): List<ScreenedCoin> = withContext(Dispatchers.IO) {
        try {
            val cached = database.screenedCoinDao().getAllScreenedCoins()
            cached.map { entity ->
                ScreenedCoin(
                    symbol = entity.symbol,
                    lastPrice = entity.lastPrice,
                    highPrice = entity.highPrice,
                    lowPrice = entity.lowPrice,
                    openPrice = entity.openPrice,
                    volume = entity.volume,
                    realTakerBuyRatio = entity.realTakerBuyRatio,
                    displacement = entity.displacement,
                    exactOI = entity.exactOI,
                    volToOiRatio = entity.volToOiRatio,
                    priceChangePercent = entity.priceChangePercent,
                    calmarRatio = entity.calmarRatio,
                    sharpeRatio = entity.sharpeRatio,
                    annualizedVol = entity.annualizedVol,
                    maxDrawdown = entity.maxDrawdown,
                    daysPassedAll = entity.daysPassedAll,
                    passedTodayAll = entity.passedTodayAll,
                    passed10DRule = entity.passed10DRule,
                    filter1 = FilterBadgeData(entity.filter1Passed, entity.filter1Val),
                    filter2 = FilterBadgeData(entity.filter2Passed, entity.filter2Val),
                    filter3 = FilterBadgeData(entity.filter3Passed, entity.filter3Val),
                    filter4 = FilterBadgeData(entity.filter4Passed, entity.filter4Val)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun executeAssetEvaluation(
        symbol: String,
        modelState: ModelState
    ): AssetEvaluationResult = withContext(Dispatchers.IO) {
        // Reset rolling memory if asset switched
        if (initializedWeightsForSymbol != symbol) {
            kurtosisHistory.clear()
            state1Returns.clear()
            state0Returns.clear()
            prevDepthTotal = 0.0
            prevDepthTimestamp = 0L
            lambdaDecay = 0.035
            initializedWeightsForSymbol = symbol
        }

        // Connect WebSocket depth and aggTrade streams
        wsManager.startStreaming(symbol)

        // Live 24hr ticker REST snapshot
        var currentSpot = 0.0
        var spotChange = 0.0
        var high24h = 0.0
        var low24h = 0.0
        try {
            val tickerObj = client.getJsonObject("/fapi/v1/ticker/24hr?symbol=$symbol")
            currentSpot = tickerObj.getDouble("lastPrice")
            spotChange = tickerObj.getDouble("priceChangePercent")
            high24h = tickerObj.getDouble("highPrice")
            low24h = tickerObj.getDouble("lowPrice")
        } catch (_: Exception) {}

        // Agg trades empirical shock
        var empiricalShock = 0.0
        try {
            val trades = client.fetchAggTrades(symbol, 500)
            if (trades.isNotEmpty()) {
                val idx95 = (trades.size * 0.95).toInt().coerceIn(0, trades.size - 1)
                empiricalShock = trades[idx95]
            }
        } catch (_: Exception) {}

        // Depth snapshot & Liquidity pain path
        var depthSnapshot: DepthSnapshot? = null
        var ltfPathData = LiquidityPainPathResult(0.0, 1.0, "L2 Proxy")
        var netImbalanceUsdt = 0.0
        var bidRatioPct = 50.0

        try {
            depthSnapshot = client.fetchDepth(symbol, 50)
            if (depthSnapshot.bids.isNotEmpty() && depthSnapshot.asks.isNotEmpty()) {
                val tiers = min(20, min(depthSnapshot.bids.size, depthSnapshot.asks.size))
                var bidNotional = 0.0
                var askNotional = 0.0
                for (i in 0 until tiers) {
                    bidNotional += depthSnapshot.bids[i].price * depthSnapshot.bids[i].quantity
                    askNotional += depthSnapshot.asks[i].price * depthSnapshot.asks[i].quantity
                }
                val totalDepth = bidNotional + askNotional
                netImbalanceUsdt = bidNotional - askNotional
                bidRatioPct = if (totalDepth > 0) (bidNotional / totalDepth) * 100.0 else 50.0

                // Adaptive lambda turnover
                val now = System.currentTimeMillis()
                if (prevDepthTotal > 0 && now > prevDepthTimestamp) {
                    val dtMinutes = max(0.05, (now - prevDepthTimestamp) / 60000.0)
                    val turnover = abs(totalDepth - prevDepthTotal) / prevDepthTotal
                    val estLambda = (turnover / dtMinutes).coerceIn(0.01, 0.15)
                    lambdaDecay = (lambdaDecay * 0.8) + (estLambda * 0.2)
                }
                prevDepthTotal = totalDepth
                prevDepthTimestamp = now

                ltfPathData = QuantMath.computeLiquidityPainPath(
                    depthSnapshot.bids,
                    depthSnapshot.asks,
                    currentSpot,
                    empiricalShock
                )
            }

            // Primary route: Liquidation Heatmap API
            val heatmapPair = client.fetchLiquidityHeatmap(symbol, currentSpot)
            if (heatmapPair != null) {
                ltfPathData = LiquidityPainPathResult(heatmapPair.first, heatmapPair.second, "API")
            }
        } catch (_: Exception) {}

        // Seamless WebSocket Telemetry Override if stream is active
        val liveWs = wsManager.telemetryFlow.value
        if (liveWs != null && liveWs.symbol.equals(symbol, ignoreCase = true)) {
            if (liveWs.spotPrice > 0.0) currentSpot = liveWs.spotPrice
            if (liveWs.bids.isNotEmpty() && liveWs.asks.isNotEmpty()) {
                depthSnapshot = DepthSnapshot(bids = liveWs.bids, asks = liveWs.asks)
                netImbalanceUsdt = liveWs.netImbalanceUsdt
                bidRatioPct = liveWs.bidRatioPct
                if (liveWs.empiricalShock > 0.0) empiricalShock = liveWs.empiricalShock
                lambdaDecay = liveWs.lambdaTurnover
                ltfPathData = QuantMath.computeLiquidityPainPath(
                    depthSnapshot.bids,
                    depthSnapshot.asks,
                    currentSpot,
                    empiricalShock
                )
            }
        }

        // 40 1-minute Klines
        val klines = mutableListOf<KlineData>()
        try {
            val klinesArr = client.getJsonArray("/fapi/v1/klines?symbol=$symbol&interval=1m&limit=40")
            for (i in 0 until klinesArr.length()) {
                val k = klinesArr.getJSONArray(i)
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
        } catch (_: Exception) {}

        if (klines.isNotEmpty() && currentSpot <= 0.0) {
            currentSpot = klines.last().close
        }

        // Base Volatility
        val logReturns = mutableListOf<Double>()
        for (i in 1 until klines.size) {
            logReturns.add(ln(klines[i].close / klines[i - 1].close))
        }
        val baseVariance = if (logReturns.isNotEmpty()) logReturns.map { it.pow(2) }.average() else 0.0001
        val baseVol = sqrt(baseVariance)

        // Initial RMSE calibration if not calibrated yet
        if (modelState.mode == "Baseline Init" && klines.size >= 25) {
            QuantMath.calibrateInitialWeightsFromHistory(klines, baseVol, ltfPathData.muPath, modelState)
        }

        // Audit maturing predictions in Room & Online SGD
        auditAndSelfTrain(symbol, currentSpot, modelState)

        // Microstructure & Technical indicators
        val closes = klines.map { it.close }
        val rsi = QuantMath.computeRSI(closes, 14)
        val ema9 = QuantMath.computeEMA(closes, 9)
        val ema21 = QuantMath.computeEMA(closes, 21)
        val macdSignal = if (ema9.isNotEmpty() && ema21.isNotEmpty() && ema9.last() >= ema21.last()) "Bullish Drift" else "Bearish Drift"

        val mtfMetrics = QuantMath.evaluateMarkovRegimeGARCH(logReturns, kurtosisHistory, state1Returns, state0Returns)
        val htfMetrics = QuantMath.evaluateTemporalAttention(klines, baseVol)
        val alphaMetrics = QuantMath.computeConfluenceAlpha(klines, currentSpot, depthSnapshot, baseVol, mtfMetrics.regime.contains("Burst"))
        val vp = QuantMath.computeVolumeProfileAndVWAP(klines, currentSpot, mtfMetrics.regime.contains("Burst"))

        // Generate forecasts for 5 horizons with discrete horizon pain paths & candle boundary phase-locking
        val now = System.currentTimeMillis()
        val forecastTargets = mutableListOf<ForecastTarget>()
        val existingPredictions = database.predictionDao().getPredictionsBySymbol(symbol)

        for (h in HORIZONS) {
            val muKinematic = when {
                h.minutes <= 5 -> 0.0
                h.minutes <= 30 -> mtfMetrics.muKinematic
                else -> htfMetrics.muKinematic
            }

            // Horizon-coupled liquidity pain path
            val horizonPainResult = QuantMath.computeHorizonPainPath(
                horizonMinutes = h.minutes,
                bids = depthSnapshot?.bids ?: emptyList(),
                asks = depthSnapshot?.asks ?: emptyList(),
                currentSpot = currentSpot,
                empiricalShock = empiricalShock,
                vp = vp,
                klines = klines
            )

            val muPath = horizonPainResult.muPath * exp(-lambdaDecay * (h.minutes - 1))
            val muAlpha = alphaMetrics.muAlpha

            val effectiveVol = when {
                h.minutes <= 5 -> baseVol * modelState.volMultiplier * horizonPainResult.spreadSkew
                h.minutes <= 30 -> baseVol * modelState.volMultiplier * mtfMetrics.regimeVolScale
                else -> baseVol * modelState.volMultiplier
            }

            val upperSpreadMultiplier = if (h.minutes > 30) htfMetrics.upperConeSkew else 1.0
            val lowerSpreadMultiplier = if (h.minutes > 30) htfMetrics.lowerConeSkew else 1.0

            val combinedDrift = (muKinematic * modelState.weightK) +
                    (muPath * modelState.weightP) +
                    (muAlpha * modelState.weightAlpha) +
                    modelState.driftBias

            val dt = h.minutes.toDouble()
            val expectedReturn = combinedDrift * dt
            val predPrice = currentSpot * exp(expectedReturn)

            val sigmaSpread = effectiveVol * sqrt(dt)
            val upperPrice = currentSpot * exp(expectedReturn + (sigmaSpread * upperSpreadMultiplier))
            val lowerPrice = currentSpot * exp(expectedReturn - (sigmaSpread * lowerSpreadMultiplier))

            val deltaPct = ((predPrice - currentSpot) / currentSpot) * 100.0

            val target = ForecastTarget(
                horizonId = h.id,
                label = h.label,
                minutes = h.minutes,
                modelType = h.modelType,
                predictedPrice = predPrice,
                upperPrice = upperPrice,
                lowerPrice = lowerPrice,
                deltaPct = deltaPct,
                isBullish = deltaPct >= 0.0
            )
            forecastTargets.add(target)

            // Phase-locked target timestamp to candle boundary (:00.000s)
            val targetTimestamp = ((now / 60000L) + h.minutes) * 60000L

            // Universal 20-second pre-expiry throttling
            val throttleWindow = h.ms - 20000L
            val hasRecentPending = existingPredictions.any {
                it.horizonId == h.id && !it.settled && (now - it.issuedTimestamp) < throttleWindow
            }

            if (!hasRecentPending && currentSpot > 0.0) {
                val newEntity = PredictionEntity(
                    id = "$symbol-${h.id}-$now",
                    symbol = symbol,
                    horizonId = h.id,
                    horizonLabel = h.label,
                    modelType = h.modelType,
                    minutes = h.minutes,
                    issuedTimestamp = now,
                    targetTimestamp = targetTimestamp,
                    basePrice = currentSpot,
                    predictedPrice = predPrice,
                    upperBound = upperPrice,
                    lowerBound = lowerPrice,
                    muK = muKinematic,
                    muP = muPath,
                    muAlpha = muAlpha,
                    settled = false
                )
                database.predictionDao().insert(newEntity)
            }
        }

        // Generate Chart Points (historical + 5 forward horizon steps)
        val chartPoints = mutableListOf<ChartPoint>()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        for (k in klines) {
            chartPoints.add(
                ChartPoint(
                    timeLabel = timeFmt.format(Date(k.openTime)),
                    realizedPrice = k.close,
                    forecastPrice = null,
                    upperCone = null,
                    lowerCone = null
                )
            )
        }

        // Bridge anchor at currentSpot
        if (chartPoints.isNotEmpty()) {
            val last = chartPoints.last()
            chartPoints[chartPoints.size - 1] = last.copy(
                forecastPrice = currentSpot,
                upperCone = currentSpot,
                lowerCone = currentSpot
            )
        }

        for (target in forecastTargets) {
            chartPoints.add(
                ChartPoint(
                    timeLabel = "+${target.minutes}m",
                    realizedPrice = null,
                    forecastPrice = target.predictedPrice,
                    upperCone = target.upperPrice,
                    lowerCone = target.lowerPrice
                )
            )
        }

        // Quantitative narrative executive brief
        val settledForSymbol = existingPredictions.filter { it.settled }
        val coneHitRate = if (settledForSymbol.isNotEmpty()) {
            (settledForSymbol.count { it.inCone == true }.toDouble() / settledForSymbol.size) * 100.0
        } else null

        val executiveBrief = QuantNarrativeAgent.generateExecutiveBrief(
            symbol = symbol,
            spotPrice = currentSpot,
            modelState = modelState,
            regime = mtfMetrics.regime,
            kurtosis = mtfMetrics.kurtosis,
            kurtosisCutoff = mtfMetrics.cutoff,
            netImbalanceUsdt = netImbalanceUsdt,
            bidRatioPct = bidRatioPct,
            vwap = alphaMetrics.vwap,
            poc = alphaMetrics.poc,
            vah = alphaMetrics.vah,
            valPrice = alphaMetrics.valPrice,
            rotationRuleTriggered = alphaMetrics.rotationRuleTriggered,
            rotationTarget = alphaMetrics.rotationTarget,
            rsi = rsi,
            macdSignal = macdSignal,
            coneHitRatePct = coneHitRate
        )

        // Live notification telemetry dispatch for 24/7 background service
        val targetCountdownSeconds = if (forecastTargets.isNotEmpty()) {
            val first = forecastTargets.first()
            val diff = max(0L, (((now / 60000L) + first.minutes) * 60000L) - now)
            (diff / 1000).toInt()
        } else 60
        AlphaPulseService.updateTelemetry(
            symbol = symbol,
            spotPrice = currentSpot,
            secondsToTarget = targetCountdownSeconds,
            regime = mtfMetrics.regime
        )

        AssetEvaluationResult(
            spotPrice = currentSpot,
            spotChange = spotChange,
            high24h = high24h,
            low24h = low24h,
            rsi = rsi,
            macdSignal = macdSignal,
            markovRegime = mtfMetrics,
            netImbalanceUsdt = netImbalanceUsdt,
            bidRatioPct = bidRatioPct,
            heatmapSource = ltfPathData.source,
            forecastTargets = forecastTargets,
            chartPoints = chartPoints,
            alphaMetrics = alphaMetrics,
            executiveBrief = executiveBrief
        )
    }

    private suspend fun auditAndSelfTrain(symbol: String, currentSpot: Double, modelState: ModelState) {
        val now = System.currentTimeMillis()
        val pendingList = database.predictionDao().getPredictionsBySymbol(symbol).filter { !it.settled && now >= it.targetTimestamp }

        for (item in pendingList) {
            val errorDollar = currentSpot - item.predictedPrice
            val errorPct = (errorDollar / currentSpot) * 100.0
            val inCone = currentSpot >= item.lowerBound && currentSpot <= item.upperBound

            val muRealized = ln(currentSpot / item.basePrice) / max(1, item.minutes)
            val muPredicted = (item.muK * modelState.weightK) +
                    (item.muP * modelState.weightP) +
                    (item.muAlpha * modelState.weightAlpha) +
                    modelState.driftBias
            val errorMu = muRealized - muPredicted

            if (!errorMu.isNaN() && !errorMu.isInfinite()) {
                val gradientMultiplier = 100000.0
                modelState.weightK += modelState.learningRate * errorMu * item.muK * gradientMultiplier
                modelState.weightP += modelState.learningRate * errorMu * item.muP * gradientMultiplier
                modelState.weightAlpha += modelState.learningRate * errorMu * item.muAlpha * gradientMultiplier
                modelState.driftBias += modelState.learningRate * errorMu

                val totalW = abs(modelState.weightK) + abs(modelState.weightP) + abs(modelState.weightAlpha)
                if (totalW > 0) {
                    modelState.weightK = abs(modelState.weightK) / totalW
                    modelState.weightP = abs(modelState.weightP) / totalW
                    modelState.weightAlpha = abs(modelState.weightAlpha) / totalW
                    modelState.mode = "Live SGD Active"
                }
            }

            val learningAction = if (!inCone) {
                modelState.volMultiplier = min(3.0, modelState.volMultiplier * 1.05)
                "Breach: SGD Tri-Update"
            } else {
                modelState.volMultiplier = max(0.5, modelState.volMultiplier * 0.995)
                "Preserved: SGD Refined"
            }

            val updated = item.copy(
                settled = true,
                realizedPrice = currentSpot,
                errorDollar = errorDollar,
                errorPct = errorPct,
                inCone = inCone,
                learningAction = learningAction
            )
            database.predictionDao().update(updated)
        }
    }

    suspend fun clearLedger() {
        database.predictionDao().clearAll()
    }

    suspend fun exportAuditLedgerCsv(): String = withContext(Dispatchers.IO) {
        val all = database.predictionDao().getAllPredictions()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("Prediction ID,Symbol,Horizon,Model Core,Issued Time,Target Time,Predicted Price,Realized Price,Error %,Cone Status,Learning Action\n")
        for (item in all) {
            val realizedStr = if (item.settled && item.realizedPrice != null) Formatters.formatRawPrice5Dec(item.realizedPrice) else ""
            val errorStr = if (item.settled && item.errorPct != null) String.format(Locale.US, "%.2f", item.errorPct) else ""
            val coneStr = if (item.settled) (if (item.inCone == true) "HIT" else "BREACH") else "PENDING"
            sb.append("\"${item.id}\",\"${item.symbol}\",\"${item.horizonLabel}\",\"${item.modelType}\",\"${timeFmt.format(Date(item.issuedTimestamp))}\",\"${timeFmt.format(Date(item.targetTimestamp))}\",${Formatters.formatRawPrice5Dec(item.predictedPrice)},$realizedStr,$errorStr,\"$coneStr\",\"${item.learningAction}\"\n")
        }
        sb.toString()
    }

    fun generate4TabUniverseCsv(
        initialUniverse: List<Map<String, Any>>,
        step1Passed: List<ScreenedCoin>,
        step2Passed: List<Pair<ScreenedCoin, List<Double>>>,
        step3Ranked: List<ScreenedCoin>
    ): String {
        val sb = StringBuilder()

        // TAB 1: INITIAL UNIVERSE
        sb.append("=== TAB 1: INITIAL ACTIVE FUTURES UNIVERSE ===\n")
        sb.append("Symbol,Last Price,High 24h,Low 24h,Quote Volume,Change %\n")
        for (item in initialUniverse) {
            val sym = item["symbol"] as? String ?: ""
            val p = (item["lastPrice"] as? Double) ?: 0.0
            val h = (item["highPrice"] as? Double) ?: 0.0
            val l = (item["lowPrice"] as? Double) ?: 0.0
            val v = (item["quoteVolume"] as? Double) ?: 0.0
            val chg = (item["priceChangePercent"] as? Double) ?: 0.0
            sb.append("\"$sym\",${Formatters.formatRawPrice5Dec(p)},${Formatters.formatRawPrice5Dec(h)},${Formatters.formatRawPrice5Dec(l)},${String.format(Locale.US, "%.2f", v)},${String.format(Locale.US, "%.2f", chg)}%\n")
        }
        sb.append("\n")

        // TAB 2: INSTITUTIONAL FILTERED
        sb.append("=== TAB 2: INSTITUTIONAL 4-FILTER PASSING ASSETS ===\n")
        sb.append("Symbol,Last Price,Today Volume,Exact OI,Vol/OI Ratio,Taker Buy %,Displacement %,10D Pass Count,Status\n")
        for (c in step1Passed) {
            val status = if (c.passedTodayAll) "Passed All Today" else "Passed 10D Rule (${c.daysPassedAll}/10)"
            sb.append("\"${c.symbol}\",${Formatters.formatRawPrice5Dec(c.lastPrice)},${String.format(Locale.US, "%.2f", c.volume)},${String.format(Locale.US, "%.2f", c.exactOI)},${String.format(Locale.US, "%.2f", c.volToOiRatio)},${String.format(Locale.US, "%.2f", c.realTakerBuyRatio)}%,${String.format(Locale.US, "%.2f", c.displacement * 100)}%,${c.daysPassedAll},\"$status\"\n")
        }
        sb.append("\n")

        // TAB 3: CALMAR FILTERED
        sb.append("=== TAB 3: 1-YEAR CALMAR FILTERED ASSETS ===\n")
        sb.append("Symbol,Last Price,Annualized Return %,Max Drawdown %,Calmar Ratio\n")
        for ((c, _) in step2Passed) {
            val annReturn = c.calmarRatio * c.maxDrawdown * 100.0
            sb.append("\"${c.symbol}\",${Formatters.formatRawPrice5Dec(c.lastPrice)},${String.format(Locale.US, "%.2f", annReturn)}%,${String.format(Locale.US, "%.2f", c.maxDrawdown * 100.0)}%,${String.format(Locale.US, "%.4f", c.calmarRatio)}\n")
        }
        sb.append("\n")

        // TAB 4: SHARPE RANKED
        sb.append("=== TAB 4: FINAL SHARPE RANKED INSTITUTIONAL UNIVERSE ===\n")
        sb.append("Rank,Symbol,Last Price,Sharpe Ratio,Calmar Ratio,Annualized Vol %,Max Drawdown %\n")
        for ((rank, c) in step3Ranked.withIndex()) {
            sb.append("${rank + 1},\"${c.symbol}\",${Formatters.formatRawPrice5Dec(c.lastPrice)},${String.format(Locale.US, "%.4f", c.sharpeRatio)},${String.format(Locale.US, "%.4f", c.calmarRatio)},${String.format(Locale.US, "%.2f", c.annualizedVol * 100.0)}%,${String.format(Locale.US, "%.2f", c.maxDrawdown * 100.0)}%\n")
        }

        return sb.toString()
    }
}

data class AssetEvaluationResult(
    val spotPrice: Double,
    val spotChange: Double,
    val high24h: Double,
    val low24h: Double,
    val rsi: Double,
    val macdSignal: String,
    val markovRegime: MarkovRegimeResult,
    val netImbalanceUsdt: Double,
    val bidRatioPct: Double,
    val heatmapSource: String,
    val forecastTargets: List<ForecastTarget>,
    val chartPoints: List<ChartPoint>,
    val alphaMetrics: ConfluenceAlphaResult,
    val executiveBrief: String
)
