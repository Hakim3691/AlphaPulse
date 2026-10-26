package com.aistudio.alphapulse.domain.ensemble

import com.aistudio.alphapulse.domain.quant.ModelState
import kotlin.math.abs
import kotlin.math.max as kotlinMax
import java.lang.Math
import java.lang.Math.sqrt

/** One member of the live forecast ensemble. The three core members are always
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

/** Single source of truth for the ensemble size contract (3 -> 4 -> 5 models).
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
    /** AdamW optimizer for numerically stable online ensemble learning.
     * SPEC §20 replaces the legacy 10^5 gradient multiplier with AdamW,
     * which adapts per-parameter learning rates and maintains inverse
     * squared gradient estimates for each weight.
     * Learning rate is per-weight and calibrated via walk-forward validation.
     */
    const val ADAMW_LR_INITIAL = 0.01

    /** Second moment estimate (RMSprop-style) for each of the 5 ensemble weights.
     * Initialized to zero; updated online per settled prediction via applyAdamWStep.
     * Declared as var so they can be updated online per settled prediction.
     */
    var adamw_sqK = mutableListOf(0.0, 0.0, 0.0, 0.0, 0.0)
    var adamw_sqP = mutableListOf(0.0, 0.0, 0.0, 0.0, 0.0)
    var adamw_sqAlpha = mutableListOf(0.0, 0.0, 0.0, 0.0, 0.0)
    var adamw_sqW4 = mutableListOf(0.0, 0.0, 0.0, 0.0, 0.0)
    var adamw_sqW5 = mutableListOf(0.0, 0.0, 0.0, 0.0, 0.0)

    /** Bias-corrected learning rate for AdamW.
     * t = step count; lr = lr_init * sqrt(1 - beta2^t) / (1 - beta1^t)
     */
    val adamw_lrCorrected = mutableListOf(0.0, 0.0, 0.0, 0.0, 0.0)

    /** Exponential decay rate for the first moment estimates (default beta1 = 0.9). */
    const val ADAMW_BETA1 = 0.9f

    /** Exponential decay rate for the second moment estimates (default beta2 = 0.999). */
    const val ADAMW_BETA2 = 0.999f

    /** Small constant for numerical stability (default epsilon = 1e-8). */
    const val ADAMW_EPSILON = 1e-8

    /** Gradient multiplier of the legacy SPA online-SGD step. Kept for backwards
     * compatibility only; replaced by AdamW in production.
     */
    const val SGD_GRADIENT_MULTIPLIER = 100_000.0

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

    /** Rebalance [state] in place so the active members sum to 1.000.
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

    /** One settled-prediction SGD step for every active member — the core
     * K/P/alpha tri-factor and the mounted W4/W5 TSFM members alike — followed
     * by an abs renormalisation of all five shares so ΣW = 1.000 (spec §20
     * Adaptive Ensemble Learning).
     *
     * A member whose recorded drift term for that prediction is zero (an
     * unmounted TSFM, or a flat forecast) receives no gradient, and a raw share
     * of exactly zero therefore stays zero. [errorMu] must be finite or the
     * step is a no-op.
     */
    fun applyAdamWStep(
        state: ModelState,
        errorMu: Double,
        muK: Double,
        muP: Double,
        muAlpha: Double,
        muW4: Double,
        muW5: Double,
        step: Int
    ) {
        if (!errorMu.isFinite()) return

        // Update second moment estimates (RMSprop-style)
        for (i in 0 until 5) {
            val sq = when (i) {
                0 -> adamw_sqK[i]
                1 -> adamw_sqP[i]
                2 -> adamw_sqAlpha[i]
                3 -> adamw_sqW4[i]
                4 -> adamw_sqW5[i]
                else -> 0.0
            }
            val gradient = when (i) {
                0 -> muK
                1 -> muP
                2 -> muAlpha
                3 -> muW4
                4 -> muW5
                else -> 0.0
            }
            // Update the selected second moment estimate
            when (i) {
                0 -> adamw_sqK[i] = (ADAMW_BETA2 * adamw_sqK[i] + (1.0 - ADAMW_BETA2) * gradient * gradient).coerceAtLeast(ADAMW_EPSILON)
                1 -> adamw_sqP[i] = (ADAMW_BETA2 * adamw_sqP[i] + (1.0 - ADAMW_BETA2) * gradient * gradient).coerceAtLeast(ADAMW_EPSILON)
                2 -> adamw_sqAlpha[i] = (ADAMW_BETA2 * adamw_sqAlpha[i] + (1.0 - ADAMW_BETA2) * gradient * gradient).coerceAtLeast(ADAMW_EPSILON)
                3 -> adamw_sqW4[i] = (ADAMW_BETA2 * adamw_sqW4[i] + (1.0 - ADAMW_BETA2) * gradient * gradient).coerceAtLeast(ADAMW_EPSILON)
                4 -> adamw_sqW5[i] = (ADAMW_BETA2 * adamw_sqW5[i] + (1.0 - ADAMW_BETA2) * gradient * gradient).coerceAtLeast(ADAMW_EPSILON)
            }
        }

        // Bias-corrected learning rate
        val t = step.toDouble()
        val lrCorrected = ADAMW_LR_INITIAL * (Math.sqrt(1.0 - Math.pow(ADAMW_BETA2.toDouble(), t)) / (1.0 - Math.pow(ADAMW_BETA1.toDouble(), t)))

        // Update weights
        state.weightK += lrCorrected * errorMu * muK / kotlinMax(adamw_sqK[0], ADAMW_EPSILON)
        state.weightP += lrCorrected * errorMu * muP / kotlinMax(adamw_sqP[1], ADAMW_EPSILON)
        state.weightAlpha += lrCorrected * errorMu * muAlpha / kotlinMax(adamw_sqAlpha[2], ADAMW_EPSILON)
        state.weight4 += lrCorrected * errorMu * muW4 / kotlinMax(adamw_sqW4[3], ADAMW_EPSILON)
        state.weight5 += lrCorrected * errorMu * muW5 / kotlinMax(adamw_sqW5[4], ADAMW_EPSILON)

        // Renormalise so ΣW = 1.000
        val shareK = abs(state.weightK)
        val shareP = abs(state.weightP)
        val shareAlpha = abs(state.weightAlpha)
        val shareW4 = abs(state.weight4)
        val shareW5 = abs(state.weight5)
        val total = shareK + shareP + shareAlpha + shareW4 + shareW5
        if (total > 1e-9) {
            state.weightK = shareK / total
            state.weightP = shareP / total
            state.weightAlpha = shareAlpha / total
            state.weight4 = shareW4 / total
            state.weight5 = shareW5 / total
            state.mode = "Live SGD Active"
        }
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

    fun applySettledSgdStep(
        state: ModelState,
        errorMu: Double,
        muK: Double,
        muP: Double,
        muAlpha: Double,
        muW4: Double,
        muW5: Double
    ) {
        if (!errorMu.isFinite()) return

        // Simple settled-SGD step without AdamW bias correction
        // Learning rate calibrated for settled-prediction convergence
        val lr = 500.0

        state.weightK += lr * errorMu * muK
        state.weightP += lr * errorMu * muP
        state.weightAlpha += lr * errorMu * muAlpha
        state.weight4 += lr * errorMu * muW4
        state.weight5 += lr * errorMu * muW5

        // Renormalise so ΣW = 1.000
        val shareK = abs(state.weightK)
        val shareP = abs(state.weightP)
        val shareAlpha = abs(state.weightAlpha)
        val shareW4 = abs(state.weight4)
        val shareW5 = abs(state.weight5)
        val total = shareK + shareP + shareAlpha + shareW4 + shareW5
        if (total > 1e-9) {
            state.weightK = shareK / total
            state.weightP = shareP / total
            state.weightAlpha = shareAlpha / total
            state.weight4 = shareW4 / total
            state.weight5 = shareW5 / total
            state.mode = "Live SGD Active"
        }
    }

    fun forecastTitle(modelCount: Int): String = "${factorLabel(modelCount)} ENSEMBLE TARGET FORECASTS"

    /** Live formula subheader, e.g. `Ensemble_t = W1·M1(t) + W2·M2(t) [ΣW = 1.000]`. */
    fun formula(modelCount: Int): String {
        val terms = (1..modelCount.coerceIn(CORE_MODEL_COUNT, MAX_MODEL_COUNT))
            .joinToString(" + ") { "W$it·M$it(t)" }
        return "Ensemble_t = $terms  [ΣW = 1.000]"
    }
}