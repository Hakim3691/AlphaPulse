package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.TradeIdeaDao
import com.aistudio.alphapulse.data.db.toTradeIdea
import com.aistudio.alphapulse.domain.catalogue.CatalogueCategory
import com.aistudio.alphapulse.domain.catalogue.Regime
import com.aistudio.alphapulse.domain.catalogue.TradeIdea

/**
 * D7: the query surface over the persisted Trade Ideas Catalog.
 *
 * The engine must select candidates from the catalogue according to the regime the
 * asset is actually in, not from a static list of nine. This class is the one place
 * that translates a live regime label into a [Regime] and asks Room for the setups
 * that taxonomy permits there.
 *
 * Spec Ref: Phase 1 §3.1, D7
 */
class CatalogueRepository(private val dao: TradeIdeaDao) {

    /** Every setup, strongest empirical edge first. */
    suspend fun all(): List<TradeIdea> = dao.all().map { it.toTradeIdea() }

    /** The setups allowed in [regime], strongest empirical edge first. */
    suspend fun candidatesFor(regime: Regime): List<TradeIdea> =
        dao.forRegime(regime.name).map { it.toTradeIdea() }

    suspend fun forCategory(category: CatalogueCategory): List<TradeIdea> =
        dao.forCategory(category.name).map { it.toTradeIdea() }

    suspend fun size(): Int = dao.count()

    companion object {

        /**
         * The [Regime] a live Markov label belongs to.
         *
         * The regime classifier reports human labels ("State 0: Mean-Reverting",
         * "Trending Up", "High Volatility"). Matching is on meaning, not on an exact
         * string, and anything unrecognised maps to [Regime.UNKNOWN] so the catalogue
         * refuses to pretend it knows the regime.
         */
        fun regimeOf(raw: String): Regime {
            val label = raw.lowercase()
            return when {
                "mean" in label && "revert" in label -> Regime.MEAN_REVERTING
                "high" in label || "volatil" in label -> Regime.HIGH_VOLATILITY
                "trend" in label && ("down" in label || "bear" in label) -> Regime.TRENDING_DOWN
                "downtrend" in label -> Regime.TRENDING_DOWN
                "trend" in label || "bull" in label -> Regime.TRENDING_UP
                else -> Regime.UNKNOWN
            }
        }
    }
}
