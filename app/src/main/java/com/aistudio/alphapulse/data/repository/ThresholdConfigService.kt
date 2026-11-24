package com.aistudio.alphapulse.data.repository

import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.data.local.ThresholdStore
import com.aistudio.alphapulse.domain.campaign.ThresholdConfig
import com.aistudio.alphapulse.domain.campaign.ThresholdField

/**
 * C-59 — the only path that may change the configured thresholds.
 *
 * Stub phase.
 *
 * Spec Ref: Phase 2 C-59, C-29, §21, §34
 */
object ThresholdAudit {

    const val STATE: String = "CONFIG_UPDATE"
    const val SOURCE: String = "CONFIG"
    const val HORIZON: String = "CONFIG"

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

    suspend fun apply(
        candidate: ThresholdConfig,
        field: ThresholdField,
        scope: String,
        symbol: String
    ): ThresholdOutcome = ThresholdOutcome(accepted = true, config = candidate)

    suspend fun reset(scope: String, symbol: String): ThresholdOutcome =
        apply(ThresholdConfig.DEFAULT, ThresholdField.RESET, scope, symbol)
}
