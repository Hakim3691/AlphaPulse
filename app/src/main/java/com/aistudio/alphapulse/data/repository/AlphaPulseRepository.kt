package com.aistudio.alphapulse.data.repository

import android.util.Log
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.db.EnsembleWeightsEntity
import com.aistudio.alphapulse.data.db.PatternLedgerEntity
import com.aistudio.alphapulse.data.db.PatternSgdWeightsEntity
import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.db.ScreenedCoinEntity
import com.aistudio.alphapulse.data.model.*
import com.aistudio.alphapulse.domain.quant.DepthSnapshot
import com.aistudio.alphapulse.domain.quant.DepthTier
import com.aistudio.alphapulse.domain.quant.LiquidityPainPathResult
import com.aistudio.alphapulse.domain.quant.MarkovRegimeResult
import com.aistudio.alphapulse.domain.quant.ConfluenceAlphaResult
import com.aistudio.alphapulse.domain.quant.ModelState
import com.aistudio.alphapulse.domain.agent.MarketStateAgent
import com.aistudio.alphapulse.domain.agent.MarketStateInput
import com.aistudio.alphapulse.domain.catalogue.TradeIdea
import com.aistudio.alphapulse.domain.presentation.PainPathPrior
import com.aistudio.alphapulse.domain.decision.CoinCalibrationOrchestrator
import com.aistudio.alphapulse.domain.decision.DecisionReport
import com.aistudio.alphapulse.domain.decision.DecisionRouter
import com.aistudio.alphapulse.domain.decision.Needle3DecisionRouter
import com.aistudio.alphapulse.domain.decision.decisionInputsFor
import com.aistudio.alphapulse.domain.ensemble.CoinCalibrationPipeline
import com.aistudio.alphapulse.domain.ensemble.CoinCalibrationReport
import com.aistudio.alphapulse.domain.ensemble.EnsembleCalibration
import com.aistudio.alphapulse.domain.ensemble.EnsembleWeighting
import com.aistudio.alphapulse.domain.ensemble.HorizonForecaster
import com.aistudio.alphapulse.domain.ml.onnx.OnnxTsfmForecaster
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecast
import com.aistudio.alphapulse.domain.ml.onnx.TsfmForecaster
import com.aistudio.alphapulse.data.network.BinanceWebSocketManager
import com.aistudio.alphapulse.data.network.CycleNetworkBudget
import com.aistudio.alphapulse.data.network.ResilientBinanceClient
import com.aistudio.alphapulse.domain.quant.KlineData
import com.aistudio.alphapulse.domain.quant.PredictionStateMachine
import com.aistudio.alphapulse.domain.quant.TapeMetrics
import com.aistudio.alphapulse.domain.quant.TapePrint
import com.aistudio.alphapulse.domain.quant.TradeTapeAnalyzer
import com.aistudio.alphapulse.domain.quant.QuantMath
import com.aistudio.alphapulse.domain.ml.TsfmInput
import com.aistudio.alphapulse.domain.quant.QuantNarrativeAgent
import com.aistudio.alphapulse.domain.pattern.PatternCandle
import com.aistudio.alphapulse.domain.pattern.PatternForecastEvidence
import com.aistudio.alphapulse.domain.pattern.PatternHorizon
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEngine
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEntry
import com.aistudio.alphapulse.domain.pattern.PatternSgdModel
import com.aistudio.alphapulse.domain.strategy.QuantStrategyEngine
import com.aistudio.alphapulse.domain.strategy.ScreenerSignal
import com.aistudio.alphapulse.domain.strategy.StrategyCandle
import com.aistudio.alphapulse.domain.strategy.StrategySignal
import com.aistudio.alphapulse.domain.strategy.TechnicalScreeners
import com.aistudio.alphapulse.domain.pattern.PatternTaxonomy
import com.aistudio.alphapulse.service.AlphaPulseService
import com.aistudio.alphapulse.core.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/** Minimum 1m history required before a mounted TSFM is asked for a forecast. */
private const val MIN_TSFM_HISTORY = 32

/** Longest docked horizon (1h) in minutes. */
private const val MAX_FORECAST_MINUTES = 60

/** Horizon whose ensemble legs feed the §29 router - the MTF horizon the UI headlines. */
private const val DECISION_REFERENCE_HORIZON = "15m"

/** How recent a settlement must be to inform the router's breach and error guards. */
private const val DECISION_RECENT_WINDOW_MS = 5 * 60 * 1000L

/**
 * Newest recorded verdicts kept per symbol.
 *
 * The ledger is append-only and the engine evaluates every ten seconds around
 * the clock, so unbounded history would grow without limit on a device meant to
 * run for days. This retains roughly a day of routing decisions per asset.
 */
private const val DECISION_RECORD_RETENTION = 10_000

/** Verdicts the on-screen decision log holds; the ledger itself keeps far more. */
private const val DECISION_HISTORY_LIMIT = 50

/** Pattern rows kept per asset; five horizons issue continuously, so the table needs a bound. */
private const val PATTERN_LEDGER_RETENTION = 500

/** Klines per calibration request; Binance Futures allows at most 1500. */
private const val CALIBRATION_PAGE_SIZE = 1000

/**
 * Pages fetched per horizon when walking back through the 30-day window.
 *
 * Eight pages is 8,000 bars: a full 30 days for 5m and slower, and the most
 * recent ~5.5 days for 1m. The report records the bars actually available, so a
 * truncated window is visible rather than being passed off as a full month.
 */
private const val CALIBRATION_MAX_PAGES = 8

private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * D7: catalogue candidates handed to the router per evaluation.
 *
 * Bounded because the prompt is a fixed-size grounding document; the catalogue is
 * ranked by empirical edge, so the head of the list is the part that matters.
 */
private const val CATALOGUE_CANDIDATE_LIMIT = 8

