package com.aistudio.alphapulse.domain.campaign

/**
 * §68 / Contract C-19 — the gate a campaign must pass before it may arm orders.
 *
 * Why this exists
 * ---------------
 * A campaign that is planned is only a *shape*: a bias, a ladder, a defensive board.
 * Whether it may actually place those orders depends on data that is still arriving
 * when the campaign is built — the short timeframes settle second, the ensemble models
 * mount asynchronously, and the cone hit rate is a rolling measurement that can spend
 * an afternoon below its floor. Without one gate that answers "may this campaign arm
 * right now", each of those checks ends up re-implemented at the call site, and a
 * campaign arms on a partially-warm pipeline the first time one of them is forgotten.
 *
 * The gate is deliberately a pure function of telemetry: it reads no clock, no
 * database and no network, so the dashboard can evaluate it on every tick and the
 * BDD scenarios can pin every boundary without a fixture.
 *
 * The hit-rate floor is strict: a horizon whose cone hit rate is *exactly* 90.0% is
 * not ready. The specification asks for greater than 90%, and treating the boundary
 * as passing would quietly lower the floor each time the check is re-derived.
 *
 * Spec Ref: §68 Readiness Gate, C-19
 */

/** Where a campaign sits in its lifecycle. */
enum class CampaignLifecycleState {
    /** Built but not permitted to arm orders yet. */
    PLANNED,

    /** Every readiness condition holds; orders may be armed. */
    STAGED,

    /** Held back on purpose, independent of readiness. */
    SUPPRESSED
}

/**
 * The readiness telemetry the dashboard already has, in one immutable snapshot.
 *
 * @param settled1mCount settled 1m candles the LTF structure has consumed
 * @param settled5mCount settled 5m candles
 * @param settled15mCount settled 15m candles
 * @param settled30mCount settled 30m candles
 * @param settled1hCount settled 1h candles
 * @param readyEnsembleModels mounted ensemble members
 * @param totalEnsembleModels members the mounted tier requires
 * @param readyTsfmDockModels TSFM models whose weights are loaded
 * @param requiredTsfmDockModels TSFM models required for the current tier
 * @param horizonConeHitRatePct rolling cone hit rate per horizon id, in percent
 * @param componentAvailability §22's four readiness components as actually observed; an
 *   absent entry is unobserved and blocks staging (see [StagingPrecondition])
 */
data class AlphaPulseReadinessTelemetry(
    val settled1mCount: Int,
    val settled5mCount: Int,
    val settled15mCount: Int,
    val settled30mCount: Int,
    val settled1hCount: Int,
    val readyEnsembleModels: Int,
    val totalEnsembleModels: Int,
    val readyTsfmDockModels: Int,
    val requiredTsfmDockModels: Int = 2,
    val horizonConeHitRatePct: Map<String, Double>,
    val componentAvailability: Map<StagingComponent, ComponentAvailability> = emptyMap()
) {
    /** The fast structure needs two 1m closes and one 5m close before it is stable. */
    val isLtfReady: Boolean
        get() = settled1mCount >= 2 && settled5mCount >= 1

    /** Mid timeframes: two 15m closes and one 30m close. */
    val isMtfReady: Boolean
        get() = settled15mCount >= 2 && settled30mCount >= 1

    /** High timeframes: two 30m closes and one 1h close. */
    val isHtfReady: Boolean
        get() = settled30mCount >= 2 && settled1hCount >= 1

    /** Every member of the mounted tier is mounted, and a tier is at least a tri-factor. */
    val isEnsembleReady: Boolean
        get() = readyEnsembleModels == totalEnsembleModels && totalEnsembleModels >= 3

    /** The TSFM dock holds exactly what the tier requires — no partial mounts. */
    val isTsfmDockReady: Boolean
        get() = readyTsfmDockModels == requiredTsfmDockModels

    /** §22's four-component precondition, evaluated from the observed evidence. */
    val stagingPrecondition: StagingPreconditionVerdict
        get() = StagingPrecondition.evaluate(componentAvailability)
}

/** The gate's verdict: the state the campaign may enter, and why not when it may not. */
data class GateEvaluationResult(
    val nextState: CampaignLifecycleState,
    val canArmOrders: Boolean,
    val rejectionReasons: List<String>
)

/**
 * Decides whether a planned campaign may be staged.
 *
 * @param minConeHitRateExclusivePct the floor a governing horizon must *exceed*
 */
class CampaignReadinessGate(
    private val minConeHitRateExclusivePct: Double = 90.0
) {

    /**
     * @param telemetry the current readiness snapshot
     * @param governingHorizons the horizon ids whose cone hit rate governs this
     *   campaign — the ones the campaign's bias and ladder were derived from
     */
    fun evaluate(
        telemetry: AlphaPulseReadinessTelemetry,
        governingHorizons: List<String>
    ): GateEvaluationResult {
        val reasons = mutableListOf<String>()
        if (!telemetry.isLtfReady) reasons.add("LTF_NOT_READY")
        if (!telemetry.isMtfReady) reasons.add("MTF_NOT_READY")
        if (!telemetry.isHtfReady) reasons.add("HTF_NOT_READY")
        if (!telemetry.isEnsembleReady) reasons.add("ENSEMBLE_NOT_5_OF_5_READY")
        if (!telemetry.isTsfmDockReady) reasons.add("TSFM_DOCK_NOT_2_OF_2_READY")

        // §22's four named components are additional to the framework above: every one
        // must be actually observed, not merely absent from the refusal list.
        reasons.addAll(telemetry.stagingPrecondition.reasons)

        governingHorizons.forEach { horizon ->
            // A governing horizon with no measurement is not ready: silence from the
            // cone tracker must never be read as a passing hit rate.
            val hitRate = telemetry.horizonConeHitRatePct[horizon] ?: 0.0
            if (hitRate <= minConeHitRateExclusivePct) {
                reasons.add("HORIZON_${horizon}_CONE_HIT_RATE_BELOW_OR_EQUAL_90_PCT($hitRate%)")
            }
        }

        return if (reasons.isEmpty()) {
            GateEvaluationResult(
                nextState = CampaignLifecycleState.STAGED,
                canArmOrders = true,
                rejectionReasons = emptyList()
            )
        } else {
            GateEvaluationResult(
                nextState = CampaignLifecycleState.PLANNED,
                canArmOrders = false,
                rejectionReasons = reasons
            )
        }
    }
}
