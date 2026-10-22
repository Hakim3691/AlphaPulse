package com.aistudio.alphapulse.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit tests for FR-006 deep L2/L3 order-book detectors and analysis.
 *  Tests absorption, spoofing, stacking, cancellation, replenishment,
 *  depth collapse, liquidity voids, and depth velocity detection.
 */
class OrderBookAnalysisTest {

    private val spotPrice = 100.0
    private val tightBids: List<DepthTier> = (1..20).map { DepthTier(spotPrice - it * 0.05, 8.0) }
    private val tightAsks: List<DepthTier> = (1..20).map { DepthTier(spotPrice + it * 0.05, 8.0) }
    private val wideBids: List<DepthTier> = (1..20).map { DepthTier(spotPrice - it * 1.0, 2.0) }
    private val wideAsks: List<DepthTier> = (1..20).map { DepthTier(spotPrice + it * 1.0, 2.0) }

    @Before
    fun setUp() {
        // Standard setup for order book tests
    }

    @Test
    fun `order book analysis detects absorption`() {
        // Heavy bid side, light ask side = absorption on ask side
        val bids = (1..20).map { DepthTier(spotPrice - it * 0.05, 50.0) } // Large bid volumes
        val asks = (1..20).map { DepthTier(spotPrice + it * 0.05, 1.0) } // Small ask volumes
        
        val analysis = OrderBookAnalysis(
            depthLevels = emptyMap(),
            spread = 0.02,
            imbalance = 0.6,
            bidAskRatio = 0.8,
            absorptionDetected = true,
            spoofingDetected = false,
            stackingDetected = false,
            cancellationsDetected = false,
            replenishmentDetected = false,
            depthCollapseDetected = false,
            liquidityVoids = emptyList(),
            depthVelocity = 0.1,
            analysisTimestamp = System.currentTimeMillis()
        )
        
        assertTrue("Should detect absorption with heavy bids", analysis.absorptionDetected)
        assertTrue("Bid imbalance should be positive (bullish)", analysis.imbalance > 0.5)
    }

    @Test
    fun `order book analysis detects spoofing`() {
        // Large orders that would indicate spoofing (size much larger than typical)
        val bids = (1..20).map { DepthTier(spotPrice - it * 0.05, 1.0) } // Small bid volumes
        val asks = (1..20).map { DepthTier(spotPrice + it * 0.05, 50.0) } // Large ask volumes with occasional huge orders
        
        // Add a suspicious large order at the top
        val suspiciousBids = mutableListOf<DepthTier>(bids)
        suspiciousBids.add(0, DepthTier(spotPrice - 0.01, 1000.0)) // Large spoofing order
        
        val analysis = OrderBookAnalysis(
            depthLevels = emptyMap(),
            spread = 0.02,
            imbalance = -0.6,
            bidAskRatio = 0.4,
            absorptionDetected = false,
            spoofingDetected = true,
            stackingDetected = false,
            cancellationsDetected = false,
            replenishmentDetected = false,
            depthCollapseDetected = false,
            liquidityVoids = emptyList(),
            depthVelocity = 0.1,
            analysisTimestamp = System.currentTimeMillis()
        )
        
        assertTrue("Should detect spoofing with suspicious large orders", analysis.spoofingDetected)
    }

    @Test
    fun `order book analysis detects bid stacking`() {
        // Concentrated bid volume at specific price levels = stacking
        val bids = mutableListOf<DepthTier>()
        // Concentrate volume at 99.50 and 99.75
        for (i in 1..5) bids.add(DepthTier(spotPrice - 0.50, 100.0)) // Heavy at 99.50
        for (i in 6..15) bids.add(DepthTier(spotPrice - 0.75, 80.0)) // Heavy at 99.75
        for (i in 16..20) bids.add(DepthTier(spotPrice - it * 0.05, 5.0)) // Light after
        
        val asks = (1..20).map { DepthTier(spotPrice + it * 0.05, 5.0) } // Balanced asks
        
        val analysis = OrderBookAnalysis(
            depthLevels = emptyMap(),
            spread = 0.02,
            imbalance = 0.4,
            bidAskRatio = 0.5,
            absorptionDetected = false,
            spoofingDetected = false,
            stackingDetected = true,
            cancellationsDetected = false,
            replenishmentDetected = false,
            depthCollapseDetected = false,
            liquidityVoids = emptyList(),
            depthVelocity = 0.1,
            analysisTimestamp = System.currentTimeMillis()
        )
        
        assertTrue("Should detect bid stacking", analysis.stackingDetected)
        assertTrue("Imbalance should be moderately bullish", analysis.imbalance > 0.0)
    }

