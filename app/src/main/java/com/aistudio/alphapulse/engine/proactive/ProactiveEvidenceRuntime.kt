package com.aistudio.alphapulse.engine.proactive

/**
 * One paper-trial tracker per app process.
 *
 * The gate lives in [com.aistudio.alphapulse.ui.MainViewModel], while the base-view
 * frame observations are produced by `MainActivity`. If each created its own tracker,
 * the frame producer would write to a different in-memory streak than the one the
 * staging gate reads. This runtime makes both callers share one tracker, which in turn
 * shares one persisted store, so evidence accumulated across sessions reaches the
 * exact verdict handed to `ExecutionEngine.gateStaging`.
 *
 * Spec Ref: Phase 2 §38
 */
object ProactiveEvidenceRuntime {

    @Volatile
    private var instance: ProactiveCycleRunner? = null

    /**
     * Returns the process tracker, creating it with [store] on first use. Whichever
     * caller initialises first supplies the durable store; later callers receive the
     * same instance.
     */
    fun runner(store: PaperTrialEvidenceStore? = null): ProactiveCycleRunner =
        instance ?: synchronized(this) {
            instance ?: ProactiveCycleRunner(
                paperTrials = PaperTrialEvidenceTracker(store = store)
            ).also { instance = it }
        }

    /** Test seam: drops the process instance so a relaunch can be simulated. */
    fun reset() {
        synchronized(this) { instance = null }
    }
}