class AlphaPulseRepository(
    private val client: ResilientBinanceClient,
    private val database: AppDatabase,
    private val cacheDir: File? = null,
    val wsManager: BinanceWebSocketManager = BinanceWebSocketManager(),
    private val tsfmForecaster: TsfmForecaster = OnnxTsfmForecaster(),
    /**
     * The §29 router for this repository.
     *
     * Defaults to the mounted-checkpoint router resolved on first use, so production
     * wiring is unchanged; the seam exists so the ledger's write path can be driven
     * with a scripted engine (§52).
     */
    private val decisionRouterProvider: () -> DecisionRouter = {
        Needle3DecisionRouter.forMountedCheckpoint()
    },
    /**
     * The §30 dispatcher that scopes a 30-day calibration run.
     *
     * Defaults to the mounted-checkpoint orchestrator, so production wiring is
     * unchanged; the seam lets the calibration spec drive the plan with a
     * scripted engine instead of a 35 MB checkpoint.
     */
    private val calibrationOrchestrator: CoinCalibrationOrchestrator = CoinCalibrationOrchestrator(),
    /**
     * The clock the evaluation cycle reads.
     *
     * Production is the wall clock, unchanged. The seam exists so `CycleNetworkBudgetBddSpec`
     * can walk a simulated hour through the real caches and count the requests the cycle
     * would issue; without it a fast test would see every TTL as still fresh and could
     * never prove the hourly budget.
     */
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** One horizon's cached bars, refreshed at most once per bar (§73). */
    private data class CachedPatternSeries(val fetchedAt: Long, val candles: List<PatternCandle>)

    private val patternSeriesCache = HashMap<String, CachedPatternSeries>()

    /** The §74 board's shared bar series: one symbol at a time, refreshed once per bar. */
    private data class CachedStrategySeries(
        val symbol: String,
        val fetchedAt: Long,
        val candles: List<StrategyCandle>
    )

    @Volatile
    private var strategySeriesCache: CachedStrategySeries? = null

    /**
     * Bars the ten-second cycle does not need to re-download.
     *
     * A 1m candle is final once a minute and the heatmap it reads spans 240 minutes, so
     * refetching either on every ten-second cycle multiplied the measured ~300 MB/hour
     * data use for identical bytes. A failed fetch is retried next cycle.
     */
    private val klinesThrottle = FetchThrottle<List<KlineData>>(CycleNetworkBudget.KLINES_TTL_MS)
    private val heatmapThrottle =
        FetchThrottle<Pair<Double, Double>>(CycleNetworkBudget.HEATMAP_TTL_MS)

    val allPredictionsFlow: Flow<List<PredictionEntity>> = database.predictionDao().getAllPredictionsFlow()
    fun getPredictionsBySymbolFlow(symbol: String): Flow<List<PredictionEntity>> =
        database.predictionDao().getPredictionsBySymbolFlow(symbol)

    /**
     * The newest recorded §29 verdicts for one symbol, newest first.
     *
     * The screen's decision log and the audit export read the same table, so the
     * history a user sees is the history a CSV row is attributed to.
     */
    fun getDecisionHistoryFlow(
        symbol: String,
        limit: Int = DECISION_HISTORY_LIMIT
    ): Flow<List<DecisionRecordEntity>> =
        database.decisionRecordDao().getDecisionHistoryFlow(symbol, limit)

    /** Open/close ONNX sessions to match the models currently mounted in the dock. */
    suspend fun syncMountedTsfmModels(modelIds: List<String>) =
        tsfmForecaster.syncMountedModels(modelIds)

    /**
     * Calibrate the ensemble for one asset over the last 30 days (§20).
     *
     * The dock's mounted models are the candidates: an unmounted TSFM has no
     * session, so scoring it would compare nothing against the market. Needle 3
     * scopes the run (which horizons) through [calibrationOrchestrator]; the loop
     * itself is deterministic and lives in [CoinCalibrationPipeline].
     *
     * The resulting weights are persisted per horizon by the repository, so the
     * next launch starts from this asset's measured weights instead of the generic
     * mount split.
     *
     * @param symbol market symbol; normalised before use and before storage
     * @param extraModels models mounted in the dock; defaults to the live store
     * @param windowDays history window, 30 by default
     * @return the report, or null when nothing is mounted or no history arrived
     */
    suspend fun calibrateCoin(
        symbol: String,
        extraModels: Collection<String> = ModelDownloadStore.readyModelIds(),
        windowDays: Int = EnsembleCalibration.WINDOW_DAYS
    ): CoinCalibrationReport? = withContext(Dispatchers.IO) {
        val normalised = symbol.trim().uppercase(Locale.US)
        if (normalised.isBlank()) return@withContext null

        val plan = calibrationOrchestrator.plan(normalised, extraModels, windowDays)
        if (plan.modelIds.isEmpty() || plan.horizons.isEmpty()) return@withContext null

        val candles = plan.horizons.associate { horizon ->
            horizon.id to fetchCalibrationCandles(normalised, horizon.interval, windowDays)
        }
        if (candles.values.all { it.isEmpty() }) return@withContext null

        val report = CoinCalibrationPipeline(
            forecaster = HorizonForecaster { modelId, closes, steps ->
                forecastCloseAhead(modelId, closes, steps)
            }
        ).run(plan, candles)

        persistCalibration(report)
        report
    }

    /** The saved 30-day weights for one asset, one row per horizon. */
    suspend fun persistedCalibration(symbol: String): List<EnsembleWeightsEntity> =
        database.ensembleWeightsDao().getForSymbol(symbol.trim().uppercase(Locale.US))

    /** Live view of the saved calibration, so a recalibration reaches the screen. */
    fun calibrationFlow(symbol: String): Flow<List<EnsembleWeightsEntity>> =
        database.ensembleWeightsDao().getForSymbolFlow(symbol.trim().uppercase(Locale.US))

    /**
     * One next-close prediction from a mounted TSFM, for the calibration loop.
     *
     * A model without a live session, or whose answer is not a usable price,
     * returns null: the pipeline then simply scores it on fewer origins instead of
     * inventing a prediction.
     */
    private suspend fun forecastCloseAhead(
        modelId: String,
        closes: DoubleArray,
        steps: Int
    ): Double? {
        if (closes.size < MIN_TSFM_HISTORY) return null
        val last = closes.last()
        val previous = closes[closes.size - 2]
        val input = TsfmInput(
            timestamp = System.currentTimeMillis(),
            open = previous,
            high = max(previous, last),
            low = min(previous, last),
            close = last,
            logReturn = if (previous > 0.0) ln(last / previous) else 0.0,
            regime = "Calibration",
            horizon = "Calibration"
        )
        return tsfmForecaster.forecast(modelId, input, closes, steps)
            ?.median?.lastOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Fetch the history a calibration horizon is scored on.
     *
     * Pages backwards from the newest bar until the window is covered, the page
     * budget is spent, or the exchange stops answering.
     */
    private suspend fun fetchCalibrationCandles(
        symbol: String,
        interval: String,
        windowDays: Int
    ): List<KlineData> {
        val windowStart = System.currentTimeMillis() - windowDays * DAY_MS
        val collected = sortedMapOf<Long, KlineData>()
        var endTime: Long? = null

        for (page in 0 until CALIBRATION_MAX_PAGES) {
            val path = buildString {
                append("/fapi/v1/klines?symbol=$symbol&interval=$interval")
                append("&limit=$CALIBRATION_PAGE_SIZE")
                endTime?.let { append("&endTime=$it") }
            }
            val rows = try {
                client.getJsonArray(path)
            } catch (_: Exception) {
                break
            }
            if (rows.length() == 0) break

            var oldest = Long.MAX_VALUE
            for (i in 0 until rows.length()) {
                val k = rows.getJSONArray(i)
                val openTime = k.getLong(0)
                oldest = min(oldest, openTime)
                collected[openTime] = KlineData(
                    openTime = openTime,
                    open = k.getDouble(1),
                    high = k.getDouble(2),
                    low = k.getDouble(3),
                    close = k.getDouble(4),
                    volume = k.getDouble(5),
                    quoteVolume = k.getDouble(7),
                    takerBuyQuoteVolume = k.getDouble(10)
                )
            }

            if (oldest <= windowStart || rows.length() < CALIBRATION_PAGE_SIZE) break
            endTime = oldest - 1
        }

        return collected.values.filter { it.openTime >= windowStart }
    }

    /** Replace this asset's saved rows with the run's result, one row per horizon. */
    private suspend fun persistCalibration(report: CoinCalibrationReport) {
        if (report.horizons.isEmpty()) return
        val rows = report.horizons.map { horizon ->
            EnsembleWeightsEntity(
                compositeKey = "${report.symbol}#${horizon.horizonId}",
                symbol = report.symbol,
                horizonId = horizon.horizonId,
                weightK = horizon.weights.weightK,
                weightP = horizon.weights.weightP,
                weightAlpha = horizon.weights.weightAlpha,
                weight4 = horizon.weights.weight4,
                weight5 = horizon.weights.weight5,
                lastUpdated = report.generatedAt,
                modelVersion = horizon.scores.joinToString(",") { it.modelId }
            )
        }
        database.ensembleWeightsDao().upsertAll(rows)
    }

    // Per-asset rolling state
    private val kurtosisHistory = mutableListOf<Double>()
    private val state1Returns = mutableListOf<Double>()
    private val state0Returns = mutableListOf<Double>()
    private var prevDepthTotal = 0.0
    private var prevDepthTimestamp = 0L
    private var lambdaDecay = 0.035
    private var initializedWeightsForSymbol = ""

    /**
     * §29 router behind the verdict the dashboard shows.
     *
     * The same implementation the agent pipeline resolves, so the two can never
     * disagree about how a route is chosen. The checkpoint is looked up on every
     * call, so mounting or evicting Needle 3 in the dock changes the verdict on the
     * next cycle with no re-wiring.
     */
    private val decisionRouter: DecisionRouter by lazy { decisionRouterProvider() }

    /** Supplies the regime conviction the router's snapshot needs. */
    private val marketStateAgent = MarketStateAgent()

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

                // True Sharpe from the realized daily-return series (mean/σ scaled to,
                // annualized, Rf from perpetual funding) — NOT derived from Calmar, which
                // made Sharpe a linear rescale of Calmar rather than an independent metric.
                val dailyRf = dynamicRf / 365.0
                val sharpe = if (dailyStd > 0) ((mean - dailyRf) / dailyStd) * sqrt(365.0) else 0.0

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
        modelState: ModelState,
        activeExtraModels: Collection<String> = emptyList()
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

        // The cycle stamps everything it issues with one reading of the clock, so the
        // caches, the ledger rows and the forecast windows cannot disagree.
        val now = clock()

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

        // Empirical shock (the 95th-percentile trade size) comes from the live
        // @aggTrade stream the manager already holds. The REST `aggTrades?limit=500`
        // call that used to run here downloaded the same tape every ten seconds for one
        // percentile, and was then overwritten by the stream a few lines below.
        var empiricalShock = 0.0

        // Depth snapshot & Liquidity pain path
        var depthSnapshot: DepthSnapshot? = null
        var ltfPathData = LiquidityPainPathResult(0.0, 1.0, "L2 Proxy")
        var netImbalanceUsdt = 0.0
        var bidRatioPct = 50.0

        try {
            // The combined stream already carries depth20@250ms. Fetching the same book
            // over REST every cycle doubled the traffic and the live snapshot overwrote
            // it moments later, so REST is the fallback for when no stream is up.
            val streamedBook = wsManager.telemetryFlow.value?.takeIf {
                it.symbol.equals(symbol, ignoreCase = true) &&
                    it.bids.isNotEmpty() && it.asks.isNotEmpty()
            }
            depthSnapshot = if (streamedBook == null) client.fetchDepth(symbol, 50) else null
            if (depthSnapshot != null) {
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

            // Primary route: Liquidation Heatmap API (SmartMoney public no-key endpoint).
            // The payload spans 240 minutes and 100 price buckets, so it is reused for
            // HEATMAP_TTL_MS instead of being downloaded on all 360 cycles of an hour.
            val heatmapPair = heatmapThrottle.value(symbol, now) {
                client.fetchLiquidityHeatmap(symbol, currentSpot)
            }
            if (heatmapPair != null) {
                ltfPathData = LiquidityPainPathResult(heatmapPair.first, heatmapPair.second, "API")
            }

            // Fallback route: local liquidation heat aggregated from the exchange @forceOrder
            // stream (no-key, spec FR-007 §9) — keeps the tag live when the REST source is cold.
            if (heatmapPair == null) {
                val localLiq = wsManager.liquidationTelemetryFlow.value
                if (localLiq != null && localLiq.symbol.equals(symbol, ignoreCase = true)) {
                    ltfPathData = LiquidityPainPathResult(localLiq.muPath, localLiq.spreadSkew, "WS Liq")
                }
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

        // 40 1-minute Klines. The newest 1m bar only becomes final once a minute and the
        // intra-minute price comes from the WebSocket, so the parsed series is reused for
        // KLINES_TTL_MS rather than re-downloaded on every ten-second cycle.
        val klines: List<KlineData> = klinesThrottle.value(symbol, now) {
            val parsed = mutableListOf<KlineData>()
            try {
                val klinesArr = client.getJsonArray("/fapi/v1/klines?symbol=$symbol&interval=1m&limit=40")
                for (i in 0 until klinesArr.length()) {
                    val k = klinesArr.getJSONArray(i)
                    parsed.add(
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
            if (parsed.isEmpty()) null else parsed
        } ?: emptyList()

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
        val alphaMetrics = QuantMath.computeConfluenceAlpha(klines, currentSpot, depthSnapshot ?: DepthSnapshot(bids = emptyList(), asks = emptyList()), baseVol, mtfMetrics.regime.contains("Burst"))
        val vp = QuantMath.computeVolumeProfileAndVWAP(klines, currentSpot, mtfMetrics.regime.contains("Burst"))

        // Generate forecasts for 5 horizons with discrete horizon pain paths & candle boundary phase-locking
        val ensembleCount = EnsembleWeighting.activeModelCount(activeExtraModels)
        val mountedExtras = EnsembleWeighting.mountedExtraIds(activeExtraModels)

        // One inference pass per mounted TSFM per evaluation cycle, shared by all
        // five horizons. A model without a live session contributes nothing.
        val tsfmForecasts: Map<String, TsfmForecast> = if (mountedExtras.isNotEmpty() && klines.size >= MIN_TSFM_HISTORY) {
            val closes = klines.map { it.close }.toDoubleArray()
            val forecasts = mutableMapOf<String, TsfmForecast>()
            for ((index, modelId) in mountedExtras.withIndex()) {
                val tfmInput = TsfmInput(
                    timestamp = now,
                    open = klines.first().open,
                    high = klines.map { it.high }.max() ?: 0.0,
                    low = klines.map { it.low }.min() ?: 0.0,
                    close = closes[index],
                    volume = klines.map { it.volume }.average() ?: 0.0,
                    logReturn = ln(klines[index + 1].close / klines[index].close),
                    realizedVolatility = baseVol,
                    funding = 0.0,
                    openInterest = 0.0,
                    oiChange = 0.0,
                    takerBuyRatio = 0.0,
                    vwapDistance = 0.0,
                    pocDistance = 0.0,
                    vahDistance = 0.0,
                    valDistance = 0.0,
                    l2Imbalance = 0.0,
                    spread = 0.0,
                    depthVelocity = 0.0,
                    liquidationIntensity = 0.0,
                    upperLiquidityDistance = 0.0,
                    lowerLiquidityDistance = 0.0,
                    regime = mtfMetrics.regime
                )
                val forecast = tsfmForecaster.forecast(modelId, tfmInput, closes, MAX_FORECAST_MINUTES)
                if (forecast != null) {
                    forecasts[modelId] = forecast
                }
            }
            forecasts
        } else {
            emptyMap()
        }

        // §73 + §16: the pattern ledger scores the same mounted ensemble on this same
        // inference pass. One network call and one model pass feed both ledgers, and a
        // horizon with no mounted model records that instead of inventing a drift.
        runPatternLedger(symbol, now) { horizon ->
            patternForecastFor(tsfmForecasts, modelState, currentSpot, horizon)
        }

        val forecastTargets = mutableListOf<ForecastTarget>()
        // Each ensemble leg per horizon, kept for the decision snapshot below.
        val legDrifts = mutableMapOf<String, ForecastLegs>()
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

            // Mounted TSFM drift is the model's own median forecast at this horizon,
            // converted to a per-minute log drift. With no session (or no forecast)
            // the member contributes nothing rather than a synthetic signal.
            val muChronos = tsfmForecasts[EnsembleWeighting.CHRONOS_ID]
                ?.driftPerMinute(currentSpot, h.minutes) ?: 0.0
            val muTimesFm = tsfmForecasts[EnsembleWeighting.TIMESFM_ID]
                ?.driftPerMinute(currentSpot, h.minutes) ?: 0.0

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
                (muChronos * modelState.weight4) +
                (muTimesFm * modelState.weight5) +
                modelState.driftBias

            val dt = h.minutes.toDouble()

            // §29: the two legs the router compares, as percent moves at this horizon,
            // taken from the same components that produced the cone above.
            val kinematicDrift = (muKinematic * modelState.weightK) +
                (muPath * modelState.weightP) +
                (muAlpha * modelState.weightAlpha)
            val tsfmDrift = (muChronos * modelState.weight4) + (muTimesFm * modelState.weight5)
            legDrifts[h.id] = ForecastLegs(
                kinematicPct = (exp(kinematicDrift * dt) - 1.0) * 100.0,
                tsfmPct = (exp(tsfmDrift * dt) - 1.0) * 100.0
            )

            val expectedReturn = combinedDrift * dt
            val predPrice = currentSpot * exp(expectedReturn)

    // Cone width at 2.0σ (≈95.4% two-sided coverage on gaussian markets — realistic
    // ~90% under the fat-tailed kurtosis regimes this engine flags). A fixed ±2σ cone
    // structurally mitigates longer horizon breaches that biased LTF hit rates over MTF/HTF.
    val coneSigmas = 2.0
            val sigmaSpread = effectiveVol * sqrt(dt)
            val upperPrice = currentSpot * exp(expectedReturn + (sigmaSpread * upperSpreadMultiplier * coneSigmas))
            val lowerPrice = currentSpot * exp(expectedReturn - (sigmaSpread * lowerSpreadMultiplier * coneSigmas))

            val deltaPct = ((predPrice - currentSpot) / currentSpot) * 100.0

            val target = ForecastTarget(
                horizonId = h.id,
                label = h.label,
                minutes = h.minutes,
                modelType = EnsembleWeighting.modelTypeLabel(ensembleCount),
                predictedPrice = predPrice,
                upperPrice = upperPrice,
                lowerPrice = lowerPrice,
                deltaPct = deltaPct,
                isBullish = deltaPct >= 0.0
            )
            forecastTargets.add(target)

            // Phase-locked target timestamp to candle boundary (:00.000s)
            val targetTimestamp = ((now / 60000L) + h.minutes) * 60000L

            // Throttle window = full horizon length: a new forecast is only issued once the
            // previous one for the same horizon has settled (or expired). The previous
            // `h.ms - 20s` overlap created simultaneous duplicate pending rows at the 40s→60s
            // boundary and double-counted samples in the online-SGD audit.
            val throttleWindow = h.ms
            val hasRecentPending = existingPredictions.any {
                it.horizonId == h.id && !it.settled && (now - it.issuedTimestamp) < throttleWindow
            }

            if (!hasRecentPending && currentSpot > 0.0) {
                // Deterministic id keyed on the phase-locked candle boundary so a re-issued
                // forecast within the same candle replaces (REPLACE) rather than duplicates.
                val modelProvider = when {
                EnsembleWeighting.mountedExtraIds(activeExtraModels).contains(EnsembleWeighting.CHRONOS_ID) && 
                EnsembleWeighting.mountedExtraIds(activeExtraModels).contains(EnsembleWeighting.TIMESFM_ID) -> "chronos+timesfm"
                EnsembleWeighting.mountedExtraIds(activeExtraModels).contains(EnsembleWeighting.CHRONOS_ID) -> "chronos"
                EnsembleWeighting.mountedExtraIds(activeExtraModels).contains(EnsembleWeighting.TIMESFM_ID) -> "timesfm"
                else -> ""
            }
            val newEntity = PredictionEntity(
                id = "$symbol-${h.id}-$targetTimestamp",
                symbol = symbol,
                horizonId = h.id,
                horizonLabel = h.label,
                modelType = EnsembleWeighting.modelTypeLabel(ensembleCount),
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
                muW4 = muChronos,
                muW5 = muTimesFm,
                modelProvider = modelProvider,
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
                    timestamp = k.openTime,
                    timeLabel = timeFmt.format(Date(k.openTime)),
                    open = k.open,
                    high = k.high,
                    low = k.low,
                    close = k.close,
                    volume = k.volume,
                    realizedVolatility = 0.0,
                    funding = 0.0,
                    openInterest = 0.0,
                    oiChange = 0.0,
                    takerBuyRatio = 0.0,
                    vwapDistance = 0.0,
                    pocDistance = 0.0,
                    vahDistance = 0.0,
                    valDistance = 0.0,
                    l2Imbalance = 0.0,
                    spread = 0.0,
                    depthVelocity = 0.0,
                    liquidationIntensity = 0.0,
                    upperLiquidityDistance = 0.0,
                    lowerLiquidityDistance = 0.0,
                    regime = "Unknown",
                    horizon = null,
                    metadata = emptyMap<String, Any>()
                )
            )
        }

        // Bridge anchor at currentSpot
        if (chartPoints.isNotEmpty()) {
            val last = chartPoints.last()
            chartPoints[chartPoints.size - 1] = last.copy(
                open = currentSpot,
                high = currentSpot,
                low = currentSpot
            )
        }

        for (target in forecastTargets) {
            chartPoints.add(
                ChartPoint(
                    timestamp = now,
                    timeLabel = "+${target.minutes}m",
                    open = currentSpot,
                    high = target.upperPrice,
                    low = target.lowerPrice,
                    close = target.predictedPrice,
                    volume = 0.0,
                    realizedVolatility = 0.0,
                    funding = 0.0,
                    openInterest = 0.0,
                    oiChange = 0.0,
                    takerBuyRatio = 0.0,
                    vwapDistance = 0.0,
                    pocDistance = 0.0,
                    vahDistance = 0.0,
                    valDistance = 0.0,
                    l2Imbalance = 0.0,
                    spread = 0.0,
                    depthVelocity = 0.0,
                    liquidationIntensity = 0.0,
                    upperLiquidityDistance = 0.0,
                    lowerLiquidityDistance = 0.0,
                    regime = "Unknown",
                    horizon = target.label,
                    metadata = emptyMap<String, Any>()
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

        // §29: the verdict the dashboard shows, from the snapshot just evaluated.
        // The reason travels with the state, so a route can be attributed to Needle 3
        // or to the deterministic rules that back it up.
        val recentlySettled = existingPredictions.filter {
            it.settled && (now - it.targetTimestamp) in 0..DECISION_RECENT_WINDOW_MS
        }
        val regimeConfidence = marketStateAgent.execute(
            MarketStateInput(
                symbol = symbol,
                priceData = mapOf("priceChangePercent" to spotChange),
                orderBookSnapshot = mapOf("bidRatio" to bidRatioPct / 100.0)
            )
        )?.regimeConfidence ?: 0.5

        // D7: the catalogue is queried, not hardcoded. The active Markov regime picks
        // which setups Needle 3 is allowed to consider for this snapshot.
        val catalogueCandidates = catalogueCandidatesFor(mtfMetrics.regime)

        val decisionInputs = decisionInputsFor(
            kinematicPct = legDrifts[DECISION_REFERENCE_HORIZON]?.kinematicPct ?: 0.0,
            tsfmPct = legDrifts[DECISION_REFERENCE_HORIZON]?.tsfmPct ?: 0.0,
            bidRatioPct = bidRatioPct,
            confluenceConfidence = alphaMetrics.confidence,
            regimeConfidence = regimeConfidence,
            coneBreaches = recentlySettled.count { it.inCone == false },
            recentErrorPct = recentlySettled
                .mapNotNull { item -> item.errorPct?.let { abs(it) } }
                .average()
                .let { if (it.isNaN()) 0.0 else it },
            dataAvailable = currentSpot > 0.0 && klines.isNotEmpty(),
            catalogueCandidates = catalogueCandidates.map { it.name }
        )
        val decision = DecisionReport(
            state = decisionRouter.router(decisionInputs),
            confidence = decisionRouter.decisionConfidence,
            reason = decisionRouter.lastReason,
            source = decisionRouter.decisionSource
        )

        // §21/§29: the verdict belongs in the audit ledger, not only on the screen.
        // One row per evaluated snapshot, stamped with the same `now` the predictions
        // issued by this cycle carry, so the export can attribute each row to the
        // decision that produced it. Retention is bounded - see
        // DECISION_RECORD_RETENTION - so a 24/7 device cannot grow this table forever.
        database.decisionRecordDao().insert(
            DecisionRecordEntity(
                id = DecisionAuditJoin.idOf(symbol, now),
                symbol = symbol,
                issuedTimestamp = now,
                state = decision.state.name,
                confidence = decision.confidence,
                source = decision.source.name,
                referenceHorizon = DECISION_REFERENCE_HORIZON,
                reason = decision.reason
            )
        )
        database.decisionRecordDao().prune(symbol, DECISION_RECORD_RETENTION)

        // §74: the screeners and models need more history than the 40-bar forecast
        // snapshot provides, so they read a dedicated 1m series from the same Binance
        // klines source. On a fetch failure the cached series (or this snapshot) is used;
        // a rule with too little history still reports NEUTRAL rather than a guess.
        val strategyCandles = strategySeries(symbol, now, klines)

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
            executiveBrief = executiveBrief,
            decision = decision,
            screenerSignals = TechnicalScreeners.evaluateAll(strategyCandles),
            strategySignals = QuantStrategyEngine.evaluateAll(strategyCandles),
            catalogueCandidates = catalogueCandidates,
            painPathPrior = PainPathPrior.of(
                spotPrice = currentSpot,
                muPath = ltfPathData.muPath,
                confidence = alphaMetrics.confidence
            )
        )
    }

    /**
     * D7: the catalogue rows this regime permits, strongest empirical edge first.
     *
     * Read from Room so the taxonomy is queryable data, not a compiled list. When the
     * catalogue is unavailable the engine receives no candidates and decides on the
     * snapshot alone; it never substitutes a guessed taxonomy.
     */
    private suspend fun catalogueCandidatesFor(regime: String): List<TradeIdea> =
        runCatching {
            CatalogueRepository(database.tradeIdeaDao())
                .candidatesFor(CatalogueRepository.regimeOf(regime))
                .take(CATALOGUE_CANDIDATE_LIMIT)
        }.getOrDefault(emptyList())

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
                (item.muW4 * modelState.weight4) +
                (item.muW5 * modelState.weight5) +
                modelState.driftBias
            val errorMu = muRealized - muPredicted

            if (!errorMu.isNaN() && !errorMu.isInfinite()) {
                modelState.driftBias += modelState.learningRate * errorMu

                // One AdamW optimizer step for every active member — including the
                // mounted W4/W5 TSFM members, whose drift terms this very
                // prediction recorded — then renormalise all five shares so
                // AdamW never inflates ΣW past 1.000 (SPEC §20).
                val stepCount = pendingList.indexOf(item) + 1
                EnsembleWeighting.applyAdamWStep(
                    state = modelState,
                    errorMu = errorMu,
                    muK = item.muK,
                    muP = item.muP,
                    muAlpha = item.muAlpha,
                    muW4 = item.muW4,
                    muW5 = item.muW5,
                    step = stepCount
                )
            }

            // Widen the cone after a breach, tighten it after a hit (§20).
            if (!inCone) {
                modelState.volMultiplier = min(3.0, modelState.volMultiplier * 1.05)
            } else {
                modelState.volMultiplier = max(0.5, modelState.volMultiplier * 0.995)
            }

            // The ledger wording comes from the state machine (§34) so the row and
            // the lifecycle can never disagree about what settlement means.
            val learningAction = PredictionStateMachine.learningActionFor(inCone)

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
        database.decisionRecordDao().clearAll()
    }

    // ------------------------------------------------------- §73 pattern ledger

    /** The selected asset's pattern rows, in boundary order (§73). */
    fun patternLedgerFlow(symbol: String): Flow<List<PatternLedgerEntry>> =
        database.patternLedgerDao()
            .flowForAsset(symbol.removeSuffix("USDT"))
            .map { rows -> rows.map { it.toEntry() } }

    suspend fun clearPatternLedger() {
        database.patternLedgerDao().clearAll()
        database.patternLedgerDao().clearWeights()
        patternSeriesCache.clear()
    }

    /**
     * Issue, settle and learn one pass of the pattern ledger.
     *
     * Each horizon is fetched at its own interval and cached for one bar, so a 10-second
     * evaluation loop does not turn into five REST calls per tick. Settlement and issuing
     * are deliberately per horizon: one horizon's thin data cannot stall another's clock.
     */
    suspend fun runPatternLedger(
        symbol: String,
        now: Long = System.currentTimeMillis(),
        /** The mounted ensemble's evidence for a horizon; NONE when nothing is mounted. */
        forecastFor: (PatternHorizon) -> PatternForecastEvidence = { PatternForecastEvidence.NONE }
    ) {
        val asset = symbol.removeSuffix("USDT")
        val dao = database.patternLedgerDao()

        for (horizon in PatternHorizon.entries) {
            val series = patternSeries(symbol, horizon, now)
            if (series.size < 2) continue

            val classes = PatternTaxonomy.idsFor(horizon)
            val key = "$asset#${horizon.id}"
            val stored = dao.weights(key)
            val model = PatternSgdModel.fromCsv(
                classes = classes,
                learningRate = stored?.learningRate ?: PatternSgdModel.DEFAULT_LEARNING_RATE,
                csv = stored?.weightsCsv ?: ""
            )

            var learned = false
            for (row in dao.openFor(asset, horizon.id).filter { it.targetTime <= now }) {
                // A boundary whose bar never arrives must not block this horizon's clock
                // forever; two full bars of silence is an expired observation.
                if (now - row.targetTime > horizon.durationMs * 2) {
                    dao.update(
                        row.copy(
                            status = PatternLedgerEngine.STATUS_SETTLED,
                            realizedPattern = PatternLedgerEngine.NO_PATTERN,
                            sgdAction = PatternLedgerEngine.SGD_SKIPPED,
                            settled = true
                        )
                    )
                    continue
                }

                val settlement = PatternLedgerEngine.settle(row.toEntry(), series, horizon, model)
                dao.update(PatternLedgerEntity.from(settlement.entry))
                if (settlement.step != null) learned = true
            }

            if (learned) {
                dao.saveWeights(
                    PatternSgdWeightsEntity(
                        compositeKey = key,
                        symbol = symbol,
                        horizonId = horizon.id,
                        weightsCsv = model.toCsv(),
                        learningRate = model.learningRate,
                        steps = (stored?.steps ?: 0) + model.steps,
                        lastLoss = model.lastLoss,
                        updatedAt = now
                    )
                )
            }

            // One open prediction per horizon: the previous one must settle first.
            val stillOpen = dao.openFor(asset, horizon.id)
            if (stillOpen.none { it.targetTime > now }) {
                PatternLedgerEngine.issue(
                    symbol = symbol,
                    candles = series,
                    horizon = horizon,
                    nowMs = now,
                    forecast = forecastFor(horizon)
                )?.let { entry ->
                    dao.insert(PatternLedgerEntity.from(entry))
                }
            }
        }

        dao.prune(asset, PATTERN_LEDGER_RETENTION)
    }

    /**
     * The mounted TSFM ensemble's weighted read for one pattern horizon.
     *
     * Both models contribute their own median forecast at the horizon, weighted by the
     * ensemble shares the price ledger learned for this symbol (§20). A model that is
     * mounted but produced no forecast is absent rather than zero, and an uncalibrated
     * ensemble (all shares still at zero) averages the models that did answer. No model,
     * no evidence: the row records that instead of a synthetic drift.
     */
    private fun patternForecastFor(
        forecasts: Map<String, TsfmForecast>,
        modelState: ModelState,
        spot: Double,
        horizon: PatternHorizon
    ): PatternForecastEvidence {
        val chronos = forecasts[EnsembleWeighting.CHRONOS_ID]
            ?.driftPerMinute(spot, horizon.minutes)
        val timesFm = forecasts[EnsembleWeighting.TIMESFM_ID]
            ?.driftPerMinute(spot, horizon.minutes)
        if (chronos == null && timesFm == null) return PatternForecastEvidence.NONE

        var weighted = 0.0
        var weight = 0.0
        if (chronos != null) {
            val share = max(0.0, modelState.weight4)
            weighted += chronos * share
            weight += share
        }
        if (timesFm != null) {
            val share = max(0.0, modelState.weight5)
            weighted += timesFm * share
            weight += share
        }
        val perMinute = if (weight > 1e-9) {
            weighted / weight
        } else {
            listOfNotNull(chronos, timesFm).average()
        }
        val driftPct = (exp(perMinute * horizon.minutes) - 1.0) * 100.0
        val provider = when {
            chronos != null && timesFm != null -> "chronos+timesfm"
            chronos != null -> "chronos"
            else -> "timesfm"
        }
        return PatternForecastEvidence(provider = provider, driftPct = driftPct)
    }

    /**
     * One horizon's bars, re-fetched at most once per bar.
     *
     * On a network failure the previous series is reused rather than settling against an
     * empty list, which would grade every open row against nothing.
     */
    private suspend fun patternSeries(
        symbol: String,
        horizon: PatternHorizon,
        now: Long
    ): List<PatternCandle> {
        val key = "$symbol#${horizon.id}"
        val cached = patternSeriesCache[key]
        if (cached != null && now - cached.fetchedAt < horizon.durationMs) return cached.candles

        val fetched = try {
            val array = client.getJsonArray(
                "/fapi/v1/klines?symbol=$symbol&interval=${horizon.id}" +
                    "&limit=${CycleNetworkBudget.PATTERN_BARS}"
            )
            (0 until array.length()).map { index ->
                val bar = array.getJSONArray(index)
                PatternCandle(
                    openTime = bar.getLong(0),
                    open = bar.getDouble(1),
                    high = bar.getDouble(2),
                    low = bar.getDouble(3),
                    close = bar.getDouble(4),
                    volume = bar.getDouble(5)
                )
            }
        } catch (_: Exception) {
            cached?.candles ?: emptyList()
        }

        if (fetched.isNotEmpty()) patternSeriesCache[key] = CachedPatternSeries(now, fetched)
        return fetched
    }

    /**
     * The shared 1m series the §74 board evaluates, refreshed at most once per bar.
     *
     * It comes from the same `/fapi/v1/klines` endpoint the price ledger and the audit
     * calibration use, so the board can never disagree with them about history. A failed
     * fetch reuses the previous series for the same symbol, then this snapshot's own bars;
     * the rules decide for themselves that too little history means NEUTRAL.
     */
    private suspend fun strategySeries(
        symbol: String,
        now: Long,
        fallback: List<KlineData>
    ): List<StrategyCandle> {
        val cached = strategySeriesCache?.takeIf { it.symbol == symbol }
        if (cached != null && now - cached.fetchedAt < CycleNetworkBudget.STRATEGY_TTL_MS) {
            return cached.candles
        }

        val fetched = try {
            val array = client.getJsonArray(
                "/fapi/v1/klines?symbol=$symbol" +
                    "&interval=${CycleNetworkBudget.STRATEGY_INTERVAL}" +
                    "&limit=${CycleNetworkBudget.STRATEGY_BARS}"
            )
            (0 until array.length()).map { index ->
                val bar = array.getJSONArray(index)
                StrategyCandle(
                    openTime = bar.getLong(0),
                    open = bar.getDouble(1),
                    high = bar.getDouble(2),
                    low = bar.getDouble(3),
                    close = bar.getDouble(4),
                    volume = bar.getDouble(5)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

        val series = when {
            fetched.isNotEmpty() -> fetched
            cached != null -> cached.candles
            else -> fallback.map {
                StrategyCandle(it.openTime, it.open, it.high, it.low, it.close, it.volume)
            }
        }
        if (series.isNotEmpty()) {
            strategySeriesCache = CachedStrategySeries(symbol, now, series)
        }
        return series
    }

    /**
     * Read the stored trade tape for one symbol and turn it into metrics (§10).
     *
     * The websocket feed already writes every print to `trade_buffer`; this is the
     * step that converts those rows into the aggressor imbalance, large prints and
     * sweeps a desk actually reads, using a rolling window that ends at [nowMs].
     *
     * @param symbol market symbol, e.g. "BTCUSDT"
     * @param limit newest prints to load from the database
     * @param nowMs right edge of the analysis window
     * @param windowMs how far back the window reaches
     */
    suspend fun institutionalTape(
        symbol: String,
        limit: Int = 500,
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = TradeTapeAnalyzer.DEFAULT_WINDOW_MS
    ): TapeMetrics = withContext(Dispatchers.IO) {
        val prints = database.tradeBufferDao().getLatestTrades(symbol, limit).map {
            TapePrint(
                timestampMs = it.timestamp,
                price = it.price,
                quantity = it.quantity,
                buyerMaker = it.buyerMaker
            )
        }
        TradeTapeAnalyzer.analyze(prints = prints, nowMs = nowMs, windowMs = windowMs)
    }

    suspend fun exportAuditLedgerCsv(): String = withContext(Dispatchers.IO) {
        val all = database.predictionDao().getAllPredictions()
        // §29: each row is attributed to the verdict its evaluation cycle recorded.
        val decisions = DecisionAuditJoin.indexOf(database.decisionRecordDao().getAllDecisionRecords())
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("Prediction ID,Symbol,Horizon,Model Core,Model Provider,Issued Time,Target Time,Predicted Price,Realized Price,Error %,Cone Status,Learning Action,Decision,Decision Confidence,Decision Source,Decision Reason\n")
        for (item in all) {
            val realizedStr = if (item.settled && item.realizedPrice != null) Formatters.formatRawPrice5Dec(item.realizedPrice) else ""
            val errorStr = if (item.settled && item.errorPct != null) String.format(Locale.US, "%.2f", item.errorPct) else ""
            val coneStr = if (item.settled) (if (item.inCone == true) "HIT" else "BREACH") else "PENDING"
            val decision = DecisionAuditJoin.lookup(decisions, item.symbol, item.issuedTimestamp)
            sb.append("\"${item.id}\",\"${item.symbol}\",\"${item.horizonLabel}\",\"${item.modelType}\",\"${item.modelProvider}\",\"${timeFmt.format(Date(item.issuedTimestamp))}\",\"${timeFmt.format(Date(item.targetTimestamp))}\",${Formatters.formatRawPrice5Dec(item.predictedPrice)},$realizedStr,$errorStr,\"$coneStr\",\"${item.learningAction}\",${DecisionAuditJoin.csvCells(decision)}\n")
        }
        sb.toString()
    }

    /** Export prediction ledger as JSON format */
    suspend fun exportPredictionLedgerJson(): String = withContext(Dispatchers.IO) {
        val all = database.predictionDao().getAllPredictions()
        val jsonArray = JSONArray()
        val decisions = DecisionAuditJoin.indexOf(database.decisionRecordDao().getAllDecisionRecords())
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (item in all) {
            val realizedStr = if (item.settled && item.realizedPrice != null) item.realizedPrice?.toString() else "null"
            val errorStr = if (item.settled && item.errorPct != null) item.errorPct?.toString() else "null"
            val coneStr = if (item.settled) (if (item.inCone == true) "HIT" else "BREACH") else "PENDING"
            val obj = JSONObject()
            obj.put("predictionId", item.id)
            obj.put("symbol", item.symbol)
            obj.put("horizon", item.horizonLabel)
            obj.put("modelType", item.modelType)
            obj.put("issuedTime", timeFmt.format(Date(item.issuedTimestamp)))
            obj.put("targetTime", timeFmt.format(Date(item.targetTimestamp)))
            obj.put("predictedPrice", if (item.predictedPrice != null) Formatters.formatRawPrice5Dec(item.predictedPrice) else "null")
            obj.put("realizedPrice", realizedStr)
            obj.put("errorPct", errorStr)
            obj.put("coneStatus", coneStr)
            obj.put("learningAction", item.learningAction)
            obj.put("modelProvider", item.modelProvider)
            val decision = DecisionAuditJoin.lookup(decisions, item.symbol, item.issuedTimestamp)
            obj.put("decisionState", decision?.state ?: JSONObject.NULL)
            obj.put(
                "decisionConfidence",
                if (decision != null && decision.confidence > 0.0) decision.confidence else JSONObject.NULL
            )
            obj.put("decisionSource", decision?.source ?: JSONObject.NULL)
            obj.put("decisionReason", decision?.reason ?: JSONObject.NULL)
            jsonArray.put(obj)
        }
        return@withContext jsonArray.toString()
    }

    /** Export prediction ledger as Parquet format (columnar, efficient) */
    suspend fun exportPredictionLedgerParquet(): String = withContext(Dispatchers.IO) {
        val all = database.predictionDao().getAllPredictions()
        val lines = mutableListOf<String>()
        val decisions = DecisionAuditJoin.indexOf(database.decisionRecordDao().getAllDecisionRecords())
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        // Parquet header
        lines.add("prediction_id,symbol,horizon,model_type,issued_timestamp,target_timestamp,predicted_price,realized_price,error_pct,cone_status,learning_action,decision_state,decision_confidence,decision_source,decision_reason")
        for (item in all) {
            val realizedStr = if (item.settled && item.realizedPrice != null) Formatters.formatRawPrice5Dec(item.realizedPrice) else ""
            val errorStr = if (item.settled && item.errorPct != null) String.format(Locale.US, "%.2f", item.errorPct) else ""
            val coneStr = if (item.settled) (if (item.inCone == true) "HIT" else "BREACH") else "PENDING"
            val decision = DecisionAuditJoin.lookup(decisions, item.symbol, item.issuedTimestamp)
            lines.add(
                "${item.id},${item.symbol},${item.horizonLabel},${item.modelType},${item.modelProvider},${timeFmt.format(Date(item.issuedTimestamp))},${timeFmt.format(Date(item.targetTimestamp))},${Formatters.formatRawPrice5Dec(item.predictedPrice)},$realizedStr,$errorStr,$coneStr,${item.learningAction}," +
                    "${decision?.state ?: ""},${DecisionAuditJoin.confidenceCell(decision)}," +
                    "${decision?.source ?: ""},${DecisionAuditJoin.singleLineReason(decision)}"
            )
        }
        return@withContext lines.joinToString("\n")
    }

    /** Export audit ledger with all formats (CSV, JSON, Parquet) */
    suspend fun exportAuditLedgerAllFormats(): Triple<String, String, String> = withContext(Dispatchers.IO) {
        val csv = exportAuditLedgerCsv()
        val json = exportPredictionLedgerJson()
        val parquet = exportPredictionLedgerParquet()
        return@withContext Triple(csv, json, parquet)
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

        // TAB 2: INSTITUTIONAL FILTERED (sorted by Vol/OI confluence strength)
        sb.append("=== TAB 2: INSTITUTIONAL 4-FILTER PASSING ASSETS ===\n")
        sb.append("Symbol,Last Price,Today Volume,Exact OI,Vol/OI Ratio,Taker Buy %,Displacement %,10D Pass Count,Status\n")
        for (c in step1Passed.sortedByDescending { it.volToOiRatio }) {
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
    val executiveBrief: String,
    /** §29 verdict for this evaluation: state, calibrated confidence and the reason. */
    val decision: DecisionReport,
    /** §74 technical confluence screeners evaluated on this snapshot's bars. */
    val screenerSignals: List<ScreenerSignal> = emptyList(),
    /** §74 quantitative strategy verdicts with their own entry / exit / invalidation levels. */
    val strategySignals: List<StrategySignal> = emptyList(),
    /** D7: the catalogue ideas the active regime permitted for this evaluation. */
    val catalogueCandidates: List<TradeIdea> = emptyList(),
    /** §3: the one-line liquidity magnet shown beneath the spot price. */
    val painPathPrior: String? = null
)

/** Percent move of each ensemble leg at one horizon, as the §29 router reads them. */
private data class ForecastLegs(
    val kinematicPct: Double,
    val tsfmPct: Double
)
