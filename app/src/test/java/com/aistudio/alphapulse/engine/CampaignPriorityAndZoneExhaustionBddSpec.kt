package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.LevelZone
import com.aistudio.alphapulse.data.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11 / §8 / Contracts C-51 and C-52 — structural priority and spent levels.
 *
 * §11 defines a campaign's priority as the *product* of the path's probability, its
 * cleanliness and the level's confidence, but the engine was adding a fixed role base
 * to fifteen percent of the path probability. A perfect path could therefore never
 * outrank a primary board with no structure, and the header's "PRIORITY RANK" was
 * mostly printing the role it already knew.
 *
 * §8 treats the third touch as the end of a level's usefulness: a shelf price has
 * already tested cannot be a fresh invitation, so a fourth and fifth tap must not keep
 * raising its confidence. The ledger already prints "Exhausted (Touch 3/3)"; the
 * structure engine now agrees with it.
 */
class CampaignPriorityAndZoneExhaustionBddSpec {

    @Test
    fun `given path probability cleanliness and level confidence when prioritised then the score is their product`() {
        // 80% x 60% x 70% of 100 = 33.6
        assertEquals(33.6, CampaignPriority.of(80, 60, 70), 1e-9)
    }

    @Test
    fun `given a perfect path on a perfect level when prioritised then the score is the bounded maximum`() {
        assertEquals(100.0, CampaignPriority.of(100, 100, 100), 1e-9)
    }

    @Test
    fun `given a spent level when prioritised then no priority survives`() {
        assertEquals(0.0, CampaignPriority.of(90, 90, 0), 1e-9)
    }

    @Test
    fun `given an untested level when its confidence is scored then it is weaker than a twice-tested one`() {
        val once = StructureEngine.zoneConfidence(1, avgReactionAtr = 1.0, timeframe = "4h", freshness = 90)
        val twice = StructureEngine.zoneConfidence(2, avgReactionAtr = 1.0, timeframe = "4h", freshness = 90)

        assertTrue("second touch should strengthen: $once vs $twice", twice > once)
    }

    @Test
    fun `given a fourth or ninth touch when confidence is scored then it stops rewarding repeated taps`() {
        val third = StructureEngine.zoneConfidence(3, avgReactionAtr = 1.0, timeframe = "4h", freshness = 90)
        val fourth = StructureEngine.zoneConfidence(4, avgReactionAtr = 1.0, timeframe = "4h", freshness = 90)
        val ninth = StructureEngine.zoneConfidence(9, avgReactionAtr = 1.0, timeframe = "4h", freshness = 90)

        assertEquals(third, fourth)
        assertEquals(third, ninth)
    }

    @Test
    fun `given a level at the touch ceiling when inspected then it reports itself exhausted`() {
        val tested = zone(touches = 2)
        val exhausted = zone(touches = 3)
        val overTapped = zone(touches = 7)

        assertFalse(tested.isExhausted)
        assertTrue(exhausted.isExhausted)
        assertTrue(overTapped.isExhausted)
    }

    private fun zone(touches: Int) = LevelZone(
        id = "z$touches",
        zoneType = ZoneType.HTF_SUPPORT,
        priceLow = 99.0,
        priceHigh = 100.0,
        timeframe = "4h",
        confidence = 70,
        touches = touches,
        freshnessScore = 80
    )
}
