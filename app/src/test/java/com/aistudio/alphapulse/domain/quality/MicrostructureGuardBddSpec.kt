package com.aistudio.alphapulse.domain.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §35 / C-39 — the microstructure reading is refused, not invented.
 *
 * Why this spec exists
 * --------------------
 * The evaluation cycle initialised the bid share to `50.0` and left it there whenever no
 * depth arrived, and the ingestion agent defaulted the same value again. The consequence
 * was that a coin with no order book at all was evaluated as a perfectly balanced book:
 * the imbalance leg contributed silence that the router read as neutrality, the narrative
 * told the user "50.0% Bid vs 50.0% Ask", and the dashboard printed a book split that was
 * never observed. §35 requires the opposite — stale-data age, order-book sequence
 * integrity and a shallow-book check, and an explicit statement instead of a forecast
 * presented as equally reliable under degraded conditions.
 *
 * Spec Ref: §35 Data Quality Engine, §7 FR-006 full order book, C-39
 */
class MicrostructureGuardBddSpec {

    private val now = 1_700_000_000_000L

    /** A symmetric, deep, healthy book: 20 levels a side, best bid 100 / best ask 100.1. */
    private fun deepBook(
        bidNotionalPerLevel: Double = 100.0,
        askNotionalPerLevel: Double = 60.0,
        levels: Int = 20,
        bestBid: Double = 100.0,
        bestAsk: Double = 100.1
    ): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val bids = (0 until levels).map { bestBid - it * 0.1 to bidNotionalPerLevel / (bestBid - it * 0.1) }
        val asks = (0 until levels).map { bestAsk + it * 0.1 to askNotionalPerLevel / (bestAsk + it * 0.1) }
        return bids to asks
    }

    @Test
    fun `given no depth arrived when the book is assessed then the bid share is unknown rather than one half`() {
        val reading = MicrostructureGuard.assess(emptyList(), emptyList(), now, now)

        assertEquals(BookHealth.ABSENT, reading.health)
        assertNull(
            "A missing book has no bid share; one half is a number the app never observed",
            reading.bidRatioPct
        )
        assertEquals(0.0, reading.netImbalanceUsdt, 0.0)
        assertFalse(reading.isUsable)
    }

    @Test
    fun `given a book with no timestamp when assessed then it cannot be dated and is refused`() {
        val (bids, asks) = deepBook()

        val reading = MicrostructureGuard.assess(bids, asks, bookTimestampMs = 0L, nowMs = now)

        assertEquals(BookHealth.ABSENT, reading.health)
        assertNull(reading.bidRatioPct)
    }

    @Test
    fun `given a book at the freshness limit when assessed then it is live, and one millisecond later it is stale`() {
        val (bids, asks) = deepBook()

        assertEquals(
            BookHealth.LIVE,
            MicrostructureGuard.assess(
                bids, asks,
                bookTimestampMs = now - MicrostructureGuard.MAX_BOOK_AGE_MS,
                nowMs = now
            ).health
        )

        val stale = MicrostructureGuard.assess(
            bids, asks,
            bookTimestampMs = now - MicrostructureGuard.MAX_BOOK_AGE_MS - 1,
            nowMs = now
        )
        assertEquals(BookHealth.STALE, stale.health)
        assertNull("A stale book's share is not a current reading", stale.bidRatioPct)
    }

    @Test
    fun `given a crossed book when assessed then the reconstruction is rejected instead of priced`() {
        val (bids, asks) = deepBook()
        val crossed = bids.map { (price, qty) -> (price + 1.0) to qty }

        val reading = MicrostructureGuard.assess(crossed, asks, now, now)

        assertEquals(
            "A best bid above the best ask means the book was rebuilt wrongly",
            BookHealth.CROSSED,
            reading.health
        )
        assertNull(reading.bidRatioPct)
    }

    @Test
    fun `given fewer than ten levels a side when assessed then the book is too shallow to price`() {
        val (bids, asks) = deepBook(levels = 9)

        val reading = MicrostructureGuard.assess(bids, asks, now, now)

        assertEquals(BookHealth.SHALLOW, reading.health)
        assertNull(reading.bidRatioPct)
        assertEquals(9, reading.levels)

        val justEnough = deepBook(levels = MicrostructureGuard.MIN_BOOK_LEVELS)
        assertEquals(
            BookHealth.LIVE,
            MicrostructureGuard.assess(justEnough.first, justEnough.second, now, now).health
        )
    }

    @Test
    fun `given a healthy book when assessed then the bid share, imbalance and spread are the level-20 truth`() {
        // 20 levels of 100 USDT-equivalent on the bid and 60 on the ask: 2000 vs 1200.
        val (bids, asks) = deepBook()

        val reading = MicrostructureGuard.assess(bids, asks, now, now)

        assertEquals(BookHealth.LIVE, reading.health)
        assertEquals(2_000.0, reading.bidNotionalUsdt, 1.0)
        assertEquals(1_200.0, reading.askNotionalUsdt, 1.0)
        assertEquals(62.5, reading.bidRatioPct!!, 0.1)
        assertEquals(800.0, reading.netImbalanceUsdt, 1.0)
        assertEquals(20, reading.levels)
        // (100.1 - 100.0) / 100.05 * 10_000 bps ~= 10.0 bps.
        assertEquals(10.0, reading.spreadBps, 0.2)
        assertTrue(reading.isUsable)
    }

    @Test
    fun `given the production evaluation and stream paths when read then neither defaults the bid share to one half`() {
        val repository = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/data/repository/AlphaPulseRepository.kt"
        ).readText()
        val stream = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/data/network/BinanceWebSocketManager.kt"
        ).readText()

        assertFalse(
            "The evaluation must not invent a balanced book when the depth call failed",
            repository.contains("var bidRatioPct = 50.0")
        )
        assertTrue(
            "The evaluation must ask the guard before it reports a share",
            repository.contains("MicrostructureGuard.assess(")
        )
        assertFalse(
            "The depth stream must not publish a one-half share for an empty book",
            stream.contains("else 50.0")
        )
    }

    @Test
    fun `given the live stream when its contract is read then it publishes the book and not a second verdict about it`() {
        // The socket used to compute a bid share and an imbalance of its own on every depth
        // frame. Both were unread once the guard judged the book, so both were a second
        // opinion with no reader — and a second place for the two to disagree.
        val stream = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/data/network/BinanceWebSocketManager.kt"
        ).readText()

        assertFalse(
            "The stream must not carry a bid share; the guard is the only reader of the book",
            stream.contains("bidRatioPct")
        )
        assertFalse(
            "And no imbalance computed from the same levels for nobody to read",
            stream.contains("netImbalanceUsdt")
        )
    }

    /** Locates a repository file from whichever directory tests run in. */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
