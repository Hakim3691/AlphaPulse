package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.db.ScreenedCoinEntity
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.remote.BinanceFuturesClient
import com.aistudio.alphapulse.data.remote.BinanceWebSocketClient
import com.aistudio.alphapulse.data.remote.CoinGlassClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Universe emission that also states WHERE the data came from (Spec §4 honesty). */
data class UniverseSnapshot(
    val assets: List<CryptoAsset>,
    val isLive: Boolean
)

/**
 * The live market-data layer for the campaign engine.
 *
 * Universe source
 * ---------------
 * The coin list is **no longer discovered here**. AlphaPulse runs its four-filter
 * institutional screen upstream and persists the survivors; [universeFrom] maps that
 * snapshot through [ScreenedUniverseBridge]. Crypto Trader Pro's own discovery
 * (perp enumeration, spot/CoinGlass inclusion, $50M-$750M volume band, L2 spread
 * filter, candle-derived bias) was removed because AlphaPulse had already applied a
 * stricter selection and forecast the outcome.
 *
 * Streaming
 * ---------
 * §5 data throttling:
 *  - Dormant polling: the caller supplies a light refresh of the screened snapshot.
 *  - Active delta mode: a direct WebSocket for only the staged symbol's book ticker,
 *    plus a light REST depth poll for the real L2 ladder.
 *
 * No simulated fallbacks: when a feed is offline the flow goes quiet and the last
 * real snapshot stays on screen.
 */
class LiveMarketDataProvider(
    private val bridge: ScreenedUniverseBridge = ScreenedUniverseBridge()
) {

    companion object {
        const val TICKS_PER_EVIDENCE_FRAME = 25
        const val ORDER_BOOK_POLL_INTERVAL_MS = 2_000L
        const val DEPTH_LEVELS = 12
        const val BOOK_LADDER_LEVELS = 6
        const val DORMANT_POLL_INTERVAL_MS = 20_000L
    }

    /** Last successfully bridged universe (kept for evidence-frame context). */
    @Volatile
    var lastUniverse: List<CryptoAsset> = emptyList()
        private set

    /**
     * Maps one AlphaPulse screen + forecast snapshot onto the engine's universe.
     * Returns an empty list when AlphaPulse has not screened anything yet, so the
     * caller can decide whether to show its labelled seed placeholder.
     */
    fun universeFrom(
        screened: List<ScreenedCoinEntity>,
        forecasts: Map<String, List<PredictionEntity>>
    ): UniverseSnapshot {
        val assets = bridge.toCryptoAssets(screened, forecasts)
        if (assets.isNotEmpty()) lastUniverse = assets
        return UniverseSnapshot(assets, isLive = assets.isNotEmpty())
    }

    /**
     * Dormant polling over whatever source the caller owns. The engine no longer
     * fetches a universe on its own schedule, so the refresh cadence lives with the
     * snapshot owner (the ViewModel reading AlphaPulse's store).
     */
    fun dormantUniversePolling(
        load: suspend () -> UniverseSnapshot,
        intervalMs: Long = DORMANT_POLL_INTERVAL_MS
    ): Flow<UniverseSnapshot> = flow {
        while (true) {
            emit(load())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /** One-shot universe load for the first frame. */
    suspend fun loadUniverseOnce(load: suspend () -> UniverseSnapshot): UniverseSnapshot = load()

    /**
     * §5 Active Delta Mode: price/OI/funding deltas for the selected symbol only.
     * Price ticks come from the real bookTicker WS; OI/funding requote over REST.
     */
    fun activeDeltaStream(asset: CryptoAsset): Flow<CryptoAsset> = flow {
        var current = asset
        var tickCount = 0
        var lastOi = asset.openInterestUsd
        var lastFunding = asset.fundingRatePct

        BinanceWebSocketClient.streamBookTicker(asset.symbol).collect { delta ->
            current = current.copy(
                lastPrice = delta.midPrice,
                orderBookSpreadPct = delta.spreadPct
            )
            tickCount++
            if (tickCount % TICKS_PER_EVIDENCE_FRAME == 0) {
                runCatching {
                    BinanceFuturesClient.fetchOpenInterest(asset.symbol)?.let { oiCoin ->
                        lastOi = oiCoin * current.lastPrice
                    }
                    CoinGlassClient.fetchFundingRates()[asset.symbol]?.first?.let { lastFunding = it }
                }
            }
            emit(current.copy(openInterestUsd = lastOi, fundingRatePct = lastFunding))
        }
    }

    /**
     * Real L2 book via a light REST depth poll (Binance futures /depth). Real bids and
     * asks, real spread. Quiet when the request fails.
     */
    fun orderBookStream(asset: CryptoAsset): Flow<OrderBookSnapshot> = flow {
        while (true) {
            val depth = runCatching {
                BinanceFuturesClient.fetchDepth(asset.symbol, DEPTH_LEVELS)
            }.getOrNull()
            if (depth != null && depth.bids.isNotEmpty() && depth.asks.isNotEmpty()) {
                val bestBid = depth.bids.first().first
                val bestAsk = depth.asks.first().first
                val mid = (bestBid + bestAsk) / 2.0
                val spreadPct = if (mid > 0.0) (bestAsk - bestBid) / mid * 100.0 else 0.0
                emit(
                    OrderBookSnapshot(
                        asset = asset.symbol,
                        bids = depth.bids.take(BOOK_LADDER_LEVELS).map { OrderBookLevel(it.first, it.second) },
                        asks = depth.asks.take(BOOK_LADDER_LEVELS).map { OrderBookLevel(it.first, it.second) },
                        spreadPct = Math.round(spreadPct * 1000.0) / 1000.0
                    )
                )
            }
            delay(ORDER_BOOK_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    /** Real taker-trade tape (aggTrade WS). No simulated tape when offline. */
    fun takerTradeStream(asset: CryptoAsset): Flow<TakerTrade> = flow {
        BinanceWebSocketClient.streamAggTrades(asset.symbol).collect { delta ->
            emit(
                TakerTrade(
                    id = delta.tradeTimeUtcMs,
                    timestamp = delta.tradeTimeUtcMs,
                    price = delta.price,
                    qty = delta.qty,
                    isBuyerMaker = delta.isBuyerMaker
                )
            )
        }
    }

    /**
     * Evidence frame from the asset's own readings. The candle-derived inputs
     * (normalized tick, corpus OI) belong to the removed discovery pass, so this
     * reports the neutral tick and takes derivatives context from the asset.
     */
    fun buildEvidenceFrame(asset: CryptoAsset): EvidenceFrame =
        FeatureEngine().buildEvidenceFrame(
            asset = asset,
            normalizedTick = com.aistudio.alphapulse.data.model.NormalizedTick.NO_DATA,
            currentRegime = asset.regime,
            oiFromCorpus = asset.openInterestUsd.takeIf { it > 0.0 }
        )
}
