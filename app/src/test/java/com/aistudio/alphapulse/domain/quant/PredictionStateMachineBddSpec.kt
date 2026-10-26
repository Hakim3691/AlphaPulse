package com.aistudio.alphapulse.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the prediction lifecycle, specified as Given/When/Then.
 *
 * §34 asks for an explicit state machine. The point of these tests is the illegal
 * transitions: learning from an unsettled forecast, or reviving a void row, must
 * be impossible rather than merely unlikely.
 *
 * Spec Ref: §34 Prediction State Machine
 */
class PredictionStateMachineBddSpec {

    // ------------------------------------------------------- the happy path

    @Test
    fun `given a freshly issued prediction when its horizon ends then it settles`() {
        val result = PredictionStateMachine.transition(
            PredictionStatus.ISSUED, PredictionEvent.SETTLE
        )

        assertEquals(
            TransitionResult.Allowed(PredictionStatus.ISSUED, PredictionStatus.SETTLED),
            result
        )
    }

    @Test
    fun `given a settled prediction when the ensemble learns from it then it becomes learned`() {
        val result = PredictionStateMachine.transition(
            PredictionStatus.SETTLED, PredictionEvent.LEARN
        )

        assertEquals(
            TransitionResult.Allowed(PredictionStatus.SETTLED, PredictionStatus.LEARNED),
            result
        )
    }

    @Test
    fun `given a prediction whose horizon passed with no price when it expires then it becomes expired`() {
        val result = PredictionStateMachine.transition(
            PredictionStatus.ISSUED, PredictionEvent.EXPIRE
        )

        assertEquals(
            TransitionResult.Allowed(PredictionStatus.ISSUED, PredictionStatus.EXPIRED),
            result
        )
    }

    // ------------------------------------------------- illegal transitions

    @Test
    fun `given an unsettled prediction when asked to learn then the move is rejected with a reason`() {
        val result = PredictionStateMachine.transition(
            PredictionStatus.ISSUED, PredictionEvent.LEARN
        )

        assertTrue("Learning before settling would corrupt the ledger", result is TransitionResult.Rejected)
        result as TransitionResult.Rejected
        assertEquals(PredictionStatus.ISSUED, result.from)
        assertTrue(
            "The reason must name what is allowed instead: ${result.reason}",
            result.reason.contains("settle")
        )
    }

    @Test
    fun `given a void prediction when any event arrives then it is rejected because void is final`() {
        PredictionEvent.entries.forEach { event ->
            val result = PredictionStateMachine.transition(PredictionStatus.VOID, event)

            assertTrue("VOID must reject $event", result is TransitionResult.Rejected)
            assertTrue(
                "The reason must say the row is final",
                (result as TransitionResult.Rejected).reason.contains("final")
            )
        }
    }

    @Test
    fun `given a learned prediction when asked to learn again then the double update is rejected`() {
        val result = PredictionStateMachine.transition(
            PredictionStatus.LEARNED, PredictionEvent.LEARN
        )

        assertTrue(
            "Learning twice would apply the same outcome to the weights twice",
            result is TransitionResult.Rejected
        )
    }

    @Test
    fun `given an expanded prediction when asked to settle then it is rejected because expiry is not settlement`() {
        val result = PredictionStateMachine.transition(
            PredictionStatus.EXPIRED, PredictionEvent.SETTLE
        )

        assertTrue(result is TransitionResult.Rejected)
    }

    // ------------------------------------------------------------ the table

    @Test
    fun `given the transition table when read then every state except void has at least one legal move`() {
        PredictionStatus.entries.forEach { status ->
            val moves = PredictionStateMachine.transitions[status].orEmpty()
            if (status == PredictionStatus.VOID) {
                assertTrue("VOID is the only terminal state", moves.isEmpty())
                assertTrue(PredictionStateMachine.isTerminal(status))
            } else {
                assertTrue("$status must be able to move somewhere", moves.isNotEmpty())
                assertFalse(PredictionStateMachine.isTerminal(status))
            }
        }
    }

    @Test
    fun `given two states when asked whether a direct move is legal then the answer matches the table`() {
        assertTrue(PredictionStateMachine.canTransition(PredictionStatus.ISSUED, PredictionStatus.SETTLED))
        assertStillFalse(PredictionStatus.ISSUED, PredictionStatus.LEARNED)
        assertStillFalse(PredictionStatus.LEARNED, PredictionStatus.SETTLED)
        assertTrue(PredictionStateMachine.canTransition(PredictionStatus.SETTLED, PredictionStatus.VOID))
    }

    // ------------------------------------------------ database round-tripping

    @Test
    fun `given a stored ledger row when read back then its status matches the flags on the row`() {
        assertEquals(
            PredictionStateMachine.statusOf(settled = false, learningAction = "Pending Settlement"),
            PredictionStatus.ISSUED
        )
        assertEquals(
            PredictionStateMachine.statusOf(
                settled = true,
                learningAction = PredictionStateMachine.learningActionFor(inCone = true)
            ),
            PredictionStatus.SETTLED
        )
        assertEquals(
            PredictionStateMachine.statusOf(settled = true, learningAction = "Learned: SGD step 4"),
            PredictionStatus.LEARNED
        )
        assertEquals(
            PredictionStateMachine.statusOf(settled = true, learningAction = PredictionStateMachine.EXPIRED_ACTION),
            PredictionStatus.EXPIRED
        )
        assertEquals(
            PredictionStateMachine.statusOf(settled = true, learningAction = PredictionStateMachine.VOID_ACTION),
            PredictionStatus.VOID
        )
    }

    @Test
    fun `given a settled outcome when the learning action is written then in-cone and breach get different labels`() {
        val preserved = PredictionStateMachine.learningActionFor(inCone = true)
        val breached = PredictionStateMachine.learningActionFor(inCone = false)

        assertTrue(preserved.startsWith("Preserved"))
        assertTrue(breached.startsWith("Breach"))
        assertEquals(
            "The ledger wording must not drift from what the settlement path writes",
            "Preserved: SGD Refined", preserved
        )
        assertEquals("Breach: SGD Tri-Update", breached)
    }

    private fun assertStillFalse(from: PredictionStatus, to: PredictionStatus) {
        assertFalse(
            "$from must not jump straight to $to",
            PredictionStateMachine.canTransition(from, to)
        )
    }
}
