package com.aistudio.alphapulse.domain.catalogue

/**
 * C-66 — the 44 catalogue setups are reachable from the ledgers they inform.
 *
 * The catalogue used to be a standalone 44-row scroller at the bottom of the AlphaPulse
 * screen, disconnected from the price, pattern and strategy ledgers that consume it. Each
 * ledger now carries one info action whose popup lists its slice of the taxonomy, and the
 * three slices partition the catalogue exactly: 8 indicator setups + 27 pattern setups +
 * 9 quant setups = 44, with no setup shown under two ledgers.
 *
 * Spec Ref: Phase 1 §3.1, C-66
 */
enum class CatalogueLedger { PRICE, PATTERN, STRATEGY }

object CatalogueLedgerInfo {

    /** The catalogue categories each ledger reads. */
    fun categoriesFor(ledger: CatalogueLedger): Set<CatalogueCategory> = when (ledger) {
        CatalogueLedger.PRICE -> setOf(CatalogueCategory.INDICATOR)
        CatalogueLedger.PATTERN -> setOf(CatalogueCategory.PATTERN)
        CatalogueLedger.STRATEGY -> setOf(CatalogueCategory.QUANT)
    }

    fun ideasFor(
        ledger: CatalogueLedger,
        catalogue: List<TradeIdea> = TradeIdeasCatalog.all
    ): List<TradeIdea> {
        val categories = categoriesFor(ledger)
        return catalogue.filter { it.category in categories }
    }

    fun titleFor(ledger: CatalogueLedger, catalogue: List<TradeIdea> = TradeIdeasCatalog.all): String =
        "${ledger.name} TRADE IDEAS · ${ideasFor(ledger, catalogue).size}"

    /** What this ledger's slice measures, printed once above its rows. */
    fun descriptionFor(ledger: CatalogueLedger): String = when (ledger) {
        CatalogueLedger.PRICE ->
            "Price-derived indicator setups the engine can cite for this ledger, with their published win-rate and average-return baselines."
        CatalogueLedger.PATTERN ->
            "Candlestick and macro formations the pattern ledger may grade, with their published win-rate and average-return baselines."
        CatalogueLedger.STRATEGY ->
            "Quantitative rules the strategy board evaluates, with their published win-rate and average-return baselines."
    }
}
