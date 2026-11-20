package com.aistudio.alphapulse.domain.catalogue

/**
 * Phase 1 §3.1: the catalogue's four-category tabs plus favourites.
 *
 * The taxonomy is only useful if a trader can inspect it, so the filter rule is a
 * pure function (and therefore testable) rather than a chain of composable
 * conditionals. Favourites are a set of stable setup ids, persisted by the caller.
 *
 * Spec Ref: Phase 1 §3.1 Comprehensive Trade Ideas & Pattern / Quant Catalog
 */
enum class CatalogueFilter(val label: String) {
    ALL("All"),
    PATTERN("Pattern"),
    INDICATOR("Indicator"),
    QUANT("Quant"),
    FAVORITES("Favorites");

    /** The category this tab selects, or null for the cross-category tabs. */
    val category: CatalogueCategory?
        get() = when (this) {
            PATTERN -> CatalogueCategory.PATTERN
            INDICATOR -> CatalogueCategory.INDICATOR
            QUANT -> CatalogueCategory.QUANT
            ALL, FAVORITES -> null
        }
}

object CatalogueBrowser {

    /** The setups a tab shows, preserving the catalogue's published order. */
    fun visible(
        ideas: List<TradeIdea>,
        filter: CatalogueFilter,
        favorites: Set<String>
    ): List<TradeIdea> = when (filter) {
        CatalogueFilter.ALL -> ideas
        CatalogueFilter.FAVORITES -> ideas.filter { it.id in favorites }
        else -> ideas.filter { it.category == filter.category }
    }

    /** Adds or removes a setup from the favourites set. */
    fun toggleFavorite(favorites: Set<String>, id: String): Set<String> =
        if (id in favorites) favorites - id else favorites + id
}
