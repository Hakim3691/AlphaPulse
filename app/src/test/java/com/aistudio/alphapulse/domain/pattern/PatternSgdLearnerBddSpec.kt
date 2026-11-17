package com.aistudio.alphapulse.domain.pattern

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4 — the online SGD step that the ledger's "SGD LEARNING ACTION" column reports.
 *
 * The directive specifies categorical cross-entropy and one gradient step per settlement.
 * These scenarios pin the maths (uniform loss = ln C, probabilities sum to one), the sign
 * of the update (a hit reinforces, a miss penalises) and the exact wording the ledger
 * stores, so a row cannot claim a step that did not happen.
 *
 * Spec Ref: Directive §4 Mathematical Optimization & Gradient Updates
 */
class PatternSgdLearnerBddSpec {

    private val classes = listOf(
        "bullish-engulfing", "bearish-engulfing", "piercing-line", "dark-cloud-cover"
    )

    private fun oneHot(id: String): DoubleArray {
        val vector = DoubleArray(classes.size)
        vector[classes.indexOf(id)] = 1.0
        return vector
    }

    @Test
    fun `given a neutral model when a distribution is read then it is uniform and the loss is the log of the class count`() {
        val model = PatternSgdModel(classes)
        val features = oneHot("bullish-engulfing")

        val probabilities = model.probabilities(features)

        assertEquals(1.0, probabilities.sum(), 1e-12)
        probabilities.forEach { assertEquals(0.25, it, 1e-12) }
        assertEquals(
            "Cross-entropy of an uninformative model over C classes is ln C",
            ln(4.0),
            model.loss(features, "bullish-engulfing"),
            1e-12
        )
    }

    @Test
    fun `given a hit when the model steps then the realized affinity is reinforced and the loss falls`() {
        val model = PatternSgdModel(classes)
        val features = oneHot("bullish-engulfing")

        val before = model.loss(features, "bullish-engulfing")
        val step = model.step(features, "bullish-engulfing")
        val after = model.loss(features, "bullish-engulfing")

        assertEquals(before, step.loss, 1e-12)
        assertTrue("A single step must reduce the loss: $before -> $after", after < before)
        assertEquals(
            "The ledger must record the reinforcement wording",
            "SGD Step: +w_reinforce (Loss: ${String.format(java.util.Locale.US, "%.2f", before)})",
            step.action
        )
        assertTrue(step.reinforced)
        assertTrue("A step must move weights", step.weightDelta > 0.0)
        assertEquals(1, model.steps)
    }

    @Test
    fun `given a miss when the model steps then the predicted class is penalised and the realized class is promoted`() {
        val model = PatternSgdModel(classes)
        val predictedFeatures = oneHot("piercing-line")

        val probabilityBefore = model.probabilities(predictedFeatures)

        val step = model.step(predictedFeatures, "dark-cloud-cover")
        val probabilityAfter = model.probabilities(predictedFeatures)

        assertFalse("A miss is not a reinforcement", step.reinforced)
        assertTrue(
            "The miss must lower the probability of the class that was called: $step",
            probabilityAfter[classes.indexOf("piercing-line")] <
                probabilityBefore[classes.indexOf("piercing-line")]
        )
        assertTrue(
            "The realized class must gain probability at the expense of the call",
            probabilityAfter[classes.indexOf("dark-cloud-cover")] >
                probabilityBefore[classes.indexOf("dark-cloud-cover")]
        )
        assertTrue(
            "The ledger must record the penalty wording: ${step.action}",
            step.action.startsWith("SGD Step: - w_penalty (Loss: ")
        )
    }

    @Test
    fun `given a persisted model when it is restored then every weight round-trips unchanged`() {
        val model = PatternSgdModel(classes, learningRate = 0.05)
        val features = oneHot("bearish-engulfing")
        repeat(5) { model.step(features, "bearish-engulfing") }
        repeat(3) { model.step(oneHot("piercing-line"), "bullish-engulfing") }

        val restored = PatternSgdModel.fromCsv(classes, 0.05, model.toCsv())

        val expected = model.probabilities(features)
        val actual = restored.probabilities(features)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 1e-12) }
    }

    @Test
    fun `given a corrupt persisted model when it is restored then it falls back to a neutral head instead of throwing`() {
        val restored = PatternSgdModel.fromCsv(classes, 0.08, "not,a,weight,matrix")

        assertEquals(
            "A corrupt row must mean relearning, not a crash at settlement",
            0.25,
            restored.probabilities(oneHot("bullish-engulfing")).first(),
            1e-12
        )
    }
}
