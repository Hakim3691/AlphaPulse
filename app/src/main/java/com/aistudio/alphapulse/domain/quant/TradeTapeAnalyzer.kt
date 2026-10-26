package com.aistudio.alphapulse.domain.quant

/**
 * TradeTapeAnalyzer - reads the live trade stream the way a desk reads a tape.
 *
 * The app already stores every trade it receives (`TradeBufferEntity`), but until
 * now nothing turned that stream into a judgement. Raw prints are noise; what
 * matters is *who is hitting the market*: are buyers lifting offers, is one
 * participant sweeping several levels at once, and how large are the prints
 * compared with the usual size for this symbol?
 *
 * This analyzer answers exactly those questions over a rolling time window. It is
 * pure Kotlin - it receives prints and a clock, and returns numbers - so it can be
 * tested without a network or a database.
 *
 * Spec Ref: §10 Institutional Trade-Tape Analysis
 */

/**
 * One trade as it appears on the tape.
 *
 * @property timestampMs exchange trade time in epoch milliseconds
 * @property price execution price
 * @property quantity base-asset quantity traded
 * @property buyerMaker true when the buyer was the maker, which means the *seller*
 *   crossed the spread and the print is aggressive selling
 */
data class TapePrint(
    val timestampMs: Long,
    val price: Double,
    val quantity: Double,
    val buyerMaker: Boolean
) {
    /** Dollar value of the print. */
    val notional: Double
        get() = price * quantity

    /**
     * True when the taker was a buyer (they lifted the offer).
     *
     * Binance reports `buyerMaker = true` when the resting order was a buy, so the
     * aggressor was the seller - hence the negation here.
     */
    val isAggressiveBuy: Boolean
        get() = !buyerMaker
}

/**
 * What the tape looked like over the window.
 *
 * @property windowMs length of the window analysed
 * @property printCount how many prints fell inside the window
 * @property buyNotional dollars traded by aggressive buyers
 * @property sellNotional dollars traded by aggressive sellers
 * @property aggressorImbalance (buy - sell) / (buy + sell), in -1.0..1.0
 * @property largePrintCount prints at or above the large-print threshold
 * @property largestPrintNotional the single biggest print
 * @property sweepCount groups of rapid same-side prints (aggressive sweeps)
 * @property averagePrintNotional average print size, used as the "normal" size
 * @property deltaNotional buy minus sell, in dollars
 */
data class TapeMetrics(
    val windowMs: Long,
    val printCount: Int,
    val buyNotional: Double,
    val sellNotional: Double,
    val aggressorImbalance: Double,
    val largePrintCount: Int,
    val largestPrintNotional: Double,
    val sweepCount: Int,
    val averagePrintNotional: Double
) {
    /** Total dollars traded in the window. */
    val totalNotional: Double
        get() = buyNotional + sellNotional

    /** Buy dollars minus sell dollars: the tape's net pressure in dollars. */
    val deltaNotional: Double
        get() = buyNotional - sellNotional

    /** True when aggressive buying clearly dominates (more than 20% imbalance). */
    val isBuyDominant: Boolean
        get() = aggressorImbalance > 0.20

    /** True when aggressive selling clearly dominates (more than 20% imbalance). */
    val isSellDominant: Boolean
        get() = aggressorImbalance < -0.20
}

object TradeTapeAnalyzer {

    /** Prints at or above this notional count as "large" unless the caller overrides it. */
    const val DEFAULT_LARGE_PRINT_NOTIONAL: Double = 100_000.0

    /** Prints of the same side closer together than this are one sweep. */
    const val SWEEP_GAP_MS: Long = 250L

    /** How many same-side prints in a row make a sweep. */
    const val SWEEP_MIN_PRINTS: Int = 3

    /** Default analysis window: one minute, matching the shortest forecast horizon. */
    const val DEFAULT_WINDOW_MS: Long = 60_000L

