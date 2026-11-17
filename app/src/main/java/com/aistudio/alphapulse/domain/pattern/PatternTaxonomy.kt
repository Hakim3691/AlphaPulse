package com.aistudio.alphapulse.domain.pattern

/**
 * The verified formation vocabulary and the horizons each formation belongs to.
 *
 * Why this exists
 * ---------------
 * A pattern ledger is only auditable if "Bullish Engulfing" means the same thing at
 * prediction time and at settlement time, and if a 1-hour structure can never be
 * asserted on a 1-minute bet. Before this library the app named candlestick shapes in
 * prose; nothing tied a name to a definition, a category or a timeframe, so a
 * "confirmed pattern" could not be falsified.
 *
 * The taxonomy is the reference data for the inference classifier and the ground-truth
 * auditor: [PatternClassifier] may only emit an id declared here, and only for a horizon
 * that lists it in [PatternHorizon.targetClasses].
 *
 * Spec Ref: Directive §2 Multi-Horizon Mapping, §3 Reference Taxonomy Library
 */

/** How a formation is expected to resolve. */
enum class PatternCategory(val label: String) {
    /** A bottom that reverses a decline. */
    BULLISH_REVERSAL("Bullish Reversal"),
    /** A top that reverses an advance. */
    BEARISH_REVERSAL("Bearish Reversal"),
    /** A pause inside an existing trend. */
    CONTINUATION("Continuation")
}

/** One verified formation. */
data class PatternDefinition(
    /** Stable taxonomy key, e.g. `bullish-engulfing`. Persisted in the ledger. */
    val id: String,
    /** Display name, e.g. "Bullish Engulfing". */
    val name: String,
    val category: PatternCategory,
    /** Structural horizons this formation is valid on. */
    val horizons: Set<String>
)

/**
 * The five structural horizons of the directive.
 *
 * Each horizon carries the classes it may predict, the geometry that validates it, what
 * voids it, and the confluence signals a screener may use to corroborate it. Those four
 * columns are the directive's validity framework; keeping them next to the enum means a
 * horizon cannot be added without stating how a prediction on it is judged.
 */
enum class PatternHorizon(
    val id: String,
    val label: String,
    val minutes: Int,
    val targetClasses: List<String>,
    val validation: String,
    val invalidation: String,
    val confluentTriggers: List<String>
) {
    M1(
        id = "1m",
        label = "1 Minute",
        minutes = 1,
        targetClasses = listOf("hammer", "hanging-man", "shooting-star"),
        validation = "Bar closes within the projected wick ratio",
        invalidation = "A break of the extreme wick voids the pattern",
        confluentTriggers = listOf("VWAP Band touch", "KDJ Extreme Cross")
    ),
    M5(
        id = "5m",
        label = "5 Minutes",
        minutes = 5,
        targetClasses = listOf(
            "bullish-engulfing", "bearish-engulfing", "piercing-line", "dark-cloud-cover"
        ),
        validation = "Body strictly engulfs the prior candle",
        invalidation = "A reverse engulfing candle invalidates the pattern",
        confluentTriggers = listOf("RSI Oversold/Overbought (<30 / >70)", "BB track bounce")
    ),
    M15(
        id = "15m",
        label = "15 Minutes",
        minutes = 15,
        targetClasses = listOf(
            "morning-star", "evening-star", "three-outside-up", "three-outside-down",
            "three-white-soldiers", "three-black-crows"
        ),
        validation = "Confirmation candle closes past the midpoint of the initial bar",
        invalidation = "A close back through the initial bar voids the pattern",
        confluentTriggers = listOf("SMA 5/20 Cross", "MACD zero-axis cross")
    ),
    M30(
        id = "30m",
        label = "30 Minutes",
        minutes = 30,
        targetClasses = listOf(
            "rising-flag", "falling-flag", "rising-three-methods", "falling-three-methods"
        ),
        validation = "Breakout on expanding volume",
        invalidation = "A retracement greater than 50% of the pole voids the pattern",
        confluentTriggers = listOf("MA 5>10>20 alignment", "CMF/OBV volume surge")
    ),
    H1(
        id = "1h",
        label = "1 Hour",
        minutes = 60,
        targetClasses = listOf(
            "double-bottom", "double-top", "falling-wedge", "rising-wedge",
            "ascending-triangle", "descending-triangle",
            "reverse-head-and-shoulders", "head-and-shoulders-top",
            "triple-bottom", "triple-top"
        ),
        validation = "Neckline close on heavy volume",
        invalidation = "A breach of the base support voids the pattern",
        confluentTriggers = listOf("20D/60D High/Low break", "SuperTrend continuation")
    );

    /** Bar length in milliseconds. */
    val durationMs: Long get() = minutes * 60_000L

    companion object {
        fun fromId(id: String): PatternHorizon? = entries.firstOrNull { it.id == id }
    }
}

