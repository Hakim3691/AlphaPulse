package com.aistudio.alphapulse.domain.campaign

/**
 * §22's four-component staging precondition.
 *
 * Why this exists
 * ---------------
 * `AlphaPulse_Android_SPEC_V14-3.md` §22 keeps the LTF/MTF/HTF readiness framework and
 * adds four named components — `TSFM Ready`, `Neural LOB Ready`, `Liquidity Heatmap
 * Ready`, `Ensemble Ready` — each "based on actual validation availability". A campaign
 * that stages on a forecast while one of those inputs was never observed is staging on a
 * pipeline that is only partly warm: the ladder looks complete because the missing
 * component simply contributed nothing.
 *
 * The rule is therefore an AND over all four. Every component must be observed `READY`;
 * a component missing from the evidence is `UNOBSERVED`, which is a failure and never a
 * pass; an explicit measurement that failed is `UNAVAILABLE`. The refusal lists every
 * unmet component in declaration order so the gate's note names exactly what is missing.
 * This precondition is additional to the existing readiness framework and the settled
 * cone hit-rate floor, not a replacement for them.
 *
 * Spec Ref: AlphaPulse §22 Per-Horizon Readiness, ProactiveMS §22 Order Staging, C-19
 */

/** The four named components §22 requires before a campaign may stage. */
enum class StagingComponent(val label: String) {
    TSFM("TSFM Ready"),
    NEURAL_LOB("Neural LOB Ready"),
    LIQUIDITY_HEATMAP("Liquidity Heatmap Ready"),
    ENSEMBLE("Ensemble Ready")
}

/** What the pipeline observed for one component. Absence is never readiness. */
enum class ComponentAvailability {
    /** Actually observed and usable. */
    READY,

    /** Measured and failed: the source answered, but the component cannot be used. */
    UNAVAILABLE,

    /** Never observed, or no evidence was supplied. */
    UNOBSERVED
}

/**
 * What the shipped pipeline has actually observed for one symbol, before judgment.
 *
 * Kept separate from [StagingPreconditionVerdict] so the derivation is a pure function:
 * the app only supplies measurements, and the rule for turning them into readiness lives
 * in one place that a BDD scenario can exercise directly.
 */
data class StagingObservations(
    /** Model tiers of the persisted forecast ladder, e.g. `Tri-Factor`, `Penta-Factor`. */
    val forecastModelTypes: List<String> = emptyList(),
    /** Distinct forecast horizons in that ladder, e.g. `5m`, `15m`, `1h`. */
    val forecastHorizons: Set<String> = emptySet(),
    /** Sides of the live book with at least one level: 0, 1 or 2. */
    val bookSides: Int = 0,
    /** Timestamp of the newest persisted liquidation-heatmap row, or null if none. */
    val heatmapObservedAtMs: Long? = null
)

/** The four-component verdict: permit staging, and name what was missing when not. */
data class StagingPreconditionVerdict(
    val canStage: Boolean,
    val unmet: List<StagingComponent>,
    val reasons: List<String>,
    /** What was observed for every component, so the UI can word the hold correctly. */
    val states: Map<StagingComponent, ComponentAvailability> = emptyMap()
) {
    /** The gate's note: a stable, component-named sentence for logs and the audit trail. */
    fun note(): String =
        if (canStage) "Staging precondition met: all four readiness components observed"
        else "Staging precondition unmet: ${reasons.joinToString(", ")}"

    /**
     * The board card's copy: the same facts, in words a trader reads once and understands.
     *
     * The raw `TSFM_UNOBSERVED` tokens belong in the log, not on a card, so each unmet
     * component is named by its §22 label with what actually happened to it: an input that
     * was never observed reads differently from one that answered but had nothing usable.
     */
    fun displayNote(): String =
        if (canStage) {
            "All four readiness inputs observed"
        } else {
            "Held as a plan — " + unmet.joinToString(", ") { component ->
                val state = states[component] ?: ComponentAvailability.UNOBSERVED
                val what = when (state) {
                    ComponentAvailability.UNAVAILABLE -> "unavailable"
                    else -> "not observed"
                }
                "${component.label} $what"
            }
        }
}

object StagingPrecondition {

    /** Every component must be `READY`; anything else blocks and is named. */
    fun evaluate(
        availability: Map<StagingComponent, ComponentAvailability>
    ): StagingPreconditionVerdict {
        val unmet = StagingComponent.entries.filter { component ->
            availability[component] != ComponentAvailability.READY
        }
        val states = StagingComponent.entries.associateWith { component ->
            availability[component] ?: ComponentAvailability.UNOBSERVED
        }
        val reasons = unmet.map { component ->
            val state = states.getValue(component)
            "${component.name}_${state.name}"
        }
        return StagingPreconditionVerdict(
            canStage = unmet.isEmpty(),
            unmet = unmet,
            reasons = reasons,
            states = states
        )
    }

    /** The evidence a cold pipeline actually has: nothing observed, nothing ready. */
    fun unobserved(): StagingPreconditionVerdict = evaluate(emptyMap())

    /** Model tiers that carry the TSFM block; a tri-factor ladder does not. */
    val TSFM_MODEL_TYPES = setOf("Quad-Factor", "Penta-Factor")

    /** The window the §22 liquidation heatmap describes. */
    const val HEATMAP_FRESHNESS_MS = 24L * 60L * 60L * 1000L

    /** The tri-factor minimum for `Ensemble Ready`. */
    const val MIN_ENSEMBLE_HORIZONS = 3

    /**
     * Turns observations into the four-component verdict.
     *
     * A component with no measurement is `UNOBSERVED` — never ready. A heatmap row that
     * exists but predates [heatmapFreshnessMs] is `UNAVAILABLE`: the source answered, its
     * answer is just too old to stage on. A timestamp slightly ahead of [nowMs] is treated
     * as fresh so a device clock skew cannot read as a missing map.
     */
    fun fromObservations(
        observations: StagingObservations,
        nowMs: Long,
        heatmapFreshnessMs: Long = HEATMAP_FRESHNESS_MS,
        minEnsembleHorizons: Int = MIN_ENSEMBLE_HORIZONS
    ): StagingPreconditionVerdict {
        val heatmapAge = observations.heatmapObservedAtMs?.let { nowMs - it }
        val heatmap = when {
            heatmapAge == null -> ComponentAvailability.UNOBSERVED
            heatmapAge < -heatmapFreshnessMs -> ComponentAvailability.UNAVAILABLE
            heatmapAge <= heatmapFreshnessMs -> ComponentAvailability.READY
            else -> ComponentAvailability.UNAVAILABLE
        }
        return evaluate(
            mapOf(
                StagingComponent.TSFM to availabilityOf(
                    observations.forecastModelTypes.any { it in TSFM_MODEL_TYPES }
                ),
                StagingComponent.NEURAL_LOB to availabilityOf(observations.bookSides >= 2),
                StagingComponent.LIQUIDITY_HEATMAP to heatmap,
                StagingComponent.ENSEMBLE to availabilityOf(
                    observations.forecastHorizons.size >= minEnsembleHorizons
                )
            )
        )
    }

    private fun availabilityOf(observed: Boolean): ComponentAvailability =
        if (observed) ComponentAvailability.READY else ComponentAvailability.UNOBSERVED
}
