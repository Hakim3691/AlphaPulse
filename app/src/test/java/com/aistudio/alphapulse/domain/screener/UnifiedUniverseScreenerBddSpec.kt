package com.aistudio.alphapulse.domain.screener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BDD Guard Spec for §65 and Finalized Contract C-17:
 * Unified Institutional Universe Swap (AlphaPulse §3-§4 replacing ProactiveMS Mid-Cap Screener).
 */
class UnifiedUniverseScreenerBddSpec {

    data class DailyFilterSnapshot(
        val quoteVolume24hUsdt: Double,
        val takerBuyRatio: Double,
        val openInterestUsdt: Double,
        val openPrice: Double,
        val closePrice: Double,
        val highPrice: Double,
        val lowPrice: Double
    ) {
        val volumeToOiVelocity: Double
            get() = if (openInterestUsdt > 0.0) quoteVolume24hUsdt / openInterestUsdt else 0.0

        val convictionDisplacement: Double
            get() {
                val range = highPrice - lowPrice
                return if (range > 0.0) kotlin.math.abs(closePrice - openPrice) / range else 0.0
            }

        fun passesFourFilterGate(): Boolean {
            return quoteVolume24hUsdt >= 5_000_000.0 &&
                takerBuyRatio >= 0.50 &&
                volumeToOiVelocity > 1.0 &&
                convictionDisplacement >= 0.40
        }
    }

    data class CandidateAssetInput(
        val symbol: String,
        val todaySnapshot: DailyFilterSnapshot,
        val last10DaysSnapshots: List<DailyFilterSnapshot>,
        val annualizedReturn1Y: Double,
        val maxDrawdown1Y: Double,
        val annualizedVolatility1Y: Double,
        val orderBookSpreadPct: Double,
        val hasBinanceSpotAndFutures: Boolean,
        val hasCoinGlassFuturesFeed: Boolean,
        val hasActiveFundingHistory: Boolean
    ) {
        val calmarRatio1Y: Double
            get() = if (maxDrawdown1Y > 0.0) annualizedReturn1Y / maxDrawdown1Y else -1.0

        fun sharpeRatio1Y(riskFreeRate: Double = 0.04): Double {
            return if (annualizedVolatility1Y > 0.0) {
                (annualizedReturn1Y - riskFreeRate) / annualizedVolatility1Y
            } else {
                -1.0
            }
        }
    }

    data class ScreenedAssetVerdict(
        val symbol: String,
        val verifiedInstitutionalFlow: Boolean,
        val passesRiskAdjustedHurdle: Boolean,
        val passiveStagingEligible: Boolean,
        val calmar: Double,
        val sharpe: Double
    )

    class UnifiedUniverseScreener(
        private val riskFreeRate: Double = 0.04,
        private val requireStrict8Of10PersistenceWhenTodayFails: Boolean = true
    ) {
        fun screenUniverse(
            candidates: List<CandidateAssetInput>,
            btcOnlyOverride: Boolean = false
        ): List<ScreenedAssetVerdict> {
            val filtered = candidates.mapNotNull { candidate ->
                if (!candidate.hasBinanceSpotAndFutures || !candidate.hasCoinGlassFuturesFeed) {
                    return@mapNotNull null
                }
                val passesToday = candidate.todaySnapshot.passesFourFilterGate()
                val historicalPassDays = candidate.last10DaysSnapshots.count { it.passesFourFilterGate() }
                val minHistoricalDays = if (requireStrict8Of10PersistenceWhenTodayFails) 8 else 1
                val verifiedFlow = passesToday || (historicalPassDays >= minHistoricalDays)

                val calmar = candidate.calmarRatio1Y
                val sharpe = candidate.sharpeRatio1Y(riskFreeRate)
                val passesRiskAdjusted = calmar > 0.0 && sharpe > 0.0

                if (!verifiedFlow || !passesRiskAdjusted) {
                    return@mapNotNull null
                }

                val stagingEligible = candidate.orderBookSpreadPct < 0.10 && candidate.hasActiveFundingHistory
                ScreenedAssetVerdict(
                    symbol = candidate.symbol,
                    verifiedInstitutionalFlow = true,
                    passesRiskAdjustedHurdle = true,
                    passiveStagingEligible = stagingEligible,
                    calmar = calmar,
                    sharpe = sharpe
                )
            }.sortedByDescending { it.sharpe }

            return if (btcOnlyOverride) {
                filtered.filter { it.symbol == "BTCUSDT" }
            } else {
                filtered
            }
        }
    }

    private fun passingSnapshot(volume: Double = 120_000_000.0) = DailyFilterSnapshot(
        quoteVolume24hUsdt = volume,
        takerBuyRatio = 0.54,
        openInterestUsdt = 60_000_000.0,
        openPrice = 100.0,
        closePrice = 106.0,
        highPrice = 110.0,
        lowPrice = 98.0
    )

    private fun failingSnapshot() = DailyFilterSnapshot(
        quoteVolume24hUsdt = 2_000_000.0,
        takerBuyRatio = 0.42,
        openInterestUsdt = 10_000_000.0,
        openPrice = 100.0,
        closePrice = 100.5,
        highPrice = 105.0,
        lowPrice = 95.0
    )

