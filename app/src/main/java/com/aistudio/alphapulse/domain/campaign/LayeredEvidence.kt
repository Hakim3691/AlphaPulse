package com.aistudio.alphapulse.domain.campaign

/**
 * C-57 — the layered gate: price first, then pattern and strategy as each proves itself.
 *
 * Why this exists
 * ---------------
 * The earlier gate was one combined threshold over one blended number, so it answered a
 * question nobody asked: *is the average of everything good enough?* A coin whose price
 * cone was excellent but whose pattern classifier had never settled could still arm a
 * board, and a coin whose price cone was poor could hide behind a lucky strategy sample.
 *
 * ProactiveMS now layers its evidence, because its three inputs arrive at different
 * speeds and mean different things:
 *
 *  - **PRICE** — AlphaPulse's multi-horizon cone. This is the only *blocking* layer:
 *    without a proven price horizon there is nothing to price a ladder from.
 *  - **PATTERN** — the §73 pattern ledger, per coin and per horizon. It becomes an
 *    additional input as soon as that horizon's settled formations clear 85%.
 *  - **STRATEGY** — the §74 strategy ledger, per coin. It becomes an additional input
 *    once its graded rows clear 85% over at least twenty graded calls.
 *
 * "Layered" is deliberate: a layer that has not proven itself is simply inactive, and it
 * never withholds a board the price layer has already proven. A layer that has proven
 * itself stays active for the cycle's verdict, and the verdict is recorded on the board,
 * so a decision can always be explained by the evidence that was live when it was made.
 * Activation is not retroactive: today's verdict never re-grades yesterday's board.
 *
 * Spec Ref: Phase 2 C-57, §22, §67, §73, §74
 */
enum class EvidenceLayer {
    PRICE,
    PATTERN,
    STRATEGY
}

/**
 * One layer's record, at the scope that layer is graded in.
 *
 * @param layer which input this is
 * @param scope the horizon id for [EvidenceLayer.PRICE] / [EvidenceLayer.PATTERN], or
 *   the coin symbol for [EvidenceLayer.STRATEGY], which is pooled across strategies
 * @param settled graded rows whose outcome is known
 * @param hits graded rows that called their outcome correctly
 * @param minSettled this layer's own sample floor
 */
data class LayerEvidence(
    val layer: EvidenceLayer,
    val scope: String,
    val settled: Int,
    val hits: Int,
    val minSettled: Int
) {
    /** Hits as a percentage of settled rows; 0.0 when nothing settled. */
    val hitRatePct: Double get() = if (settled > 0) hits * 100.0 / settled else 0.0

    /** Enough sample and enough correct calls; the same integer boundary as C-57. */
    val qualified: Boolean
        get() = settled >= minSettled &&
            hits >= 0 &&
            hits <= settled &&
            hits * 100 >= SignalEvidence.MIN_HIT_RATE_PCT.toInt() * settled

    /** The one-line account printed on the board, e.g. `1h 50.0% of 2 settled`. */
    fun describe(): String {
        val rate = Math.round(hitRatePct * 10.0) / 10.0
        return when (layer) {
            EvidenceLayer.STRATEGY ->
                "strategy $rate% of $settled graded (needs " +
                    "${SignalEvidence.MIN_HIT_RATE_PCT}% of $minSettled)"
            else -> "$scope $rate% of $settled settled (needs " +
                "${SignalEvidence.MIN_HIT_RATE_PCT}% of $minSettled)"
        }
    }
}

/**
 * The layered verdict for one coin.
 *
 * @param price one record per horizon the coin has issued
 * @param pattern one record per pattern horizon the coin has settled
 * @param strategy the pooled §74 record, when the coin has graded strategy calls
 */
