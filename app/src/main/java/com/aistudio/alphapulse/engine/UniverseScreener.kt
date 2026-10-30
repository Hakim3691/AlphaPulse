package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.local.CandleDao
import com.aistudio.alphapulse.data.local.CandleEntity
import com.aistudio.alphapulse.data.model.CoinCalibration
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.remote.BinanceFuturesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Candle access for structure detection and per-coin calibration.
 *
 * The universe-screening half of this class was removed. Crypto Trader Pro used to
 * discover the perp universe here (exchangeInfo, spot/CoinGlass inclusion, the
 * $50M-$750M volume band, L2 spread measurement, candle-derived bias and pain-score
 * ranking). AlphaPulse already performs a stricter screen upstream and forecasts the
 * price per horizon, so re-running that pass duplicated work and could contradict the
 * list the app displays. [ScreenedUniverseBridge] is the replacement source.
 *
 * What remains is the piece AlphaPulse does not provide: the §5 Room-first candle
 * cache that structure detection and calibration read from.
 */
class UniverseScreener(private val candleDao: CandleDao) {

    companion object {
        /** §5 Heavy Data staleness window for HTF context. */
        const val HTF_STALENESS_MS: Long = 4L * 60L * 60L * 1000L
    }

    /** Offline seed universe for the clearly-labeled first-frame placeholder. */
    private val seedRepository = MarketDataRepository()

    fun offlineSeedUniverse(): List<CryptoAsset> = seedRepository.getInitialUniverse()

    /** The three decision timeframes (§7) used by structure detection and calibration. */
    data class TimeframeCandles(
        val candles4h: List<BinanceFuturesClient.Candle>,
        val candles1h: List<BinanceFuturesClient.Candle>,
        val candles5m: List<BinanceFuturesClient.Candle>
    )

    /**
     * Loads the HTF/MTF/LTF candle set for one symbol through the §5 Room-first cache,
     * so structure and calibration share one data path.
     */
    suspend fun loadTimeframes(symbol: String): TimeframeCandles = TimeframeCandles(
        candles4h = loadCandles(symbol, "4h", 120),
        candles1h = loadCandles(symbol, "1h", 120),
        candles5m = loadCandles(symbol, "5m", 120)
    )

    /** §10 coin-specific calibration from real 1h (behaviour) and 5m (execution) history. */
    suspend fun loadCalibration(asset: CryptoAsset): CoinCalibration =
        IndicatorMath.computeCalibration(
            symbol = asset.symbol,
            candles1h = loadCandles(asset.symbol, "1h", 120),
            candles5m = loadCandles(asset.symbol, "5m", 120),
            atr5m = asset.atr5m,
            spreadPct = asset.orderBookSpreadPct
        )

    /**
     * §5 Heavy Data: candles are served from the local store when fresh (< 4h); only a
     * stale or empty store triggers a network download, and results are persisted.
     */
    private suspend fun loadCandles(
        symbol: String,
        interval: String,
        limit: Int
    ): List<BinanceFuturesClient.Candle> = withContext(Dispatchers.IO) {
        val cached = runCatching { candleDao.getCandles(symbol, interval) }.getOrDefault(emptyList())
        val latest = runCatching { candleDao.getLatestFetchedAtMs(symbol, interval) }.getOrNull()
        val fresh = cached.isNotEmpty() && latest != null &&
            (System.currentTimeMillis() - latest) < HTF_STALENESS_MS
        if (fresh) {
            return@withContext cached.takeLast(limit).map { it.toDomain() }
        }
        val fetched = BinanceFuturesClient.fetchKlines(symbol, interval, limit)
        if (fetched.isNotEmpty()) {
            val now = System.currentTimeMillis()
            runCatching {
                candleDao.insertCandles(
                    fetched.map {
                        CandleEntity(
                            id = "$symbol|$interval|${it.openTimeUtcMs}",
                            symbol = symbol,
                            interval = interval,
                            openTimeUtcMs = it.openTimeUtcMs,
                            closeTimeUtcMs = it.closeTimeUtcMs,
                            open = it.open,
                            high = it.high,
                            low = it.low,
                            close = it.close,
                            volume = it.volume,
                            quoteVolume = it.quoteVolume,
                            fetchedAtMs = now
                        )
                    }
                )
            }
            return@withContext fetched
        }
        cached.takeLast(limit).map { it.toDomain() }
    }

    private fun CandleEntity.toDomain() = BinanceFuturesClient.Candle(
        openTimeUtcMs = openTimeUtcMs,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        closeTimeUtcMs = closeTimeUtcMs,
        quoteVolume = quoteVolume
    )
}