/** The taxonomy library itself. */
object PatternTaxonomy {

    /** Every verified formation, in the directive's order. */
    val definitions: List<PatternDefinition> = listOf(
        // ---- Bottom reversals (bullish reversal) --------------------------------
        definition("hammer", "Hammer", PatternCategory.BULLISH_REVERSAL),
        definition("bullish-engulfing", "Bullish Engulfing", PatternCategory.BULLISH_REVERSAL),
        definition("three-outside-up", "Three Outside Up", PatternCategory.BULLISH_REVERSAL),
        definition("morning-star", "Morning Star", PatternCategory.BULLISH_REVERSAL),
        definition("piercing-line", "Piercing Line", PatternCategory.BULLISH_REVERSAL),
        definition("double-bottom", "Double Bottom", PatternCategory.BULLISH_REVERSAL),
        definition("reverse-head-and-shoulders", "Reverse Head and Shoulders", PatternCategory.BULLISH_REVERSAL),
        definition("triple-bottom", "Triple Bottom", PatternCategory.BULLISH_REVERSAL),
        definition("falling-wedge", "Falling Wedge", PatternCategory.BULLISH_REVERSAL),

        // ---- Bullish continuations ---------------------------------------------
        definition("three-white-soldiers", "Three White Soldiers", PatternCategory.CONTINUATION),
        definition("rising-flag", "Rising Flag", PatternCategory.CONTINUATION),
        definition("rising-three-methods", "Rising Three Methods", PatternCategory.CONTINUATION),
        definition("ascending-triangle", "Ascending Triangle", PatternCategory.CONTINUATION),

        // ---- Top reversals (bearish reversal) ----------------------------------
        definition("hanging-man", "Hanging Man", PatternCategory.BEARISH_REVERSAL),
        definition("bearish-engulfing", "Bearish Engulfing", PatternCategory.BEARISH_REVERSAL),
        definition("three-outside-down", "Three Outside Down", PatternCategory.BEARISH_REVERSAL),
        definition("shooting-star", "Shooting Star", PatternCategory.BEARISH_REVERSAL),
        // §2 binds the Evening Star to the 15-minute horizon; it is the mirror of the
        // Morning Star and must be in the vocabulary for that binding to be reachable.
        definition("evening-star", "Evening Star", PatternCategory.BEARISH_REVERSAL),
        definition("dark-cloud-cover", "Dark Cloud Cover", PatternCategory.BEARISH_REVERSAL),
        definition("double-top", "Double Top", PatternCategory.BEARISH_REVERSAL),
        definition("head-and-shoulders-top", "Head and Shoulders Top", PatternCategory.BEARISH_REVERSAL),
        definition("triple-top", "Triple Top", PatternCategory.BEARISH_REVERSAL),
        definition("rising-wedge", "Rising Wedge", PatternCategory.BEARISH_REVERSAL),

        // ---- Bearish continuations ---------------------------------------------
        definition("three-black-crows", "Three Black Crows", PatternCategory.CONTINUATION),
        definition("falling-flag", "Falling Flag", PatternCategory.CONTINUATION),
        definition("falling-three-methods", "Falling Three Methods", PatternCategory.CONTINUATION),
        definition("descending-triangle", "Descending Triangle", PatternCategory.CONTINUATION)
    )

    /** Index by taxonomy key. */
    val byId: Map<String, PatternDefinition> = definitions.associateBy { it.id }

    /** The formations a horizon may predict, in taxonomy order. */
    fun idsFor(horizon: PatternHorizon): List<String> = horizon.targetClasses

    private fun definition(
        id: String,
        name: String,
        category: PatternCategory
    ): PatternDefinition = PatternDefinition(
        id = id,
        name = name,
        category = category,
        horizons = PatternHorizon.entries
            .filter { id in it.targetClasses }
            .map { it.id }
            .toSet()
    )
}
