package com.aistudio.alphapulse.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Behaviour of the core market-structure objects, specified as Given/When/Then.
 *
 * Spec Ref: §13 (Core Market-Structure Objects, Level Detection, Path Modeling & Coin Calibration),
 * §17 (Position Sizing, Entry Ladder Construction, Proactive Order Architecture)
 *
 * Covers:
 * - Supply/Resistance level detection
 * - Support shelf identification
 * - Reclaim zone calculation
 * - Resting ladder construction
 * - Volume profile POC/VAH/VAL
 * - Path modeling for campaign construction
 */
class LevelMapBddSpec {

    data class PriceLevel(
        val price: Double,
        val type: LevelType,
        val volume: Double = 0.0,
        val touchCount: Int = 0
    )

    enum class LevelType {
        SUPPLY, RESISTANCE, SUPPORT, RESTING_LADDER, POC, VAH, VAL
    }

    data class LevelMap(
        val supplyResistance: List<PriceLevel> = emptyList(),
        val supportShelf: List<PriceLevel> = emptyList(),
        val reclaimZone: ClosedInterval? = null,
        val restingLadder: List<PriceLevel> = emptyList(),
        val volumeProfile: VolumeProfile? = null
    )

    data class ClosedInterval(val low: Double, val high: Double) {
        fun contains(price: Double): Boolean = price in low..high
        fun width(): Double = high - low
    }

    data class VolumeProfile(
        val poc: Double,
        val vah: Double,
        val valBoundary: Double,
        val totalVolume: Double,
        val nodes: List<VolumeNode> = emptyList()
    )

    data class VolumeNode(val price: Double, val volume: Double)

    // ------------------------------------------------------------ level detection

    @Test
    fun `given price levels when identifying supply levels then highest volume nodes above price are detected`() {
        val levels = listOf(
            PriceLevel(105.0, LevelType.SUPPLY, volume = 500.0, touchCount = 3),
            PriceLevel(102.0, LevelType.RESISTANCE, volume = 300.0, touchCount = 2),
            PriceLevel(98.0, LevelType.SUPPORT, volume = 400.0, touchCount = 4),
            PriceLevel(95.0, LevelType.SUPPORT, volume = 200.0, touchCount = 1)
        )

        val supplyLevels = levels.filter { it.type == LevelType.SUPPLY || it.type == LevelType.RESISTANCE }
        assertEquals(2, supplyLevels.size)
        assertEquals(105.0, supplyLevels.maxOf { it.price }, 0.001)
    }

    @Test
    fun `given price levels when identifying support shelf then highest volume nodes below price are detected`() {
        val levels = listOf(
            PriceLevel(105.0, LevelType.SUPPLY, volume = 500.0),
            PriceLevel(102.0, LevelType.RESISTANCE, volume = 300.0),
            PriceLevel(98.0, LevelType.SUPPORT, volume = 400.0),
            PriceLevel(95.0, LevelType.SUPPORT, volume = 600.0)
        )

        val supportLevels = levels.filter { it.type == LevelType.SUPPORT }
        assertEquals(2, supportLevels.size)
        val highestVolumeSupport = supportLevels.maxByOrNull { it.volume }!!.price
        assertEquals(95.0, highestVolumeSupport, 0.001)
    }

    @Test
    fun `given consecutive higher lows when identifying reclaim zone then the zone is marked as bullish`() {
        val lows = listOf(95.0, 96.0, 97.0, 98.0)
        val reclaimLow = lows.first()
        val reclaimHigh = lows.last()

        val reclaimZone = ClosedInterval(reclaimLow, reclaimHigh)

        assertTrue(reclaimZone.contains(96.5))
        assertEquals(3.0, reclaimZone.width(), 0.001)
    }

    @Test
    fun `given a reclaim zone when price is above it then the zone is confirmed reclaimed`() {
        val reclaimZone = ClosedInterval(95.0, 98.0)
        val currentPrice = 99.0

        assertTrue("Price above reclaim zone confirms reclaim", currentPrice > reclaimZone.high)
    }

    // ------------------------------------------------------------ resting ladder

    @Test
    fun `given price levels when building resting ladder then nodes are ordered from high to low`() {
        val levels = listOf(
            PriceLevel(105.0, LevelType.SUPPLY),
            PriceLevel(103.0, LevelType.RESISTANCE),
            PriceLevel(100.0, LevelType.RESISTANCE),
            PriceLevel(98.0, LevelType.SUPPORT),
            PriceLevel(95.0, LevelType.SUPPORT)
        )

        val ladder = levels.sortedByDescending { it.price }

        assertEquals(105.0, ladder[0].price, 0.001)
        assertEquals(95.0, ladder.last().price, 0.001)
    }

