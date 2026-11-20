package com.aistudio.alphapulse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * C-37 — when the screened universe was fetched, and how much of the venue it covered.
 *
 * Why this exists
 * ---------------
 * The cached universe had no age, so the app could not tell a two-minute-old screen from
 * one taken three days ago, and it could not tell the two ways a screen can come back
 * nearly empty: *the venue answered and nothing qualified* ([screenedPairs] > 0) versus
 * *the venue never answered* ([screenedPairs] == 0). The second one used to look exactly
 * like a valid, tiny universe.
 *
 * One row, ever: the cache describes itself, not each coin.
 *
 * Spec Ref: §65, §37 Room Database, C-37
 */
@Entity(tableName = "screened_universe_state")
data class ScreenedUniverseStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Wall-clock time the screen that filled [screened_coins] finished. */
    val fetchedAtMillis: Long,
    /** How many USDT perpetuals the venue's ticker returned for that run. */
    val screenedPairs: Int,
    /** How many of them cleared the §65 gate. */
    val admittedPairs: Int
) {
    companion object {
        /** The row's key; there is exactly one cache state. */
        const val SINGLETON_ID = 1
    }
}

/**
 * The state table's schema, in one place.
 *
 * The migration, the entity and the guard spec read this definition instead of each
 * carrying its own copy, so a column cannot exist in the migration and be missing from
 * the entity (which Room would reject only on the device).
 */
object UniverseCacheSchema {

    const val TABLE = "screened_universe_state"

    val COLUMNS = listOf("id", "fetchedAtMillis", "screenedPairs", "admittedPairs")

    const val DDL = "CREATE TABLE IF NOT EXISTS `$TABLE` (" +
        "`id` INTEGER NOT NULL, `fetchedAtMillis` INTEGER NOT NULL, " +
        "`screenedPairs` INTEGER NOT NULL, `admittedPairs` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`))"
}
