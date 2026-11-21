package com.aistudio.alphapulse.engine

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Phase 1 §3 — the lifecycle bridge that makes the orphan intercept reachable.
 *
 * `MainViewModel.onAppBackgrounded()` owns the whole §3 policy, but the shipped graph
 * mounted `MainViewModel` without ever forwarding a lifecycle event to it, so the rule
 * could not run. This observer is deliberately tiny: it decides nothing, it only forwards
 * "the host left the foreground" to the policy, which keeps the decision unit-testable in
 * `OrphanInterceptPolicy` and the wiring provable in
 * `OrphanInterceptReachabilityBddSpec`.
 *
 * `ON_STOP` is the correct signal: a transient `ON_PAUSE` (permission sheet, system
 * dialog) must not wipe API keys or interrupt the operator.
 *
 * Spec Ref: Phase 1 §3 Orphaned Order OS Intercept
 */
class OrphanInterceptLifecycle(
    private val onForegroundLost: () -> Unit
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        onForegroundLost()
    }
}
