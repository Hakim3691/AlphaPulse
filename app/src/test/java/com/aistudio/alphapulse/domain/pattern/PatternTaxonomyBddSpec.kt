package com.aistudio.alphapulse.domain.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2-§3 of the Multi-Horizon Pattern Prediction directive — the taxonomy library and
 * the horizon mapping.
 *
 * The classifier is only as trustworthy as the vocabulary it draws from: a pattern that
 * is not in this library cannot be predicted, and a pattern bound to the wrong horizon
 * is a structural mismatch (a 1-hour double bottom is not a 1-minute signal). These
 * scenarios pin the 26 verified definitions, their categories and the horizon binding,
 * so a silently dropped formation fails the build.
 *
 * Spec Ref: Directive §2 Multi-Horizon Mapping, §3 Reference Taxonomy Library
 */
class PatternTaxonomyBddSpec {

    @Test
    fun `given the taxonomy library when read then it holds every verified formation in its documented category`() {
        val expectedBullishReversal = listOf(
            "hammer", "bullish-engulfing", "three-outside-up", "morning-star", "piercing-line",
            "double-bottom", "reverse-head-and-shoulders", "triple-bottom", "falling-wedge"
        )
        val expectedBullishContinuation = listOf(
            "three-white-soldiers", "rising-flag", "rising-three-methods", "ascending-triangle"
        )
        val expectedBearishReversal = listOf(
            "hanging-man", "bearish-engulfing", "three-outside-down", "shooting-star",
            "evening-star", "dark-cloud-cover", "double-top", "head-and-shoulders-top",
            "triple-top", "rising-wedge"
        )
        val expectedBearishContinuation = listOf(
            "three-black-crows", "falling-flag", "falling-three-methods", "descending-triangle"
        )

        fun ids(category: PatternCategory) =
            PatternTaxonomy.definitions.filter { it.category == category }.map { it.id }

        assertEquals("bottom reversals", expectedBullishReversal, ids(PatternCategory.BULLISH_REVERSAL))
        assertEquals("bullish continuations", expectedBullishContinuation, ids(PatternCategory.CONTINUATION)
            .filter { it in expectedBullishContinuation })
        assertEquals("top reversals", expectedBearishReversal, ids(PatternCategory.BEARISH_REVERSAL))
        assertEquals("bearish continuations", expectedBearishContinuation, ids(PatternCategory.CONTINUATION)
            .filter { it in expectedBearishContinuation })

        assertEquals(
            "the library is the 26 formations of §3 plus the Evening Star that §2 binds to 15m",
            27,
            PatternTaxonomy.definitions.size
        )
        assertEquals(
            "a taxonomy key must be unique, or two formations collapse into one prediction",
            PatternTaxonomy.definitions.size,
            PatternTaxonomy.definitions.map { it.id }.toSet().size
        )
    }

    @Test
    fun `given the horizon map when read then every horizon binds patterns that exist and carries its validation criteria`() {
        assertEquals(
            "the directive fixes five structural horizons",
            listOf("1m", "5m", "15m", "30m", "1h"),
            PatternHorizon.entries.map { it.id }
        )
        assertEquals(
            "horizon durations are the natural bar lengths",
            listOf(1, 5, 15, 30, 60),
            PatternHorizon.entries.map { it.minutes }
        )

        for (horizon in PatternHorizon.entries) {
            assertTrue(
                "${horizon.id} must bind at least three formations to be a usable classifier",
                horizon.targetClasses.size >= 3
            )
            horizon.targetClasses.forEach { id ->
                assertTrue(
                    "${horizon.id} binds '$id', which the taxonomy library does not define",
                    PatternTaxonomy.byId.containsKey(id)
                )
                assertTrue(
                    "Pattern '$id' must declare ${horizon.id} among its structural horizons",
                    horizon.id in PatternTaxonomy.byId.getValue(id).horizons
                )
            }
            assertTrue("${horizon.id} needs a validation rule", horizon.validation.isNotBlank())
            assertTrue("${horizon.id} needs an invalidation rule", horizon.invalidation.isNotBlank())
            assertTrue("${horizon.id} needs at least one confluent trigger", horizon.confluentTriggers.isNotEmpty())
        }
    }

    @Test
    fun `given the horizon map when read then each structural family is bound to its natural timeframe`() {
        assertEquals(
            "1-minute bars carry the wick-reversal family",
            listOf("hammer", "hanging-man", "shooting-star"),
            PatternHorizon.M1.targetClasses
        )
        assertEquals(
            "5-minute bars carry the two-candle body-engulfing family",
            listOf("bullish-engulfing", "bearish-engulfing", "piercing-line", "dark-cloud-cover"),
            PatternHorizon.M5.targetClasses
        )
        assertEquals(
            "15-minute bars carry the three-candle confirmation family",
            listOf(
                "morning-star", "evening-star", "three-outside-up", "three-outside-down",
                "three-white-soldiers", "three-black-crows"
            ),
            PatternHorizon.M15.targetClasses
        )
        assertEquals(
            "30-minute bars carry the pole-and-consolidation family",
            listOf("rising-flag", "falling-flag", "rising-three-methods", "falling-three-methods"),
            PatternHorizon.M30.targetClasses
        )
        assertEquals(
            "1-hour bars carry the multi-day structure family",
            listOf(
                "double-bottom", "double-top", "falling-wedge", "rising-wedge",
                "ascending-triangle", "descending-triangle",
                "reverse-head-and-shoulders", "head-and-shoulders-top", "triple-bottom", "triple-top"
            ),
            PatternHorizon.H1.targetClasses
        )
    }

    @Test
    fun `given a horizon when its pattern ids are requested then only that horizon's formations are returned`() {
        val oneMinute = PatternTaxonomy.idsFor(PatternHorizon.M1)
        assertTrue("1m must offer its wick reversals", oneMinute.contains("hammer"))
        assertTrue(
            "a 1-hour structure must never be offered to a 1-minute classifier",
            "double-bottom" !in oneMinute && "ascending-triangle" !in oneMinute
        )

        val hourly = PatternTaxonomy.idsFor(PatternHorizon.H1)
        assertTrue(hourly.contains("double-bottom"))
        assertTrue("a 1-minute wick reversal is not an hourly formation", "hammer" !in hourly)
    }
}
