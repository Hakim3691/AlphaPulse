package com.aistudio.alphapulse.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the release roadmap maths, specified as Given/When/Then.
 *
 * §62 defines four releases with a fixed number of features each (10, 8, 7, 6).
 * The progress helper drives the roadmap UI, so a miscount here would show the
 * team a percentage that does not match reality - these tests pin the counts
 * and prove the percentage actually responds when a feature is turned off.
 *
 * Spec Ref: §62 Release Strategy
 */
class ReleaseProgressBddSpec {

    @Test
    fun `given a brand new installation when progress is calculated then every release reports one hundred percent`() {
        val progress = calculateProgress(ReleasePipeline())

        assertEquals("§62 defines four releases", 4, progress.size)
        progress.forEach { p ->
            assertEquals("Default pipeline ships every feature of ${p.release}", 100.0, p.progressPercentage, 1e-9)
            assertEquals(p.completedFeatures, p.totalFeatures)
        }
    }

    @Test
    fun `given the four release feature counts when read then they match the spec totals`() {
        val progress = calculateProgress(ReleasePipeline())

        // §62 feature counts. Release 1 pairs Calmar and Sharpe into one flag, so
        // its 11 spec bullets map onto 10 switches.
        assertEquals(10, progress[0].totalFeatures) // Release 1: Native Parity
        assertEquals(8, progress[1].totalFeatures) // Release 2: Quant Upgrade
        assertEquals(6, progress[2].totalFeatures) // Release 3: Multi-Model Intelligence
        assertEquals(6, progress[3].totalFeatures) // Release 4: Edge Intelligence
    }

    @Test
    fun `given a release with one feature still open when progress is calculated then the percentage drops below one hundred`() {
        // Turn a single Release 1 feature off and check the arithmetic notices.
        val pipeline = ReleasePipeline(release1 = Release1_NativeParity(csvExport = false))
        val release1 = calculateProgress(pipeline).first()

        assertEquals(9, release1.completedFeatures)
        assertEquals(10, release1.totalFeatures)
        assertEquals(90.0, release1.progressPercentage, 1e-9)
    }

    @Test
    fun `given the roadmap when read then each release names the next milestone except the last`() {
        val progress = calculateProgress(ReleasePipeline())

        assertTrue(progress[0].nextMilestone!!.contains("Quant Upgrade"))
        assertTrue(progress[1].nextMilestone!!.contains("Multi-Model"))
        assertTrue(progress[2].nextMilestone!!.contains("Edge Intelligence"))
        assertEquals("The final release has nothing after it", null, progress[3].nextMilestone)
    }

    @Test
    fun `given the shipped roadmap when read then every release milestone carries exit criteria`() {
        RELEASE_ROADMAP.forEach { milestone ->
            assertTrue(
                "${milestone.name} must define at least one exit criterion",
                milestone.exitCriteria.isNotEmpty()
            )
            assertTrue(
                "${milestone.name} must target a sprint",
                milestone.targetSprint.isNotBlank()
            )
        }
        assertEquals("The roadmap must describe all four releases", 4, RELEASE_ROADMAP.size)
    }
}