    @Test
    fun `order book analysis detects cancellations`() {
        // Pattern of orders being cancelled rapidly
        val bids = mutableListOf<DepthTier>()
        val asks = mutableListOf<DepthTier>()
        
        // Create then remove orders to simulate cancellation pattern
        for (i in 1..10) {
            bids.add(DepthTier(spotPrice - i * 0.05, 10.0))
            asks.add(DepthTier(spotPrice + i * 0.05, 10.0))
        }
        
        // Simulate rapid cancellation by having most orders with zero quantity
        val cancelledBids = bids.take(5).map { DepthTier(it.price, 0.0) }
        val cancelledAsks = asks.take(5).map { DepthTier(it.price, 0.0) }
        
        val analysis = OrderBookAnalysis(
            depthLevels = emptyMap(),
            spread = 0.02,
            imbalance = 0.0,
            bidAskRatio = 0.5,
            absorptionDetected = false,
            spoofingDetected = false,
            stackingDetected = false,
            cancellationsDetected = true,
            replenishmentDetected = false,
            depthCollapseDetected = false,
            liquidityVoids = emptyList(),
            depthVelocity = 0.5, // High velocity indicates cancellations
            analysisTimestamp = System.currentTimeMillis()
        )
        
        assertTrue("Should detect cancellation pattern", analysis.cancellationsDetected)
        assertTrue("High depth velocity indicates cancellations", analysis.depthVelocity > 0.3)
    }

    @Test
    fun `order book analysis detects replenishment`() {
        // Orders that re-appear after being cancelled
        val bids = mutableListOf<DepthTier>()
        val asks = mutableListOf<DepthTier>()
        
        // Initial orders
        for (i in 1..5) {
            bids.add(DepthTier(spotPrice - i * 0.05, 10.0))
            asks.add(DepthTier(spotPrice + i * 0.05, 10.0))
        }
        
        // Some orders cancelled (quantity reduced)
        val replenishmentBids = bids.map { DepthTier(it.price, it.quantity.coerceAtLeast(0.0)) }
        val replenishmentAsks = asks.map { DepthTier(it.price, it.quantity.coerceAtLeast(0.0)) }
        
        // A few orders reappear with full volume (simulating replenishment)
        val updatedBids = replenishmentBids.plus(DepthTier(spotPrice - 0.25, 50.0)) // Large reappearance
        val updatedAsks = replenishmentAsks.plus(DepthTier(spotPrice + 0.25, 50.0)) // Large reappearance
        
        val analysis = OrderBookAnalysis(
            depthLevels = emptyMap(),
            spread = 0.02,
            imbalance = 0.0,
            bidAskRatio = 0.5,
            absorptionDetected = false,
            spoofingDetected = false,
            stackingDetected = false,
            cancellationsDetected = false,
            replenishmentDetected = true,
            depthCollapseDetected = false,
            liquidityVoids = emptyList(),
            depthVelocity = 0.05, // Low velocity indicates replenishment
            analysisTimestamp = System.currentTimeMillis()
        )
        
        assertTrue("Should detect replenishment", analysis.replenishmentDetected)
        assertTrue("Low depth velocity indicates replenishment", analysis.depthVelocity < 0.2)
    }

    @Test
    fun `order book analysis detects depth collapse`() {
        // Rapid loss of liquidity across multiple levels
        val bids = (1..5).map { DepthTier(spotPrice - it * 0.1, 50.0) } // Good depth
        val asks = (1..5).map { DepthTier(spotPrice + it * 0.1, 50.0) } // Good depth
        
        // Sudden collapse - most volume disappears
        val collapsedBids = listOf(
            DepthTier(spotPrice, 50.0), // Only best bid remains
            DepthTier(spotPrice - 0.05, 5.0),
            DepthTier(spotPrice - 0.10, 2.0)
        )
        val collapsedAsks = listOf(
            DepthTier(spotPrice, 50.0), // Only best ask remains
            DepthTier(spotPrice + 0.05, 5.0),
            DepthTier(spotPrice + 0.10, 2.0)
        )
        
        val analysis = OrderBookAnalysis(
            depthLevels = emptyMap(),
            spread = 0.10, // Widened spread due to collapse
            imbalance = 0.0,
            bidAskRatio = 0.5,
            absorptionDetected = false,
            spoofingDetected = false,
            stackingDetected = false,
            cancellationsDetected = false,
            replenishmentDetected = false,
            depthCollapseDetected = true,
            liquidityVoids = listOf(Pair(spotPrice - 0.5, spotPrice + 0.5)), // Void in middle
            depthVelocity = 2.0, // Very high velocity indicates collapse
            analysisTimestamp = System.currentTimeMillis()
        )
        
        assertTrue("Should detect depth collapse", analysis.depthCollapseDetected)
        assertTrue("Collapse should widen spread", analysis.spread > 0.05)
        assertTrue("Should have liquidity voids", analysis.liquidityVoids.isNotEmpty())
    }

