package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.local.ThresholdStore
import com.aistudio.alphapulse.domain.campaign.ThresholdConfig
import com.aistudio.alphapulse.domain.campaign.ThresholdField
import com.aistudio.alphapulse.domain.campaign.Thresholds

/**
 * C-60 — the only path that may change the configured thresholds.
 *
 * Why this exists
 * ---------------
 * A threshold change moves every gate at once, so it is a decision like any other in this
 * app: it is validated, it is persisted, it becomes the live configuration, and it is
 * written to the same `decision_records` table the trade-decisions ledger reads — with the
 * coin the operator was inspecting, the field, the old value and the new one. A change the
 * gate refuses writes nothing at all, so the ledger never claims a limit that is not in
 * force.
 *
 * Spec Ref: Phase 2 C-60, C-29, §21, §34
 */
object ThresholdAudit {

    /** One threshold change reads as its own kind of row, never as a trade verdict. */
    const val STATE: String = "CONFIG_UPDATE"
    const val SOURCE: String = "CONFIG"
    const val HORIZON: String = "CONFIG"

    /** The row a change writes: scope, field, both values and when it happened. */
    fun record(
        id: String,
        symbol: String,
        scope: String,
        field: ThresholdField,
        oldValue: String,
        newValue: String,
        timestamp: Long
    ): DecisionRecordEntity = DecisionRecordEntity(
        id = id,
        symbol = symbol,
        issuedTimestamp = timestamp,
        state = STATE,
        confidence = 0.0,
        source = SOURCE,
        referenceHorizon = HORIZON,
        reason = "scope=$scope · ${field.name} $oldValue → $newValue"
    )
}

/** The result of an attempted change: what is in force now, and why not when rejected. */
data class ThresholdOutcome(
    val accepted: Boolean,
    val config: ThresholdConfig,
    val rejection: String? = null
)

class ThresholdConfigService(
    private val store: ThresholdStore,
    private val writeLedger: suspend (DecisionRecordEntity) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() }
) {

    fun current(): ThresholdConfig = Thresholds.current()

    /**
     * Validates, persists, installs and records [candidate] — in that order, so a refused
     * candidate touches neither the store nor the ledger.
     */
    suspend fun apply(
        candidate: ThresholdConfig,
        field: ThresholdField,
        scope: String,
        symbol: String
    ): ThresholdOutcome {
        val verdict = candidate.validate()
        if (!verdict.accepted) {
            return ThresholdOutcome(
                accepted = false,
                config = Thresholds.current(),
                rejection = verdict.rejection
            )
        }

        val previous = Thresholds.current()
        store.save(candidate)
        Thresholds.install(candidate)
        writeLedger(
            ThresholdAudit.record(
                id = newId(),
                symbol = symbol,
                scope = scope,
                field = field,
                oldValue = previous.describeField(field),
                newValue = candidate.describeField(field),
                timestamp = now()
            )
        )
        return ThresholdOutcome(accepted = true, config = candidate)
    }

    /** Returns the configuration to exactly 85.0% floor and 20 settled samples. */
    suspend fun reset(scope: String, symbol: String): ThresholdOutcome =
        apply(ThresholdConfig.DEFAULT, ThresholdField.RESET, scope, symbol)
}
