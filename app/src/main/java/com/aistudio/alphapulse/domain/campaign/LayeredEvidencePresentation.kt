package com.aistudio.alphapulse.domain.campaign

/**
 * C-58 — the Guardrails readout of which evidence layers are live for the inspected coin.
 *
 * Why this exists
 * ---------------
 * The layered gate decides whether a board may stage, but the operator could only read
 * that from a board's gate note, one board at a time. The guardrail tab is where the limits
 * in force are read, so it is where the three layers belong: they are limits on *this*
 * coin's evidence, and they change as its ledgers settle.
 *
 * The projection is pure so the wording is pinned by a scenario instead of a screenshot: a
 * proven layer says `ACTIVE` and names the horizons behind it, an unproven one says what it
 * is still waiting for, and PRICE alone is labelled `BLOCKING` because it is the one layer
 * that withholds staging (C-58).
 *
 * Spec Ref: Phase 2 C-57/C-58, §22, §67, §73, §74
 */

/** One row of the evidence strip: the layer, whether it is live, and why. */
data class LayerReadout(
    val layer: EvidenceLayer,
    val active: Boolean,
    val statusLabel: String,
    val detail: String
)

object LayeredEvidencePresentation {

    const val ACTIVE = "ACTIVE"
    const val INACTIVE = "INACTIVE"
    const val BLOCKING = "BLOCKING"

    /** The three rows, always in PRICE → PATTERN → STRATEGY order. */
    fun rows(verdict: LayeredEvidenceVerdict?): List<LayerReadout> =
        EvidenceLayer.entries.map { layer ->
            val proven = when (layer) {
                EvidenceLayer.PRICE -> verdict?.provenPriceHorizons.orEmpty()
                EvidenceLayer.PATTERN -> verdict?.provenPatternHorizons.orEmpty()
                // Strategy is pooled per coin, so it has no horizon list to name.
                EvidenceLayer.STRATEGY -> emptyList()
            }
            val active = when (layer) {
                EvidenceLayer.PRICE -> verdict?.canFeedCampaigns == true
                EvidenceLayer.PATTERN -> proven.isNotEmpty()
                EvidenceLayer.STRATEGY -> verdict?.strategy?.qualified == true
            }
            LayerReadout(
                layer = layer,
                active = active,
                statusLabel = when {
                    active -> ACTIVE
                    // Only PRICE withholds a board; the other two simply are not inputs yet.
                    layer == EvidenceLayer.PRICE -> BLOCKING
                    else -> INACTIVE
                },
                detail = detailFor(layer, verdict, proven)
            )
        }

    private fun detailFor(
        layer: EvidenceLayer,
        verdict: LayeredEvidenceVerdict?,
        proven: List<String>
    ): String = when (layer) {
        EvidenceLayer.PRICE -> when {
            verdict == null || verdict.price.isEmpty() -> "no price horizon has settled"
            proven.isNotEmpty() -> "proven: ${proven.joinToString(", ")}"
            else -> verdict.price.joinToString(" · ") { it.describe() }
        }

        EvidenceLayer.PATTERN -> when {
            verdict == null || verdict.pattern.isEmpty() -> "no settled formations for this coin"
            proven.isNotEmpty() -> "proven: ${proven.joinToString(", ")}"
            else -> verdict.pattern.joinToString(" · ") { it.describe() }
        }

        EvidenceLayer.STRATEGY -> verdict?.strategy?.describe()
            ?: "no graded strategy calls for this coin"
    }
}
