package com.aistudio.alphapulse.domain.presentation

import com.aistudio.alphapulse.domain.decision.DecisionReport
import com.aistudio.alphapulse.domain.decision.DecisionState
import com.aistudio.alphapulse.domain.strategy.SignalDirection
import com.aistudio.alphapulse.domain.strategy.StrategySignal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Phase 1 string tokenization (D1).
 *
 * The decisions ledger used to print the router's raw sentence, so the column
 * clipped mid-word on a 360dp screen and pushed the confidence and the gate
 * threshold off-screen. A row now reads as one fixed-width token:
 *
 * ```
 * [N3: Long @ 0.50 < 0.70] Fallback: Rules
 * ```
 *
 * Model, proposed action, confidence, gate and the fallback all fit inside 180dp
 * because nothing is free-form text any more.
 *
 * Spec Ref: Phase 1 D1 String Tokenization
 */
object TokenizedReason {

    /** §29's calibrated-confidence gate; the ledger prints the number it compared. */
    const val GATE_THRESHOLD: Double = 0.70

    /**
     * Longest token the REASON column must render, measured from the rendered
     * grammar: `[N3: Strong Short @ 0.70 < 0.70] Fallback: Needle 3` is 51 chars.
     * `DecisionsLedgerSection`'s reason cell is 240dp at 8sp monospace, which covers
     * the whole grammar with no clipping; the table still scrolls horizontally at
     * 320-360dp, so nothing is hidden.
     */
    const val MAX_TOKEN_CHARS: Int = 51

    /**
     * The token's model slot.
     *
     * Only two routers exist, so the known names are abbreviated and anything else
     * is compacted to four characters. An unrecognised source used to be printed in
     * full, which could push the score and gate outside the column - the exact defect
     * D1 exists to remove.
     */
    fun modelOf(source: String): String = when (source) {
        "NEEDLE3" -> "N3"
        "DETERMINISTIC" -> "RULES"
        else -> source.trim().take(4).uppercase(Locale.US).ifBlank { "N3" }
    }

    fun actionOf(state: String): String = when (state) {
        "STRONG_LONG" -> "Strong Long"
        "LONG" -> "Long"
        "STRONG_SHORT" -> "Strong Short"
        "SHORT" -> "Short"
        "NEUTRAL" -> "Neutral"
        "NO_TRADE" -> "No Trade"
        else -> state.replace('_', ' ')
    }

    /** The fallback actually used once the gate refused the proposal. */
    fun fallbackOf(source: String, state: String, confidence: Double): String {
        val refusal = source == "NEEDLE3" && state != "NEUTRAL" && state != "NO_TRADE" &&
            confidence >= GATE_THRESHOLD
        return if (refusal) "Needle 3" else "Rules"
    }

    fun of(source: String, state: String, confidence: Double): String {
        val score = if (confidence > 0.0) String.format(Locale.US, "%.2f", confidence) else "--"
        return "[${modelOf(source)}: ${actionOf(state)} @ $score < " +
            "${String.format(Locale.US, "%.2f", GATE_THRESHOLD)}] " +
            "Fallback: ${fallbackOf(source, state, confidence)}"
    }
}

/**
 * Phase 1 D2: a NEUTRAL badge must say *why* the engine is flat.
 *
 * The three causes are mutually distinguishable from the report and the board:
 * a calibrated confidence below the gate, two opposing strategy camps, or no
 * calibrated number at all (the quorum never formed).
 *
 * Spec Ref: Phase 1 D2 Neutral Explanations
 */
object NeutralAttribution {

    fun isFlat(decision: DecisionReport?): Boolean =
        decision == null || decision.state == DecisionState.NEUTRAL ||
            decision.state == DecisionState.NO_TRADE

    /** One explanatory sub-label, or null when the verdict is directional. */
    fun of(decision: DecisionReport?, longCount: Int, shortCount: Int): String? {
        if (decision == null || !isFlat(decision)) return null

        if (longCount > 0 && shortCount > 0) {
            return "Directional Conflict: $longCount Long vs. $shortCount Short Strategies"
        }
        if (decision.isCalibrated && decision.confidence < TokenizedReason.GATE_THRESHOLD) {
            return "Confidence Gate Unmet: Needle 3 @ " +
                String.format(Locale.US, "%.2f", decision.confidence) +
                " (Req \u2265 " +
                String.format(Locale.US, "%.2f", TokenizedReason.GATE_THRESHOLD) + ")"
        }
        return "Quorum Failure: Minimum 2 Model Votes Required"
    }
}

/**
 * Phase 1 D3: retain every strategy card, but never imply that two opposing
 * setups are equal. One direction is the dominant read; a card that trades
 * against it is tagged [WATCH: CONFLICT] and says what it is fighting.
 *
 * Spec Ref: Phase 1 D3 Strategy Conflict Tags
 */
object StrategyConflicts {

    fun dominantDirection(signals: List<StrategySignal>): SignalDirection? {
        val active = signals.filter { it.isActive }
        if (active.isEmpty()) return null
        val longs = active.count { it.direction == SignalDirection.BULLISH }
        val shorts = active.count { it.direction == SignalDirection.BEARISH }
        return when {
            longs > shorts -> SignalDirection.BULLISH
            shorts > longs -> SignalDirection.BEARISH
            // A tie is broken by the first card, which is the strategic lead.
            else -> active.first().direction
        }
    }

    fun isConflict(signal: StrategySignal, dominant: SignalDirection?): Boolean =
        signal.isActive && dominant != null &&
            signal.direction != SignalDirection.NEUTRAL && signal.direction != dominant