    /**
     * Analyse the prints that fall inside the window ending at [nowMs].
     *
     * Prints older than the window are ignored, and so are prints timestamped in
     * the future (a clock skew must not invent volume that has not happened).
     *
     * @param prints the tape, in any order
     * @param nowMs the right edge of the window
     * @param windowMs how far back to look
     * @param largePrintNotional notional at which a print counts as large
     */
    fun analyze(
        prints: List<TapePrint>,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        largePrintNotional: Double = DEFAULT_LARGE_PRINT_NOTIONAL
    ): TapeMetrics {
        val windowStart = nowMs - windowMs
        val inWindow = prints
            .filter { it.timestampMs in windowStart..nowMs }
            .sortedBy { it.timestampMs }

        if (inWindow.isEmpty()) {
            // An empty tape is reported as zero volume, never as a divide-by-zero.
            return TapeMetrics(
                windowMs = windowMs,
                printCount = 0,
                buyNotional = 0.0,
                sellNotional = 0.0,
                aggressorImbalance = 0.0,
                largePrintCount = 0,
                largestPrintNotional = 0.0,
                sweepCount = 0,
                averagePrintNotional = 0.0
            )
        }

        val buyNotional = inWindow.filter { it.isAggressiveBuy }.sumOf { it.notional }
        val sellNotional = inWindow.filterNot { it.isAggressiveBuy }.sumOf { it.notional }
        val total = buyNotional + sellNotional

        return TapeMetrics(
            windowMs = windowMs,
            printCount = inWindow.size,
            buyNotional = buyNotional,
            sellNotional = sellNotional,
            // Guard the division: a zero-notional tape has no imbalance.
            aggressorImbalance = if (total <= 0.0) 0.0 else (buyNotional - sellNotional) / total,
            largePrintCount = inWindow.count { it.notional >= largePrintNotional },
            largestPrintNotional = inWindow.maxOf { it.notional },
            sweepCount = countSweeps(inWindow),
            averagePrintNotional = total / inWindow.size
        )
    }

    /**
     * Count aggressive sweeps.
     *
     * A sweep is a run of at least [SWEEP_MIN_PRINTS] prints on the same side where
     * each print follows the previous one within [SWEEP_GAP_MS]. A run of five
     * prints is one sweep, not three overlapping ones, because the desk sees it as
     * a single order that walked the book.
     */
    private fun countSweeps(prints: List<TapePrint>): Int {
        var sweeps = 0
        var runLength = 1
        var runSide = prints.first().isAggressiveBuy

        for (index in 1 until prints.size) {
            val current = prints[index]
            val previous = prints[index - 1]
            val sameSide = current.isAggressiveBuy == runSide
            val quick = current.timestampMs - previous.timestampMs <= SWEEP_GAP_MS

            if (sameSide && quick) {
                runLength += 1
            } else {
                if (runLength >= SWEEP_MIN_PRINTS) sweeps += 1
                runLength = 1
                runSide = current.isAggressiveBuy
            }
        }
        // Close the run that was still open when the data ended.
        if (runLength >= SWEEP_MIN_PRINTS) sweeps += 1
        return sweeps
    }

    /**
     * Plain-English line for the explanation panel.
     *
     * @return e.g. "Tape 1m: buyers aggressive ($1.2M bought vs $0.4M sold), 2 large prints, 1 sweep."
     */
    fun describe(metrics: TapeMetrics): String {
        if (metrics.printCount == 0) {
            return "Tape ${metrics.windowMs / 1000}s: no prints in the window."
        }

        val side = when {
            metrics.isBuyDominant -> "buyers aggressive"
            metrics.isSellDominant -> "sellers aggressive"
            else -> "balanced two-way flow"
        }
        val bought = formatUsd(metrics.buyNotional)
        val sold = formatUsd(metrics.sellNotional)
        return "Tape ${metrics.windowMs / 1000}s: $side " +
            "($bought bought vs $sold sold), " +
            "${metrics.largePrintCount} large print(s), ${metrics.sweepCount} sweep(s)."
    }

    /** Compact dollar label: 1_234_567 -> "$1.23M". */
    private fun formatUsd(value: Double): String = when {
        value >= 1_000_000.0 -> "$${"%.2f".format(value / 1_000_000.0)}M"
        value >= 1_000.0 -> "$${"%.1f".format(value / 1_000.0)}K"
        else -> "$${"%.0f".format(value)}"
    }
}
