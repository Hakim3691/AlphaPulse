package com.aistudio.alphapulse.domain.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 §3.1: the catalogue's existing tabs (All, Pattern, Indicator, Quant,
 * Favorites) must select exactly what they claim.
 *
 * Spec Ref: Phase 1 §3.1
 */
class CatalogueBrowserBddSpec {

    @Test
    fun `given the All tab when it is applied then every catalogue setup is visible`() {
        val visible = CatalogueBrowser.visible(
            TradeIdeasCatalog.all,
            CatalogueFilter.ALL,
            emptySet()
        )

        assertEquals(TradeIdeasCatalog.all.size, visible.size)
    }

    @Test
    fun `given a category tab when it is applied then only that category is visible`() {
        listOf(
            CatalogueFilter.PATTERN,
            CatalogueFilter.INDICATOR,
            CatalogueFilter.QUANT
        ).forEach { filter ->
            val visible = CatalogueBrowser.visible(TradeIdeasCatalog.all, filter, emptySet())

            assertTrue("$filter must show setups", visible.isNotEmpty())
            assertTrue(
                "$filter must show only its own category",
                visible.all { it.category == filter.category }
            )
        }
    }

    @Test
    fun `given no favourites when the Favorites tab is applied then it is empty rather than showing everything`() {
        val visible = CatalogueBrowser.visible(
            TradeIdeasCatalog.all,
            CatalogueFilter.FAVORITES,
            emptySet()
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `given a favourite when the Favorites tab is applied then only that setup is visible`() {
        val favorites = setOf("hammer", "turtle-breakout")

        val visible = CatalogueBrowser.visible(
            TradeIdeasCatalog.all,
            CatalogueFilter.FAVORITES,
            favorites
        )

        assertEquals(listOf("hammer", "turtle-breakout"), visible.map { it.id })
    }

    @Test
    fun `given a setup when it is starred twice then it returns to unfavourited`() {
        val once = CatalogueBrowser.toggleFavorite(emptySet(), "hammer")
        val twice = CatalogueBrowser.toggleFavorite(once, "hammer")

        assertEquals(setOf("hammer"), once)
        assertTrue(twice.isEmpty())
    }
}