    /** `[DOMINANT]`, `[WATCH: CONFLICT]`, or null for an abstaining card. */
    fun tagOf(signal: StrategySignal, dominant: SignalDirection?): String? = when {
        !signal.isActive -> null
        signal.direction == dominant -> "[DOMINANT]"
        isConflict(signal, dominant) -> "[WATCH: CONFLICT]"
        else -> null
    }

    fun noteOf(signal: StrategySignal, dominant: SignalDirection?): String? {
        if (!isConflict(signal, dominant)) return null
        val lead = when (dominant) {
            SignalDirection.BULLISH -> "bullish MTF drift"
            SignalDirection.BEARISH -> "bearish MTF drift"
            else -> "higher-timeframe drift"
        }
        return "Counter-trend setup against dominant $lead"
    }
}

/**
 * Phase 1 §3 addition: how far the shelf is, in units of ATR, and whether the
 * zone is still fresh. `Dist: 0.3 ATR · Zone: Fresh (Touch 1/3)`.
 */
object ZoneMetadata {

    const val MAX_TOUCHES: Int = 3

    fun freshness(touches: Int): String = when {
        touches <= 0 -> "Fresh"
        touches < MAX_TOUCHES -> "Tested"
        else -> "Exhausted"
    }

    fun format(distanceAtr: Double?, touches: Int): String {
        val distance = if (distanceAtr == null || distanceAtr.isNaN()) "--"
        else String.format(Locale.US, "%.1f", distanceAtr)
        return "Dist: $distance ATR \u00b7 Zone: ${freshness(touches)} " +
            "(Touch $touches/$MAX_TOUCHES)"
    }
}

/**
 * Phase 1 §3 addition: the SGD column must describe the operational action, not
 * print debug prose. `SGD Step: - w_penalty (Loss: 1.10)` becomes
 * `[PENALTY: Loss 1.10]` plus the formation it was graded against.
 */
object SgdActionTag {

    private val LOSS = Regex("""Loss:\s*([0-9]+(?:\.[0-9]+)?)""")

    fun of(action: String, realizedPattern: String?): String {
        if (action.isBlank()) return action
        return when {
            action.startsWith("SGD Skip") -> {
                val formation = realizedPattern?.takeIf { it.isNotBlank() }
                if (formation != null) "[SKIP: False Alarm] $formation unconfirmed by tape"
                else "[SKIP: False Alarm] unconfirmed by tape"
            }
            action.contains("+w_reinforce") ->
                "[PRESERVED] Weights refined via SGD"
            action.contains("w_penalty") -> {
                val loss = LOSS.find(action)?.groupValues?.getOrNull(1)
                val tag = if (loss != null) "[PENALTY: Loss $loss]" else "[PENALTY]"
                val formation = realizedPattern?.takeIf { it.isNotBlank() } ?: "setup"
                "$tag $formation confirmed"
            }
            else -> action
        }
    }
}

/**
 * Phase 1 §3 addition: the horizon filter is annotated by intent so a trader
 * knows whether they are scalp-path plotting or reading macro drift.
 */
object HorizonGroups {

    fun groupOf(horizon: String): String? = when (horizon) {
        "1m", "5m", "15m" -> "TACTICAL"
        "30m", "1h" -> "STRATEGIC"
        else -> null
    }

    fun captionOf(group: String): String = when (group) {
        "TACTICAL" -> "Tactical Horizons: scalp pathing, order-book absorption, local wick rejections"
        "STRATEGIC" -> "Strategic Horizons: macro trend drift, funding squeeze, HTF value-area alignment"
        else -> ""
    }
}

/**
 * Phase 1 §3 addition: the one-line liquidity magnet under the spot price.
 *
 * The headline used to be the spot price alone, so a trader could see where price
 * was but not where it was most likely to be pulled. The prior names the route, the
 * magnet price and a bounded 0-100 pain score derived from the same order-book /
 * liquidation path the forecast consumes - no new data source, no invented level.
 *
 * `Pain Path: Ascending Sweep to $23.85000 Overhead Stop Cluster (Pain Score: 78/100)`
 */
object PainPathPrior {

    fun of(spotPrice: Double, muPath: Double, confidence: Double): String? {
        if (!spotPrice.isFinite() || spotPrice <= 0.0) return null
        if (!muPath.isFinite() || muPath == 0.0) return null
        val magnet = spotPrice * exp(muPath)
        if (!magnet.isFinite() || magnet <= 0.0) return null

        val ascending = muPath > 0.0
        val route = if (ascending) "Ascending Sweep" else "Descending Flush"
        val cluster = if (ascending) "Overhead Stop Cluster" else "Support Stop Cluster"
        return "Pain Path: $route to $" +
            String.format(Locale.US, "%.5f", magnet) + " $cluster " +
            "(Pain Score: ${painScore(muPath, confidence)}/100)"
    }

    /**
     * Bounded 0-100 pain score: how far the path can reach plus how convinced the
     * confluence engine is. Documented arithmetic rather than a tuned constant.
     */
    fun painScore(muPath: Double, confidence: Double): Int {
        val reach = (abs(muPath) * 10_000.0).coerceIn(0.0, 60.0)
        val conviction = confidence.coerceIn(0.0, 1.0) * 40.0
        return (reach + conviction).roundToInt().coerceIn(0, 100)
    }
}

/**
 * Phase 1 §3 addition: a strategy card with no trigger is not an invitation to
 * invent levels; it says so. A live card publishes the price that voids it.
 */
object StrategyLevels {

    fun unavailableStatus(): String = "STATUS: Staging Suspended (Awaiting Trigger)"

    fun invalidationLabel(invalidation: Double?, horizon: String = "1m"): String =
        if (invalidation == null) "--"
        else String.format(Locale.US, "%.4f", invalidation) +
            " ($horizon Close Below Shelf)"
}
