package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.CampaignState

/**
 * The boundary between the Android evaluation cycle and the §31 automation loop.
 *
 * The live cycle owns the frames (it has the asset, evidence, level map, paths and
 * execution mode), while the loop owns the decisions. This runner exists so the
 * cycle neither re-implements cadence timing nor forgets to record the §38 evidence:
 *
 *  - it times each loop pass and records a cancellation latency whenever a paper
 *    board is cancelled, which is what the pre-launch gate benchmarks;
 *  - it exposes the tracker's verdict so `ExecutionEngine.gateStaging` receives
 *    real evidence instead of a permanently-null gate;
 *  - it forwards the operator events (trade completion, cycle exception, minimize
 *    intercept, base-view regression) to the single tracker.
 *
 * Spec Ref: Phase 2 §31, §38
 */
class ProactiveCycleRunner(
    private val loop: ProactiveExecutionLoop = ProactiveExecutionLoop(),
    private val paperTrials: PaperTrialEvidenceTracker = PaperTrialEvidenceTracker(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Runs one automation pass. [paperMode] gates evidence recording: a shadow or
     * live pass must not fabricate paper-trade statistics.
     */
    fun run(frame: ProactiveLoopFrame, paperMode: Boolean): ProactiveLoopResult {
        val startedAt = clock()
        val previousStatus = frame.campaigns.associate { it.id to it.status }
        val result = loop.step(frame)

        if (paperMode) {
            val cancelled = result.campaigns.any { campaign ->
                campaign.status == CampaignState.CANCELLED &&
                    previousStatus[campaign.id] != CampaignState.CANCELLED
            }
            if (cancelled) {
                paperTrials.recordCancellationLatency(
                    (clock() - startedAt).coerceAtLeast(0L)
                )
            }
        }
        return result
    }

    fun recordCompletedPaperTrade() = paperTrials.recordCompletedPaperTrade()

    /** Records a cancellation latency measured outside [run] (operator flatten path). */
    fun recordCancellationLatency(latencyMs: Long) =
        paperTrials.recordCancellationLatency(latencyMs)

    fun recordUnhandledException() = paperTrials.recordUnhandledException()

    fun recordMinimizeEvent(warnedAndWiped: Boolean) =
        paperTrials.recordMinimizeEvent(warnedAndWiped)

    fun recordBaseViewObservation(regressionDetected: Boolean) =
        paperTrials.recordBaseViewObservation(regressionDetected)

    fun evidence(): PreLaunchEvidence = paperTrials.evidence()

    /** The verdict `ExecutionEngine.gateStaging` receives for Scaled Live. */
    fun preLaunchVerdict(): PreLaunchVerdict = paperTrials.verdict()
}