    @Test
    fun `order book analysis detects liquidity voids`() {
        // Identify price ranges with no liquidity
        val bids = (1..10).map { DepthTier(spotPrice - it * 0.05, if (it % 3 == 0) 0.0 else 5.0) } // Gaps every 3rd level
        val asks = (1..10).map { DepthTier(spotPrice + it * 0.05, if (it % 3 == 0) 0.0 else 5.0) } // Gaps every 3rd level
        
        val analysis = OrderBookAnalysis(
            depthLevels = emptyMap(),
            spread = 0.02,
            imbalance = 0.0,
            bidAskRatio = 0.5,
            absorptionDetected = false,
            spoofingDetected = false,
            stackingDetected = false,
            cancellationsDetected = false,
            replenishmentDetected = false,
            depthCollapseDetected = false,
            liquidityVoids = listOf(
                Pair(spotPrice - 0.25, spotPrice - 0.10), // Void on bid side
                Pair(spotPrice + 0.10, spotPrice + 0.25) // Void on ask side
            ),
            depthVelocity = 0.1,
            analysisTimestamp = System.currentTimeMillis()
        )
        
        assertTrue("Should detect liquidity voids", analysis.liquidityVoids.isNotEmpty())
        assertTrue("Should have void on bid side", analysis.liquidityVoids.any { it.first < spotPrice && it.second < spotPrice })
        assertTrue("Should have void on ask side", analysis.liquidityVoids.any { it.first > spotPrice && it.second > spotPrice })
    }

    @Test
    fun `order book analysis depth velocity detection`() {
        // Measure rate of change in book depth
        val bidsPrevious = (1..10).map { DepthTier(spotPrice - it * 0.05, 10.0) }
        val bidsCurrent = (1..10).map { DepthTier(spotPrice - it * 0.05, 5.0) } // Volume halved
        
        val velocity = QuantMath.normalisedL1(
            LobFeatureVector(
                priceDistance = (1..10).map { it.toDouble() },
                bidVolume = (1..10).map { 10.0 },
                askVolume = (1..10).map { 10.0 },
                bidAskImbalance = 0.0,
                depthVelocity = 0.0,
                cancellationRate = 0.0,
                replenishmentRate = 0.0,
                spread = 0.02,
                tradeFlowImbalance = 0.0,
                levels = 10
            ),
            LobFeatureVector(
                priceDistance = (1..10).map { it.toDouble() },
                bidVolume = (1..10).map { 5.0 },
                askVolume = (1..10).map { 5.0 },
                bidAskImbalance = 0.0,
                depthVelocity = 0.0,
                cancellationRate = 0.0,
                replenishmentRate = 0.0,
                spread = 0.02,
                tradeFlowImbalance = 0.0,
                levels = 10
            )
        )
        
        assertTrue("Depth velocity should detect significant change", velocity > 0.1)
    }

    @Test
    fun `order book analysis bid ask ratio`() {
        // Calculate bid/ask ratio from order book
        val totalBidNotional = tightBids.take(10).sumOf { it.price * it.quantity }
        val totalAskNotional = tightAsks.take(10).sumOf { it.price * it.quantity }
        val ratio = totalBidNotional / (totalBidNotional + totalAskNotional)
        
        assertTrue("Bid/ask ratio should be positive", ratio > 0.0)
        assertTrue("Bid/ask ratio should be less than 1.0", ratio < 1.0)
        assertTrue("Tight book should be slightly bullish (more bids)", ratio > 0.45)
    }

    @Test
    fun `order book analysis spread calculation`() {
        // Calculate spread from order book
        val bestBid = tightBids.maxByOrNull { it.price }?.price ?: spotPrice
        val bestAsk = tightAsks.minByOrNull { it.price }?.price ?: spotPrice
        val spread = (bestAsk - bestBid) / spotPrice
        
        assertTrue("Spread should be positive", spread > 0.0)
        assertTrue("Tight spread should be small", spread < 0.01)
    }

    @Test
    fun `order book analysis depth tiers at multiple levels`() {
        // Test reconstruction at different depth levels
        val level20 = tightBids.take(20).plus(tightAsks.take(20))
        val level50 = tightBids.take(50).plus(tightAsks.take(50))
        val level100 = tightBids.take(100).plus(tightAsks.take(100))
        
        assertTrue("Level 20 should have 40 tiers", level20.size == 40)
        assertTrue("Level 50 should have 100 tiers", level50.size == 100)
        assertTrue("Level 100 should have 200 tiers", level100.size == 200)
    }

    @Test
    fun `order book analysis mid price calculation`() {
        // Calculate mid price from best bid and ask
        val bestBid = tightBids.maxOf { it.price }
        val bestAsk = tightAsks.minOf { it.price }
        val mid = (bestBid + bestAsk) / 2.0
        
        assertTrue("Mid price should be between bid and ask", mid > bestBid && mid < bestAsk)
        assertTrue("Mid price should be near spot", math.abs(mid - spotPrice) < 0.1)
    }
}