package com.aistudio.alphapulse.domain.ensemble

import com.aistudio.alphapulse.domain.quant.ModelState
import kotlin.math.abs

/**
 * One member of the live forecast ensemble. The three core members are always
 * active; the two mounted TSFM members join once their weights are downloaded.
 */
data class EnsembleMember(
    val id: String,
    val weightSymbol: String,
    val modelLabel: String,
    val term: String
)

data class WeightedMember(
    val member: EnsembleMember,
    val weight: Double
)

/**
 * Single source of truth for the ensemble size contract (3 -> 4 -> 5 models).
 *
 * Mounting a TSFM member rebalances the whole ensemble so ΣW = 1.000: the new
 * member receives [MOUNTED_MEMBER_WEIGHT] and the core tri-factor members are
 * scaled down proportionally into the remaining budget.
 */
object EnsembleWeighting {

    const val CORE_MODEL_COUNT = 3
    const val MAX_MODEL_COUNT = 5

    /** Share of the ensemble handed to each newly mounted TSFM model. */
    const val MOUNTED_MEMBER_WEIGHT = 0.10

    const val CHRONOS_ID = "chronos"
    const val TIMESFM_ID = "timesfm"

    private const val MOMENTUM_ID = "momentum"
    private const val TRANSFORMER_ID = "transformer"
    private const val ALPHA_ID = "alpha-residual"

    private val CORE_MEMBERS = listOf(
        EnsembleMember(MOMENTUM_ID, "W1", "Momentum-LSTM", "M1(t)"),
        EnsembleMember(TRANSFORMER_ID, "W2", "Transformer-Hybrid", "M2(t)"),
        EnsembleMember(ALPHA_ID, "W3", "Alpha-Residual", "M3(t)")
    )

    private val MOUNTED_MEMBERS = listOf(
        EnsembleMember(CHRONOS_ID, "W4", "Chronos-Bolt TSFM", "M4(t)"),
        EnsembleMember(TIMESFM_ID, "W5", "TimesFM-2.5 TSFM", "M5(t)")
    )

    /** Mounted TSFM ids in canonical W4/W5 order, tolerant of case and duplicates. */
    fun mountedExtraIds(extraModelIds: Collection<String>): List<String> =
        MOUNTED_MEMBERS
            .map { it.id }
            .filter { id -> extraModelIds.any { it.equals(id, ignoreCase = true) } }

    fun activeModelCount(extraModelIds: Collection<String>): Int =
        CORE_MODEL_COUNT + mountedExtraIds(extraModelIds).size

    fun activeMembers(extraModelIds: Collection<String>): List<EnsembleMember> {
        val mounted = mountedExtraIds(extraModelIds).toSet()
        return CORE_MEMBERS + MOUNTED_MEMBERS.filter { it.id in mounted }
    }

    fun weightOf(state: ModelState, memberId: String): Double = when (memberId) {
        MOMENTUM_ID -> state.weightK
        TRANSFORMER_ID -> state.weightP
        ALPHA_ID -> state.weightAlpha
        CHRONOS_ID -> state.weight4
        TIMESFM_ID -> state.weight5
        else -> 0.0
    }

    fun weightVector(state: ModelState, extraModelIds: Collection<String>): List<WeightedMember> =
        activeMembers(extraModelIds).map { WeightedMember(it, weightOf(state, it.id)) }

    fun totalWeight(state: ModelState, extraModelIds: Collection<String>): Double =
        weightVector(state, extraModelIds).sumOf { it.weight }

    /**
     * Rebalance [state] in place so the active members sum to 1.000.
     * Core weights keep their relative split inside the remaining budget.
     */
    fun rebalanceForMountedModels(state: ModelState, extraModelIds: Collection<String>) {
        val mounted = mountedExtraIds(extraModelIds)
        val mountedBudget = (MOUNTED_MEMBER_WEIGHT * mounted.size).coerceAtMost(1.0)
        val coreBudget = (1.0 - mountedBudget).coerceAtLeast(0.0)

        val core = listOf(abs(state.weightK), abs(state.weightP), abs(state.weightAlpha))
        val coreTotal = core.sum()
        if (coreTotal > 1e-9) {
            state.weightK = core[0] / coreTotal * coreBudget
            state.weightP = core[1] / coreTotal * coreBudget
            state.weightAlpha = core[2] / coreTotal * coreBudget
        } else {
            state.weightK = coreBudget / 3.0
            state.weightP = coreBudget / 3.0
            state.weightAlpha = coreBudget / 3.0
        }

        state.weight4 = if (CHRONOS_ID in mounted) MOUNTED_MEMBER_WEIGHT else 0.0
        state.weight5 = if (TIMESFM_ID in mounted) MOUNTED_MEMBER_WEIGHT else 0.0
    }

    fun factorLabel(modelCount: Int): String = when (modelCount) {
        CORE_MODEL_COUNT -> "TRI-FACTOR"
        4 -> "QUAD-FACTOR"
        5 -> "PENTA-FACTOR"
        else -> "MULTI-FACTOR"
    }

    fun modelTypeLabel(modelCount: Int): String = when (modelCount) {
        CORE_MODEL_COUNT -> "Tri-Factor"
        4 -> "Quad-Factor"
        5 -> "Penta-Factor"
        else -> "Multi-Factor"
    }

    fun forecastTitle(modelCount: Int): String = "${factorLabel(modelCount)} ENSEMBLE TARGET FORECASTS"

    /** Live formula subheader, e.g. `Ensemble_t = W1·M1(t) + W2·M2(t) [ΣW = 1.000]`. */
    fun formula(modelCount: Int): String {
        val terms = (1..modelCount.coerceIn(CORE_MODEL_COUNT, MAX_MODEL_COUNT))
            .joinToString(" + ") { "W$it·M$it(t)" }
        return "Ensemble_t = $terms  [ΣW = 1.000]"
    }
}
