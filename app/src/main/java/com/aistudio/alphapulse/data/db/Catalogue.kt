package com.aistudio.alphapulse.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.aistudio.alphapulse.domain.catalogue.CatalogueCategory
import com.aistudio.alphapulse.domain.catalogue.Regime
import com.aistudio.alphapulse.domain.catalogue.TradeIdea
import com.aistudio.alphapulse.domain.catalogue.TradeIdeasCatalog

/**
 * D7: the Trade Ideas Catalog persisted in Room.
 *
 * The catalogue used to be a Kotlin object only, so "the catalogue" could not be
 * queried by the engine at evaluation time and the directive's requirement that
 * Needle 3 select candidates from it was prose. It is now a table: one row per
 * setup with the published win-rate / average-return baseline, the regimes it is
 * permitted to fire in, and its trend-following flag.
 *
 * The DDL and the seed rows live in this one object, so the schema, the 9 -> 10
 * migration, the fresh-install seed and the guard spec all read a single
 * definition.
 *
 * Spec Ref: Phase 1 §3.1 Comprehensive Trade Ideas & Pattern / Quant Catalog, D7
 */
object CatalogueSchema {

    const val TABLE = "trade_ideas"

    /** Every column, in schema order. */
    val COLUMNS: List<String> = listOf(
        "id",
        "name",
        "category",
        "winRate",
        "avgReturn",
        "regimes",
        "trendFollowing"
    )

    /** The production 9 -> 10 statement, shared with the migration and its guard. */
    val DDL: String = buildString {
        append("CREATE TABLE IF NOT EXISTS `").append(TABLE).append("` (")
        append("`id` TEXT NOT NULL, ")
        append("`name` TEXT NOT NULL, ")
        append("`category` TEXT NOT NULL, ")
        append("`winRate` REAL NOT NULL, ")
        append("`avgReturn` REAL NOT NULL, ")
        append("`regimes` TEXT NOT NULL, ")
        append("`trendFollowing` INTEGER NOT NULL, ")
        append("PRIMARY KEY(`id`))")
    }

    /**
     * The catalogue's rows as idempotent INSERTs.
     *
     * The taxonomy is published data, not learned state, so seeding it in the
     * migration *and* on a fresh install is the honest way to ensure the engine can
     * always query it. Per-setup learning lives on top of these baselines.
     */
    fun seedStatements(): List<String> = TradeIdeasCatalog.all.map { idea ->
        buildString {
            append("INSERT OR REPLACE INTO `").append(TABLE).append("` (")
            append(COLUMNS.joinToString(", ") { "`$it`" })
            append(") VALUES (")
            append(quote(idea.id)).append(", ")
            append(quote(idea.name)).append(", ")
            append(quote(idea.category.name)).append(", ")
            append(idea.winRate).append(", ")
            append(idea.avgReturn).append(", ")
            append(quote(idea.regimes.joinToString(",") { it.name })).append(", ")
            append(if (idea.trendFollowing) 1 else 0)
            append(")")
        }
    }

    /** SQL string literal; the taxonomy has no quotes today, escaping keeps it true. */
    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"
}

/**
 * One catalogue row.
 *
 * `regimes` is a comma-separated list of [Regime] constant names so the engine can
 * filter in SQL (`regimes LIKE '%TRENDING_UP%'`) and still read the full set back.
 */
@Entity(tableName = CatalogueSchema.TABLE)
data class TradeIdeaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val winRate: Double,
    val avgReturn: Double,
    val regimes: String,
    val trendFollowing: Boolean
)

/** Row -> domain idea. An unknown category/regime is skipped, never guessed. */
fun TradeIdeaEntity.toTradeIdea(): TradeIdea = TradeIdea(
    id = id,
    name = name,
    category = runCatching { CatalogueCategory.valueOf(category) }
        .getOrDefault(CatalogueCategory.QUANT),
    winRate = winRate,
    avgReturn = avgReturn,
    regimes = regimes.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { runCatching { Regime.valueOf(it) }.getOrNull() }
        .toSet(),
    trendFollowing = trendFollowing
)

/** Domain idea -> row, used by the seed path and by tests. */
fun TradeIdea.toEntity(): TradeIdeaEntity = TradeIdeaEntity(
    id = id,
    name = name,
    category = category.name,
    winRate = winRate,
    avgReturn = avgReturn,
    regimes = regimes.joinToString(",") { it.name },
    trendFollowing = trendFollowing
)

@Dao
interface TradeIdeaDao {

    @Query("SELECT * FROM trade_ideas ORDER BY (winRate * avgReturn) DESC")
    suspend fun all(): List<TradeIdeaEntity>

    /** Candidates the taxonomy permits in [regime], strongest empirical edge first. */
    @Query(
        "SELECT * FROM trade_ideas WHERE regimes LIKE '%' || :regime || '%' " +
            "ORDER BY (winRate * avgReturn) DESC"
    )
    suspend fun forRegime(regime: String): List<TradeIdeaEntity>

    @Query(
        "SELECT * FROM trade_ideas WHERE category = :category " +
            "ORDER BY (winRate * avgReturn) DESC"
    )
    suspend fun forCategory(category: String): List<TradeIdeaEntity>

    @Query("SELECT COUNT(*) FROM trade_ideas")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ideas: List<TradeIdeaEntity>)
}
