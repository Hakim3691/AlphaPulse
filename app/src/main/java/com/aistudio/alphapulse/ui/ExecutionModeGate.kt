package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.data.model.ExecutionMode

/**
 * C-63 — a mode switch either happens or refuses with the reason on screen.
 *
 * Only Paper Mode appeared to work. Selecting Shadow or Scaled Live changed the pill and
 * nothing else: the live-mode refusal was recorded in the view model but never rendered,
 * and Scaled stays held by §38 without saying so. The rule and its wording now live in
 * one pure function the view model and the workspace both read, so the dialog the
 * operator sees is the gate that actually ran.
 *
 * Spec Ref: §36, §38, C-63
 */
data class ModeGateDecision(
    val mode: ExecutionMode,
    val allowed: Boolean,
    val refusal: String? = null
)

object ExecutionModeGate {

    /**
     * @param hasActiveSession whether an unlocked in-memory API session exists
     * @param preLaunchUnlocked whether the §38 pre-launch acceptance gate has passed
     */
    fun evaluate(
        mode: ExecutionMode,
        hasActiveSession: Boolean,
        preLaunchUnlocked: Boolean
    ): ModeGateDecision = when (mode) {
        // Planning and simulation route nothing, so they need no venue credentials.
        ExecutionMode.SHADOW, ExecutionMode.PAPER -> ModeGateDecision(mode, allowed = true)
        ExecutionMode.CAPPED_LIVE -> if (hasActiveSession) {
            ModeGateDecision(mode, allowed = true)
        } else {
            ModeGateDecision(
                mode,
                allowed = false,
                refusal = "Capped Live needs an active API session. Open Guardrails and enter your key."
            )
        }
        ExecutionMode.SCALED_LIVE -> when {
            !hasActiveSession -> ModeGateDecision(
                mode,
                allowed = false,
                refusal = "Scaled Live needs an active API session. Open Guardrails and enter your key."
            )
            !preLaunchUnlocked -> ModeGateDecision(
                mode,
                allowed = false,
                refusal = "Scaled Live is locked until the §38 pre-launch acceptance gate passes. See Guardrails."
            )
            else -> ModeGateDecision(mode, allowed = true)
        }
    }

    /** What the active mode actually does, shown under the header on every tab. */
    fun banner(mode: ExecutionMode): String = when (mode) {
        ExecutionMode.SHADOW ->
            "SHADOW — plan only: boards and alerts are generated, no orders are routed"
        ExecutionMode.PAPER ->
            "PAPER — simulated fills against the live book, no exchange orders"
        ExecutionMode.CAPPED_LIVE ->
            "CAPPED LIVE — real routing, each board clipped to the capped notional"
        ExecutionMode.SCALED_LIVE ->
            "SCALED LIVE — full-size routing after the §38 pre-launch gate"
    }
}
