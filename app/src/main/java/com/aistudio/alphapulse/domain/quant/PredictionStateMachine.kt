package com.aistudio.alphapulse.domain.quant

/**
 * PredictionStateMachine - the legal life of a prediction, written down.
 *
 * A forecast is not just "open or closed". It is issued, then either settles when
 * its horizon ends, expires if it never gets a price to compare against, or is
 * voided when it turns out the inputs were wrong. Learning may only happen after
 * settlement, because you cannot learn from a forecast whose outcome is unknown.
 *
 * Before this existed those rules lived implicitly in booleans on the database
 * row, so a bug could mark a prediction "learned" without it ever settling and
 * nothing would complain. Now every move is checked against one table, and an
 * illegal move is rejected with a reason instead of silently corrupting the
 * ledger.
 *
 * Spec Ref: §34 Prediction State Machine, §21 Prediction Audit Ledger
 */

/** Where a prediction is in its life. */
enum class PredictionStatus {
    /** Published and waiting for its horizon to end. */
    ISSUED,
    /** Horizon ended and the realised price is known. */
    SETTLED,
    /** The ensemble has been updated from this outcome. */
    LEARNED,
    /** The horizon passed with no usable price, so nothing can be learned. */
    EXPIRED,
    /** Inputs or bookkeeping were wrong; the row is kept but ignored. */
    VOID
}

/** Something that has happened to a prediction. */
enum class PredictionEvent {
    /** The horizon ended and a realised price was recorded. */
    SETTLE,
    /** The ensemble took its optimiser step from the settled outcome. */
    LEARN,
    /** The horizon passed with no price; nothing to compare against. */
    EXPIRE,
    /** The row was found to be unusable. */
    VOID
}

/** The result of asking the machine to move. */
sealed class TransitionResult {
    /**
     * The move is legal.
     * @property from state before the move
     * @property to state after the move
     */
    data class Allowed(val from: PredictionStatus, val to: PredictionStatus) : TransitionResult()

    /**
     * The move is not legal and must not be applied.
     * @property from state the prediction is in
     * @property event the event that was rejected
     * @property reason plain-English explanation for the log
     */
    data class Rejected(
        val from: PredictionStatus,
        val event: PredictionEvent,
        val reason: String
    ) : TransitionResult()
}

object PredictionStateMachine {

    /**
     * The whole state machine in one table: for each state, the events it accepts
     * and the state each event leads to.
     *
     * Reading it out loud: an issued prediction can settle, expire or be voided; a
     * settled one can be learned from or voided; a learned or expired one can only
     * be voided; and a void prediction is final.
     */
    val transitions: Map<PredictionStatus, Map<PredictionEvent, PredictionStatus>> = mapOf(
        PredictionStatus.ISSUED to mapOf(
            PredictionEvent.SETTLE to PredictionStatus.SETTLED,
            PredictionEvent.EXPIRE to PredictionStatus.EXPIRED,
            PredictionEvent.VOID to PredictionStatus.VOID
        ),
        PredictionStatus.SETTLED to mapOf(
            PredictionEvent.LEARN to PredictionStatus.LEARNED,
            PredictionEvent.VOID to PredictionStatus.VOID
        ),
        PredictionStatus.LEARNED to mapOf(
            PredictionEvent.VOID to PredictionStatus.VOID
        ),
        PredictionStatus.EXPIRED to mapOf(
            PredictionEvent.VOID to PredictionStatus.VOID
        ),
        // VOID is terminal: once a row is void, nothing brings it back.
        PredictionStatus.VOID to emptyMap()
    )

    /**
     * Apply one event.
     *
     * @param from the prediction's current state
     * @param event what just happened
     * @return [TransitionResult.Allowed] with the new state, or
     *   [TransitionResult.Rejected] explaining why the move is impossible
     */
    fun transition(from: PredictionStatus, event: PredictionEvent): TransitionResult {
        val destination = transitions[from]?.get(event)

        if (destination == null) {
            val allowed = transitions[from].orEmpty().keys.map { it.name.lowercase() }
            val reason = if (allowed.isEmpty()) {
                "A ${from.name.lowercase()} prediction is final and accepts no further events."
            } else {
                "A ${from.name.lowercase()} prediction cannot ${event.name.lowercase()}; " +
                    "it only accepts ${allowed.joinToString(" or ")}."
            }
            return TransitionResult.Rejected(from, event, reason)
        }

        return TransitionResult.Allowed(from, destination)
    }

    /** True when the move is legal; a convenience wrapper for callers that only need a yes/no. */
    fun canTransition(from: PredictionStatus, to: PredictionStatus): Boolean =
        transitions[from]?.values?.any { it == to } == true

    /** True when no event can move the prediction any further. */
    fun isTerminal(status: PredictionStatus): Boolean =
        transitions[status].orEmpty().isEmpty()

    /**
     * Map a stored row onto a state.
     *
     * The ledger stores a single `settled` flag plus a free-text learning action,
     * so this is the bridge that keeps the database and the state machine agreeing.
     *
     * @param settled value of `PredictionEntity.settled`
     * @param learningAction value of `PredictionEntity.learningAction`
     */
    fun statusOf(settled: Boolean, learningAction: String): PredictionStatus {
        if (!settled) return PredictionStatus.ISSUED
        return when {
            learningAction.startsWith(LEARNED_PREFIX) -> PredictionStatus.LEARNED
            learningAction.equals(VOID_ACTION, ignoreCase = true) -> PredictionStatus.VOID
            learningAction.equals(EXPIRED_ACTION, ignoreCase = true) -> PredictionStatus.EXPIRED
            else -> PredictionStatus.SETTLED
        }
    }

    /**
     * The learning action recorded on the ledger for a settled prediction.
     *
     * Kept here, rather than in the repository, so the wording and the state
     * machine can never drift apart.
     *
     * @param inCone whether the realised price stayed inside the forecast cone
     */
    fun learningActionFor(inCone: Boolean): String =
        if (inCone) "Preserved: SGD Refined" else "Breach: SGD Tri-Update"

    /** Prefix used for rows the ensemble has already learned from. */
    const val LEARNED_PREFIX: String = "Learned"

    /** Marker action for a row that expired without a price. */
    const val EXPIRED_ACTION: String = "Expired: No Realised Price"

    /** Marker action for a row that was voided. */
    const val VOID_ACTION: String = "Void: Unusable Inputs"
}
