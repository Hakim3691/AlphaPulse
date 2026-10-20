/***
 * ExchangeDataProvider - Abstraction layer for market data ingestion.
 * Decouples the quantitative engine from exchange-specific API details.
 * Supports Binance, Bybit, and Hyperliquid providers through a common contract.
 *
 * Spec Ref: FR-004 §2, FR-005 §2, Gap §2 line 14
 */

package com.aistudio.alphapulse.domain.data

import com.aistudio.alphapulse.data.model.ScreenedCoin
import com.aistudio.alphapulse.domain.quant.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.List

/***
 * Core contract for all exchange data providers.
 * The quantitative engine depends on this interface, not concrete exchange implementations.
 */
interface ExchangeDataProvider {

    /** Current market symbols under observation */
    val watchedSymbols: StateFlow<List<String>>

    /** 24hr ticker snapshot for a given symbol */
    fun getTickerFlow(symbol: String): Flow<TickerSnapshot>

    /** Order book depth stream (full L2/L3 reconstruction) */
    fun getDepthFlow(symbol: String): Flow<DepthSnapshot>

    /** Aggregate trade stream (rolling trade buffer) */
    fun getAggTradeFlow(symbol: String): Flow<AggTradeEvent>

    /** Funding rate stream */
    fun getFundingRateFlow(symbol: String): Flow<FundingRateEvent>

    /** Liquidation event stream */
    fun getLiquidationFlow(symbol: String): Flow<LiquidationEvent>

    /** Open interest stream */
    fun getOpenInterestFlow(symbol: String): Flow<OpenInterestEvent>

    /** Kline/candle historical data stream */
    fun getKlineFlow(symbol: String, interval: String): Flow<List<KlineData>>

    /** Symbol availability check */
    fun isSymbolActive(symbol: String): Boolean

    /** Exchange-specific health check */
    fun checkHealth(): Boolean
}

/** Ticker snapshot returned by the provider */
data class TickerSnapshot(
    val symbol: String,
    val lastPrice: Double,
    val priceChangePercent: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val volume: Double,
    val quoteVolume: Double,
    val openPrice: Double,
    val timestamp: Long
)

/** Aggregate trade event from the WS stream */
data class AggTradeEvent(
    val tradeId: Long,
    val price: Double,
    val quantity: Double,
    val quoteVolume: Double,
    val buyerMaker: Boolean,
    val timestamp: Long,
    val symbol: String
)

/** Funding rate event */
data class FundingRateEvent(
    val symbol: String,
    val fundingRate: Double,
    val timestamp: Long
)

/** Liquidation event */
data class LiquidationEvent(
    val price: Double,
    val notional: Double,
    val side: String, // "long" or "short"
    val timestamp: Long,
    val symbol: String
)

/** Open interest event */
data class OpenInterestEvent(
    val symbol: String,
    val openInterest: Double,
    val timestamp: Long
)