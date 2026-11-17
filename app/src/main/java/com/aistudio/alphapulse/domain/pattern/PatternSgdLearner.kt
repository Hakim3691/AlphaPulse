package com.aistudio.alphapulse.domain.pattern

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Online multi-class SGD over categorical cross-entropy — the learning half of the
 * pattern ledger.
 *
 * Why this exists
 * ---------------
 * "The model learns from the miss" is easy to claim and impossible to audit. The ledger
 * therefore needs a loss it can print and a weight update it can attribute: at settlement
 * the realized formation is one-hot encoded, the model's probability distribution is read,
 * cross-entropy is computed, and one gradient step is taken. [PatternSgdStep.action] is the
 * exact sentence the ledger stores, so a row that says "+w_reinforce" was produced by a
 * genuinely improving prediction and not by a branch that decided to look confident.
 *
 * The model is a softmax regression: `z_c = W[c] · x`, `ŷ = softmax(z)`, and
 * `L = -Σ y_c log ŷ_c`. Its gradient is `(ŷ_c - y_c) · x`, so the update below is the
 * directive's `Wpattern ← Wpattern − η · ∇W LCE`.
 *
 * Spec Ref: Directive §4 Mathematical Optimization & Gradient Updates
 */

/** The outcome of one settlement step, and the sentence the ledger records. */
data class PatternSgdStep(
    val realizedId: String,
    val predictedId: String,
    /** Categorical cross-entropy of the realized class, in nats. */
    val loss: Double,
    /** True when the model's argmax already matched the realized pattern. */
    val reinforced: Boolean,
    /** L1 magnitude of the weight update, for the audit trail. */
    val weightDelta: Double
) {
    /** e.g. `SGD Step: +w_reinforce (Loss: 0.69)`. */
    val action: String
        get() = String.format(
            Locale.US,
            "SGD Step: %s (Loss: %.2f)",
            if (reinforced) "+w_reinforce" else "- w_penalty",
            loss
        )
}

/**
 * A softmax-regression head over one horizon's taxonomy classes.
 *
 * Weights are `classes.size × classes.size`: row `c` scores class `c` from the evidence
 * vector. The matrix is small (at most 10×10), so it is persisted as CSV in a single row
 * rather than as hundreds of columns.
 */
class PatternSgdModel(
    val classes: List<String>,
    val learningRate: Double = DEFAULT_LEARNING_RATE,
    private val weights: Array<DoubleArray> = Array(classes.size) { DoubleArray(classes.size) }
) {
    init {
        require(classes.isNotEmpty()) { "A pattern model needs at least one class" }
        require(weights.size == classes.size) { "Weight rows must match the class count" }
        require(weights.all { it.size == classes.size }) { "Weight columns must match the class count" }
        require(learningRate > 0.0) { "The learning rate must be positive" }
    }

    /** Number of gradient steps taken, for the ledger's audit trail. */
    var steps: Int = 0
        private set

    /** Loss of the most recent step. */
    var lastLoss: Double = 0.0
        private set

    /** Softmax probabilities for one evidence vector. */
    fun probabilities(features: DoubleArray): DoubleArray {
        require(features.size == classes.size) { "Features must align with the class list" }
        val logits = DoubleArray(classes.size) { row ->
            var total = 0.0
            for (column in features.indices) total += weights[row][column] * features[column]
            total
        }
        val maxLogit = logits.max()
        val exponents = DoubleArray(logits.size) { exp(logits[it] - maxLogit) }
        val normaliser = exponents.sum()
        return DoubleArray(logits.size) { exponents[it] / normaliser }
    }

    /** Categorical cross-entropy of [realizedId] under the current weights. */
    fun loss(features: DoubleArray, realizedId: String): Double {
        val position = classes.indexOf(realizedId)
        require(position >= 0) { "Unknown realized pattern '$realizedId'" }
        return -ln(probabilities(features)[position].coerceAtLeast(PROBABILITY_FLOOR))
    }

    /**
     * One online SGD step against the one-hot [realizedId].
     *
     * The returned loss is the loss *before* the update — the number the ledger prints for
     * the settled prediction — while the weights carried forward are already updated.
     */
    fun step(features: DoubleArray, realizedId: String): PatternSgdStep {
        val realizedIndex = classes.indexOf(realizedId)
        require(realizedIndex >= 0) { "Unknown realized pattern '$realizedId'" }

        val probabilities = probabilities(features)
        val loss = -ln(probabilities[realizedIndex].coerceAtLeast(PROBABILITY_FLOOR))
        var predictedIndex = 0
        for (index in probabilities.indices) {
            if (probabilities[index] > probabilities[predictedIndex]) predictedIndex = index
        }

        var delta = 0.0
        for (row in classes.indices) {
            val error = probabilities[row] - if (row == realizedIndex) 1.0 else 0.0
            for (column in features.indices) {
                val update = learningRate * error * features[column]
                weights[row][column] -= update
                delta += abs(update)
            }
        }

        steps += 1
        lastLoss = loss
        return PatternSgdStep(
            realizedId = realizedId,
            predictedId = classes[predictedIndex],
            loss = loss,
            reinforced = predictedIndex == realizedIndex,
            weightDelta = delta
        )
    }

    /** Round-trip form for the single-row persistence of this head. */
    fun toCsv(): String = weights.joinToString(ROW_SEPARATOR) { row ->
        row.joinToString(VALUE_SEPARATOR) { it.toString() }
    }

    companion object {
        const val DEFAULT_LEARNING_RATE = 0.08
        private const val PROBABILITY_FLOOR = 1e-12
        private const val ROW_SEPARATOR = ";"
        private const val VALUE_SEPARATOR = ","

        /**
         * Restore a head from [csv], falling back to a neutral head when it is empty or
         * the wrong shape — a corrupt weights row must not stop predictions, it must only
         * mean this horizon relearns.
         */
        fun fromCsv(
            classes: List<String>,
            learningRate: Double,
            csv: String
        ): PatternSgdModel {
            val neutral = PatternSgdModel(classes, learningRate)
            if (csv.isBlank()) return neutral
            val rows = csv.split(ROW_SEPARATOR).map { row ->
                row.split(VALUE_SEPARATOR).mapNotNull { it.toDoubleOrNull() }
            }
            if (rows.size != classes.size || rows.any { it.size != classes.size }) return neutral
            return PatternSgdModel(classes, learningRate, Array(classes.size) { rows[it].toDoubleArray() })
        }
    }
}