data class LayeredEvidenceVerdict(
    val price: List<LayerEvidence> = emptyList(),
    val pattern: List<LayerEvidence> = emptyList(),
    val strategy: LayerEvidence? = null
) {
    /** Horizons whose price cone has proven itself at its own 85% floor. */
    val provenPriceHorizons: List<String>
        get() = price.filter { it.qualified }.map { it.scope }.sorted()

    /** Pattern horizons that are now an additional input. */
    val provenPatternHorizons: List<String>
        get() = pattern.filter { it.qualified }.map { it.scope }.sorted()

    /** True when at least one price horizon may feed a board. */
    val canFeedCampaigns: Boolean get() = provenPriceHorizons.isNotEmpty()

    /** PRICE blocks staging; PATTERN and STRATEGY are additive and never block. */
    val canStage: Boolean get() = canFeedCampaigns

    /** Every layer live for this verdict. */
    val activeLayers: Set<EvidenceLayer>
        get() = buildSet {
            if (canFeedCampaigns) add(EvidenceLayer.PRICE)
            if (provenPatternHorizons.isNotEmpty()) add(EvidenceLayer.PATTERN)
            if (strategy?.qualified == true) add(EvidenceLayer.STRATEGY)
        }

    /**
     * Why staging is held. Empty when the price layer is proven; otherwise it names the
     * price horizons that have not, so the operator is told which evidence is missing
     * rather than that "the gate" failed.
     */
    val blockingReasons: List<String>
        get() = if (canStage) emptyList() else {
            val unproven = price.filterNot { it.qualified }
            if (unproven.isEmpty()) {
                listOf("PRICE_LAYER_NOT_PROVEN(no price horizon has settled)")
            } else {
                unproven.map { "PRICE_LAYER_NOT_PROVEN(${it.describe()})" }
            }
        }

    /** One line for the board's gate note and the audit log. */
    fun summary(): String {
        val active = activeLayers.joinToString("+") { it.name }.ifEmpty { "NONE" }
        if (canStage) {
            return "Layered evidence active: $active " +
                "(price ${provenPriceHorizons.joinToString(",")})"
        }
        return "Layered evidence withheld: " + blockingReasons.joinToString(" · ")
    }
}

/**
 * Builds a [LayeredEvidenceVerdict] from what the shipped ledgers actually observed.
 *
 * It is a pure function: every input arrives explicit, so the boundaries can be pinned
 * without a database, a clock or a network.
 */
object LayeredEvidenceGate {

    /** The PRICE layer for one horizon, at that horizon's own floor. */
    fun price(horizonId: String, settled: Int, hits: Int): LayerEvidence =
        record(EvidenceLayer.PRICE, horizonId, settled, hits, SignalEvidence.minSettledFor(horizonId))

    /** The PATTERN layer for one horizon; it uses the same tiered floors as price. */
    fun pattern(horizonId: String, settled: Int, hits: Int): LayerEvidence =
        record(EvidenceLayer.PATTERN, horizonId, settled, hits, SignalEvidence.minSettledFor(horizonId))

    /**
     * The STRATEGY layer, pooled across strategies for one coin.
     *
     * Twenty graded calls is the confirmed floor: the strategies fire per bar, so the
     * sample accumulates quickly, and a coin is not credited for a strategy sample that
     * cannot yet distinguish 85% from a coin flip.
     */
    fun strategy(symbol: String, settled: Int, hits: Int): LayerEvidence =
        record(EvidenceLayer.STRATEGY, symbol, settled, hits, STRATEGY_MIN_GRADED)

    /** The confirmed sample floor for the pooled strategy layer. */
    const val STRATEGY_MIN_GRADED = 20

    /**
     * The verdict. Price is required for staging; pattern and strategy only ever add.
     */
    fun assess(
        price: List<LayerEvidence>,
        pattern: List<LayerEvidence> = emptyList(),
        strategy: LayerEvidence? = null
    ): LayeredEvidenceVerdict = LayeredEvidenceVerdict(
        price = price.sortedBy { it.scope },
        pattern = pattern.sortedBy { it.scope },
        strategy = strategy
    )

    private fun record(
        layer: EvidenceLayer,
        scope: String,
        settled: Int,
        hits: Int,
        minSettled: Int
    ): LayerEvidence {
        val safeSettled = settled.coerceAtLeast(0)
        return LayerEvidence(
            layer = layer,
            scope = scope,
            settled = safeSettled,
            hits = hits.coerceIn(0, safeSettled),
            minSettled = minSettled
        )
    }
}