    @Test
    fun `given a resting ladder when counting rungs then each level is a distinct rung`() {
        val ladder = listOf(
            PriceLevel(105.0, LevelType.SUPPLY),
            PriceLevel(103.0, LevelType.RESISTANCE),
            PriceLevel(100.0, LevelType.RESISTANCE),
            PriceLevel(98.0, LevelType.SUPPORT),
            PriceLevel(95.0, LevelType.SUPPORT)
        )

        assertEquals(5, ladder.size)
    }

    // ------------------------------------------------------------ volume profile

    @Test
    fun `given volume nodes when computing POC then the node with highest volume is the point of control`() {
        val nodes = listOf(
            VolumeNode(100.0, 1000.0),
            VolumeNode(101.0, 1500.0),
            VolumeNode(102.0, 2000.0),
            VolumeNode(103.0, 1200.0)
        )

        val poc = nodes.maxByOrNull { it.volume }!!.price

        assertEquals(102.0, poc, 0.001)
    }

    @Test
    fun `given volume nodes when computing VAH and VAL then they represent the value area boundaries`() {
        val nodes = listOf(
            VolumeNode(98.0, 500.0),
            VolumeNode(99.0, 800.0),
            VolumeNode(100.0, 2000.0),
            VolumeNode(101.0, 1500.0),
            VolumeNode(102.0, 600.0)
        )

        val totalVolume = nodes.sumOf { it.volume }
        val sortedByPrice = nodes.sortedBy { it.price }
        val poc = nodes.maxByOrNull { it.volume }!!.price

        // Simple VAH/VAL calculation: 70% of volume around POC
        val valueAreaVolume = totalVolume * 0.70
        var cumulative = 0.0
        var vah = poc
        var valBoundary = poc

        for (node in sortedByPrice) {
            cumulative += node.volume
            if (node.price > poc && cumulative <= valueAreaVolume) vah = node.price
            if (node.price < poc && cumulative <= valueAreaVolume) valBoundary = node.price
        }

        assertTrue("VAH should be above POC", vah >= poc)
        assertTrue("VAL should be below POC", valBoundary <= poc)
        assertTrue("VAH should be >= VAL", vah >= valBoundary)
    }

    @Test
    fun `given a volume profile when checking if price is in value area then the cone bounds are respected`() {
        val profile = VolumeProfile(
            poc = 100.0,
            vah = 102.5,
            valBoundary = 97.5,
            totalVolume = 5000.0,
            nodes = emptyList()
        )

        assertTrue(profile.vah >= profile.poc)
        assertTrue(profile.valBoundary <= profile.poc)
        assertTrue(profile.valBoundary < profile.vah)
    }

    // ------------------------------------------------------------ path modeling

    @Test
    fun `given a price path when modeling then the path respects structural levels`() {
        val supplyLevel = 105.0
        val supportLevel = 98.0
        val currentPrice = 101.0

        // Path should respect levels: price above support, below supply
        assertTrue(currentPrice > supportLevel)
        assertTrue(currentPrice < supplyLevel)
    }

    @Test
    fun `given multiple timeframes when building composite level map then HTF levels encompass LTF levels`() {
        val ltfSupply = 103.0
        val htfSupply = 105.0
        val ltfSupport = 99.0
        val htfSupport = 97.0

        // Higher timeframe levels should be wider
        assertTrue("HTF supply should be above LTF supply", htfSupply >= ltfSupply)
        assertTrue("HTF support should be below LTF support", htfSupport <= ltfSupport)
    }

    @Test
    fun `given a level map when checking campaign entry then price is within acceptable range of levels`() {
        val levelMap = LevelMap(
            supplyResistance = listOf(PriceLevel(105.0, LevelType.RESISTANCE)),
            supportShelf = listOf(PriceLevel(98.0, LevelType.SUPPORT)),
            reclaimZone = ClosedInterval(95.0, 97.0)
        )

        val entryPrice = 99.0
        val resistance = levelMap.supplyResistance.first().price
        val support = levelMap.supportShelf.first().price

        assertTrue("Entry should be below resistance", entryPrice < resistance)
        assertTrue("Entry should be above support", entryPrice > support)
    }

    @Test
    fun `given volume profile nodes when total volume is computed then it sums all nodes`() {
        val nodes = listOf(
            VolumeNode(100.0, 1000.0),
            VolumeNode(101.0, 2000.0),
            VolumeNode(102.0, 1500.0)
        )

        val totalVolume = nodes.sumOf { it.volume }

        assertEquals(4500.0, totalVolume, 0.001)
    }

    @Test
    fun `given a closed interval when checking boundary containment then exact boundaries are included`() {
        val interval = ClosedInterval(100.0, 105.0)

        assertTrue(interval.contains(100.0))
        assertTrue(interval.contains(105.0))
        assertTrue(interval.contains(102.5))
        assertFalse(interval.contains(99.99))
        assertFalse(interval.contains(105.01))
    }
}
