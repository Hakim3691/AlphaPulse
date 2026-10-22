/**
 * FailoverExchangeProvider - Wrapper that provides automatic failover between
 * Binance, Bybit, and Hyperliquid providers for maximum uptime.
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
import com.aistudio.alphapulse.domain.quant.KlineData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

class FailoverExchangeProvider(
    private val binanceProvider: BinanceProvider = BinanceProvider(),
    private val bybitProvider: BybitProvider = BybitProvider(),
    private val hyperliquidProvider: HyperliquidProvider = HyperliquidProvider()
) : ExchangeDataProvider {

    enum class ProviderSource(val label: String) {
        BINANCE("Binance"),
        BYBIT("Bybit"),
        HYPERLIQUID("Hyperliquid")
    }

    private val _watchedSymbols = MutableStateFlow<List<String>>(emptyList())
    override val watchedSymbols: StateFlow<List<String>> = _watchedSymbols.asStateFlow()

    private val _currentProvider = MutableStateFlow<ProviderSource>(ProviderSource.BINANCE)
    val currentProvider: StateFlow<ProviderSource> = _currentProvider.asStateFlow()

    private val failureCounts = mutableMapOf<ProviderSource, AtomicInteger>().apply {
        ProviderSource.values().forEach { put(it, AtomicInteger(0)) }
    }
    private val lastFailureTime = mutableMapOf<ProviderSource, Long>()
    private val COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
    private val MAX_FAILURES = 3

    private val providers = mapOf(
        ProviderSource.BINANCE to binanceProvider,
        ProviderSource.BYBIT to bybitProvider,
        ProviderSource.HYPERLIQUID to hyperliquidProvider
    )

    private fun getActiveProvider(): ExchangeDataProvider {
        return providers[_currentProvider.value]!!
    }

    private fun recordFailure(source: ProviderSource) {
        val count = failureCounts[source]!!.incrementAndGet()
        lastFailureTime[source] = System.currentTimeMillis()
        if (count >= MAX_FAILURES) {
            triggerFailover(source)
        }
    }

    private fun recordSuccess(source: ProviderSource) {
        failureCounts[source]?.set(0)
        lastFailureTime.remove(source)
    }

    private fun triggerFailover(failedSource: ProviderSource) {
        val order = listOf(ProviderSource.BINANCE, ProviderSource.BYBIT, ProviderSource.HYPERLIQUID)
        val currentIndex = order.indexOf(_currentProvider.value)
        for (i in 1 until order.size) {
            val nextIndex = (currentIndex + i) % order.size
            val candidate = order[nextIndex]
            val lastFail = lastFailureTime[candidate] ?: 0L
            if (System.currentTimeMillis() - lastFail > COOLDOWN_MS && failureCounts[candidate]!!.get() < MAX_FAILURES) {
                _currentProvider.value = candidate
                // Restart streaming on new provider
                _watchedSymbols.value.forEach { symbol ->
                    val provider = providers[candidate]
                    when (provider) {
                        is BinanceProvider -> provider.startStreaming(symbol)
                        is BybitProvider -> provider.startStreaming(symbol)
                        is HyperliquidProvider -> provider.startStreaming(symbol)
                    }
                }
                return
            }
        }
    }

    override fun getTickerFlow(symbol: String): Flow<TickerSnapshot> = flow {
        val provider = getActiveProvider()
        provider.getTickerFlow(symbol).collect { ticker ->
            try {
                recordSuccess(_currentProvider.value)
                emit(ticker)
            } catch (e: Exception) {
                recordFailure(_currentProvider.value)
                // Try next provider
                val nextProvider = getNextAvailableProvider()
                if (nextProvider != null) {
                    nextProvider.getTickerFlow(symbol).collect { t -> emit(t) }
                } else {
                    emit(ticker) // Emit anyway as fallback
                }
            }
        }
    }

    override fun getDepthFlow(symbol: String): Flow<DepthSnapshot> = flow {
        val provider = getActiveProvider()
        provider.getDepthFlow(symbol).collect { depth ->
            try {
                recordSuccess(_currentProvider.value)
                emit(depth)
            } catch (e: Exception) {
                recordFailure(_currentProvider.value)
                // Try failover
                val nextProvider = getNextAvailableProvider()
                if (nextProvider != null) {
                    nextProvider.getDepthFlow(symbol).collect { d -> emit(d) }
                } else {
                    emit(depth)
                }
            }
        }
    }

    override fun getAggTradeFlow(symbol: String): Flow<AggTradeEvent> = flow {
        val provider = getActiveProvider()
        provider.getAggTradeFlow(symbol).collect { trade ->
            try {
                recordSuccess(_currentProvider.value)
                emit(trade)
            } catch (e: Exception) {
                recordFailure(_currentProvider.value)
                val nextProvider = getNextAvailableProvider()
                if (nextProvider != null) {
                    nextProvider.getAggTradeFlow(symbol).collect { t -> emit(t) }
                } else {
                    emit(trade)
                }
            }
        }
    }

    override fun getFundingRateFlow(symbol: String): Flow<FundingRateEvent> = flow {
        val provider = getActiveProvider()
        provider.getFundingRateFlow(symbol).collect { fr ->
            try {
                recordSuccess(_currentProvider.value)
                emit(fr)
            } catch (e: Exception) {
                recordFailure(_currentProvider.value)
                val nextProvider = getNextAvailableProvider()
                if (nextProvider != null) {
                    nextProvider.getFundingRateFlow(symbol).collect { f -> emit(f) }
                } else {
                    emit(fr)
                }
            }
        }
    }

    override fun getLiquidationFlow(symbol: String): Flow<LiquidationEvent> = flow {
        val provider = getActiveProvider()
        provider.getLiquidationFlow(symbol).collect { liq ->
            try {
                recordSuccess(_currentProvider.value)
                emit(liq)
            } catch (e: Exception) {
                recordFailure(_currentProvider.value)
                val nextProvider = getNextAvailableProvider()
                if (nextProvider != null) {
                    nextProvider.getLiquidationFlow(symbol).collect { l -> emit(l) }
                } else {
                    emit(liq)
                }
            }
        }
    }

    override fun getOpenInterestFlow(symbol: String): Flow<OpenInterestEvent> = flow {
        val provider = getActiveProvider()
        provider.getOpenInterestFlow(symbol).collect { oi ->
            try {
                recordSuccess(_currentProvider.value)
                emit(oi)
            } catch (e: Exception) {
                recordFailure(_currentProvider.value)
                val nextProvider = getNextAvailableProvider()
                if (nextProvider != null) {
                    nextProvider.getOpenInterestFlow(symbol).collect { o -> emit(o) }
                } else {
                    emit(oi)
                }
            }
        }
    }

    override fun getKlineFlow(symbol: String, interval: String): Flow<List<KlineData>> = flow {
        val provider = getActiveProvider()
        provider.getKlineFlow(symbol, interval).collect { klines ->
            try {
                recordSuccess(_currentProvider.value)
                emit(klines)
            } catch (e: Exception) {
                recordFailure(_currentProvider.value)
                val nextProvider = getNextAvailableProvider()
                if (nextProvider != null) {
                    nextProvider.getKlineFlow(symbol, interval).collect { k -> emit(k) }
                } else {
                    emit(klines)
                }
            }
        }
    }

    override fun isSymbolActive(symbol: String): Boolean {
        // Check all providers in order
        val order = listOf(ProviderSource.BINANCE, ProviderSource.BYBIT, ProviderSource.HYPERLIQUID)
        for (source in order) {
            if (failureCounts[source]!!.get() < MAX_FAILURES) {
                val provider = providers[source]!!
                if (provider.isSymbolActive(symbol)) {
                    return true
                }
            }
        }
        return false
    }

    override fun checkHealth(): Boolean {
        return providers.values.any { it.checkHealth() }
    }

    private fun getNextAvailableProvider(): ExchangeDataProvider? {
        val order = listOf(ProviderSource.BINANCE, ProviderSource.BYBIT, ProviderSource.HYPERLIQUID)
        val currentIndex = order.indexOf(_currentProvider.value)
        for (i in 1 until order.size) {
            val nextIndex = (currentIndex + i) % order.size
            val candidate = order[nextIndex]
            if (failureCounts[candidate]!!.get() < MAX_FAILURES) {
                _currentProvider.value = candidate
                return providers[candidate]
            }
        }
        return null
    }

    fun startStreaming(symbol: String) {
        _watchedSymbols.value.ifEmpty { return }
        _currentProvider.value = ProviderSource.BINANCE // Reset to primary
        providers.values.forEach { provider ->
            when (provider) {
                is BinanceProvider -> provider.startStreaming(symbol)
                is BybitProvider -> provider.startStreaming(symbol)
                is HyperliquidProvider -> provider.startStreaming(symbol)
            }
        }
    }

    fun stopStreaming() {
        providers.values.forEach { provider ->
            when (provider) {
                is BinanceProvider -> provider.stopStreaming()
                is BybitProvider -> provider.stopStreaming()
                is HyperliquidProvider -> provider.stopStreaming()
            }
        }
    }

    fun updateWatchedSymbols(symbols: List<String>) {
        _watchedSymbols.value = symbols
        providers.values.forEach { provider ->
            when (provider) {
                is BinanceProvider -> provider.updateWatchedSymbols(symbols)
                is BybitProvider -> provider.updateWatchedSymbols(symbols)
                is HyperliquidProvider -> provider.updateWatchedSymbols(symbols)
            }
        }
    }

    fun getBinanceProvider(): BinanceProvider = binanceProvider
    fun getBybitProvider(): BybitProvider = bybitProvider
    fun getHyperliquidProvider(): HyperliquidProvider = hyperliquidProvider
}