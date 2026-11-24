package com.aistudio.alphapulse.domain.campaign

import java.util.Locale

/**
 * C-60 — the Guardrails Thresholds readout.
 *
 * Two numbers that differ only in wording are indistinguishable on a screenshot, so the
 * projection that prints them is pure and pinned by its own scenario: every layer states the
 * configured floor and sample floor, and beneath it the inspected coin's own measured rate
 * against those limits. When the coin has no evidence for a layer, the row says which
 * evidence is missing instead of implying a rate.
 *
 * The rates are described against the [config] that was passed in, not against whatever is
 * globally installed, so the tab can never print a limit it is not actually applying.
 *
 * Spec Ref: Phase 2 C-57/C-58/C-60
 */

/** One row of the Thresholds surface: the layer, its configured limits and the live rate. */
data class ThresholdRow(
    val layer: EvidenceLayer,
    val floorLabel: String,
    val samplesLabel: String,
    val effective: String
)

object ThresholdPresentation {

    fun rows(
        config: ThresholdConfig,
        verdict: LayeredEvidenceVerdict?
    ): List<ThresholdRow> = EvidenceLayer.entries.map { layer ->
        ThresholdRow(
            layer = layer,
            floorLabel = config.describeFloor(),
            samplesLabel = config.describeSamplesFor(layer),
            effective = effectiveFor(layer, config, verdict)
        )
    }

    private fun effectiveFor(
        layer: EvidenceLayer,
        config: ThresholdConfig,
        verdict: LayeredEvidenceVerdict?
    ): String = when (layer) {
        EvidenceLayer.PRICE -> when {
            verdict == null || verdict.price.isEmpty() -> NO_PRICE
            else -> verdict.price.joinToString(" · ") { describe(it, config) }
        }

        EvidenceLayer.PATTERN -> when {
            verdict == null || verdict.pattern.isEmpty() -> NO_PATTERN
            else -> verdict.pattern.joinToString(" · ") { describe(it, config) }
        }

        EvidenceLayer.STRATEGY -> verdict?.strategy
            ?.let { describe(it, config) }
            ?: NO_STRATEGY
    }

    private fun describe(evidence: LayerEvidence, config: ThresholdConfig): String {
        val rate = String.format(Locale.US, "%.1f%%", evidence.hitRatePct)
        val needs = "${config.describeFloor()} of ${config.minSettledFor(evidence.scope)}"
        val base = when (evidence.layer) {
            EvidenceLayer.STRATEGY ->
                "strategy $rate of ${evidence.settled} graded (needs $needs)"
            else -> "${evidence.scope} $rate of ${evidence.settled} settled (needs $needs)"
        }
        // C-61: the tab states which rate is no longer in force, so a stale layer is never
        // read as a live input.
        return if (evidence.staleReason == null) base else "$base · ${evidence.staleReason}"
    }

    const val NO_PRICE = "no price horizon has settled"
    const val NO_PATTERN = "no settled formations for this coin"
    const val NO_STRATEGY = "no graded strategy calls for this coin"
}