    @Test
    fun `given high volume mega cap exceeding 750M when evaluated by unified screener then legacy mid cap ceiling is removed and coin qualifies`() {
        val screener = UnifiedUniverseScreener()
        val btcCandidate = CandidateAssetInput(
            symbol = "BTCUSDT",
            todaySnapshot = passingSnapshot(volume = 4_200_000_000.0), // > $750M legacy cap
            last10DaysSnapshots = List(10) { passingSnapshot(volume = 3_500_000_000.0) },
            annualizedReturn1Y = 0.68,
            maxDrawdown1Y = 0.22,
            annualizedVolatility1Y = 0.45,
            orderBookSpreadPct = 0.01,
            hasBinanceSpotAndFutures = true,
            hasCoinGlassFuturesFeed = true,
            hasActiveFundingHistory = true
        )

        val results = screener.screenUniverse(listOf(btcCandidate))

        assertEquals(1, results.size)
        assertEquals("BTCUSDT", results.first().symbol)
        assertTrue(results.first().verifiedInstitutionalFlow)
        assertTrue(results.first().passiveStagingEligible)
    }

    @Test
    fun `given coin failing today gate but passing 8 of last 10 days with positive Calmar and Sharpe when screened then it qualifies via 10 day persistence`() {
        val screener = UnifiedUniverseScreener(requireStrict8Of10PersistenceWhenTodayFails = true)
        val history = List(8) { passingSnapshot() } + List(2) { failingSnapshot() }
        val candidate = CandidateAssetInput(
            symbol = "PAXGUSDT",
            todaySnapshot = failingSnapshot(),
            last10DaysSnapshots = history,
            annualizedReturn1Y = 0.32,
            maxDrawdown1Y = 0.12,
            annualizedVolatility1Y = 0.18,
            orderBookSpreadPct = 0.025,
            hasBinanceSpotAndFutures = true,
            hasCoinGlassFuturesFeed = true,
            hasActiveFundingHistory = true
        )

        val results = screener.screenUniverse(listOf(candidate))

        assertEquals(1, results.size)
        assertEquals("PAXGUSDT", results.first().symbol)
        assertTrue(results.first().calmar > 0.0)
        assertTrue(results.first().sharpe > 0.0)
    }

    @Test
    fun `given coin passing 4 filter institutional gate but having negative Sharpe or Calmar when screened then it is rejected`() {
        val screener = UnifiedUniverseScreener(riskFreeRate = 0.04)
        val lowReturnCoin = CandidateAssetInput(
            symbol = "WEAKUSDT",
            todaySnapshot = passingSnapshot(),
            last10DaysSnapshots = List(10) { passingSnapshot() },
            annualizedReturn1Y = 0.02, // Below 4% risk-free rate -> Sharpe < 0
            maxDrawdown1Y = 0.25,
            annualizedVolatility1Y = 0.40,
            orderBookSpreadPct = 0.03,
            hasBinanceSpotAndFutures = true,
            hasCoinGlassFuturesFeed = true,
            hasActiveFundingHistory = true
        )

        val results = screener.screenUniverse(listOf(lowReturnCoin))

        assertTrue(results.isEmpty())
    }

    @Test
    fun `given verified coin with order book spread above 0_10 percent when screened then passive campaign staging is flagged false`() {
        val screener = UnifiedUniverseScreener()
        val wideSpreadCoin = CandidateAssetInput(
            symbol = "SOPHUSDT",
            todaySnapshot = passingSnapshot(),
            last10DaysSnapshots = List(10) { passingSnapshot() },
            annualizedReturn1Y = 0.55,
            maxDrawdown1Y = 0.20,
            annualizedVolatility1Y = 0.35,
            orderBookSpreadPct = 0.14, // Breaches < 0.10% execution guardrail
            hasBinanceSpotAndFutures = true,
            hasCoinGlassFuturesFeed = true,
            hasActiveFundingHistory = true
        )

        val results = screener.screenUniverse(listOf(wideSpreadCoin))

        assertEquals(1, results.size)
        assertFalse(results.first().passiveStagingEligible)
    }

    @Test
    fun `given btc only override is toggled on when screening multi coin universe then only BTCUSDT is emitted to both tabs`() {
        val screener = UnifiedUniverseScreener()
        val coins = listOf("BTCUSDT", "PAXGUSDT", "XAUTUSDT", "SOPHUSDT").map { sym ->
            CandidateAssetInput(
                symbol = sym,
                todaySnapshot = passingSnapshot(),
                last10DaysSnapshots = List(10) { passingSnapshot() },
                annualizedReturn1Y = 0.45,
                maxDrawdown1Y = 0.15,
                annualizedVolatility1Y = 0.30,
                orderBookSpreadPct = 0.02,
                hasBinanceSpotAndFutures = true,
                hasCoinGlassFuturesFeed = true,
                hasActiveFundingHistory = true
            )
        }

        val results = screener.screenUniverse(coins, btcOnlyOverride = true)

        assertEquals(1, results.size)
        assertEquals("BTCUSDT", results.first().symbol)
    }
}
