package com.aistudio.alphapulse.engine.proactive

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.engine.ExecutionEngine
import com.aistudio.alphapulse.engine.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §38 / Contract C-49 — durable, fail-closed paper-trial evidence.
 *
 * Simulates a process restart by persisting to a store, discarding the in-memory
 * tracker (and, for the runtime scenario, the process singleton) and reloading.
 *
 * Spec Ref: Phase 2 §38
 */
class PaperTrialEvidencePersistenceBddSpec {

    private class MemoryStore(var payload: String? = null) : PaperTrialEvidenceStore {
        override fun read(): String? = payload
        override fun write(payload: String) {
            this.payload = payload
        }

        override fun clear() {
            payload = null
        }
    }

    private fun campaign() = Campaign(
        id = "SOLUSDT_SUPPORT_LONG",
        asset = "SOLUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 80.0,
        sizeBudgetUsd = 2_000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(LadderSlice(0, 100.0, 1.0, 500.0, OrderType.PASSIVE_LIMIT)),
        stopLogic = StopLogic(hardStopPrice = 90.0),
        targets = emptyList(),
        invalidationScore = 5,
        topReasons = emptyList(),
        status = CampaignState.STAGED
    )

    @Test
    fun `given one session of paper evidence when the tracker is discarded and reloaded then the streak latency and intercepts carry across`() {
        val store = MemoryStore()

        val first = PaperTrialEvidenceTracker(store = store)
        repeat(60) { first.recordCompletedPaperTrade() }
        repeat(60) { first.recordCancellationLatency(50L) }
        repeat(60) { first.recordMinimizeEvent(warnedAndWiped = true) }
        first.recordBaseViewObservation(regressionDetected = false)

        // Process restart: the in-memory tracker is gone; only the store survives.
        val reloaded = PaperTrialEvidenceTracker(store = store)
        val carried = reloaded.evidence()

        assertEquals(60, carried.consecutivePaperTrades)
        assertEquals(60, carried.cancellationSamples)
        assertEquals(50L, carried.cancellationLatencyMsP95)
        assertEquals(60, carried.minimizeEventsTested)
        assertEquals(60, carried.minimizeEventsWarnedAndWiped)
        assertTrue(carried.baseViewObserved)
        assertFalse("60 trades is not yet the 100-trade gate", reloaded.verdict().unlocked)

        // The campaign continues from where it left off, not from zero.
        repeat(40) { reloaded.recordCompletedPaperTrade() }
        val afterThirdLaunch = PaperTrialEvidenceTracker(store = store)

        assertEquals(100, afterThirdLaunch.evidence().consecutivePaperTrades)
        assertTrue(afterThirdLaunch.verdict().unlocked)
    }

    @Test
    fun `given a process relaunch through the shared runtime when reloaded then its verdict is the one the staging gate receives`() {
        ProactiveEvidenceRuntime.reset()
        val store = MemoryStore()
        try {
            val first = ProactiveEvidenceRuntime.runner(store)
            repeat(100) { first.recordCompletedPaperTrade() }
            repeat(100) { first.recordCancellationLatency(40L) }
            repeat(100) { first.recordMinimizeEvent(warnedAndWiped = true) }
            first.recordBaseViewObservation(regressionDetected = false)

            ProactiveEvidenceRuntime.reset()
            val relaunched = ProactiveEvidenceRuntime.runner(store)
            val verdict = relaunched.preLaunchVerdict()
            assertTrue(verdict.unlocked)

            val gated = ExecutionEngine(RiskEngine()).gateStaging(
                listOf(campaign()),
                ExecutionMode.SCALED_LIVE,
                RiskEnvelope(),
                VenueHealth(),
                hasActiveSession = true,
                preLaunchAcceptance = verdict
            )

            assertEquals(CampaignState.STAGED, gated.single().campaign.status)
        } finally {
            ProactiveEvidenceRuntime.reset()
        }
    }

    @Test
    fun `given corrupt persisted state when the tracker loads then it stays unmet instead of unlocking scaled live`() {
        val store = MemoryStore(payload = "not-a-valid-evidence-payload")

        val tracker = PaperTrialEvidenceTracker(store = store)

        assertEquals(0, tracker.evidence().consecutivePaperTrades)
        assertFalse(tracker.verdict().unlocked)
        assertTrue(tracker.verdict().failures.isNotEmpty())
        assertNull("Corrupt state is discarded, not kept for the next load", store.payload)
    }

    @Test
    fun `given a truncated or tampered payload when the tracker loads then the checksum rejects it and the gate stays locked`() {
        val valid = PaperTrialEvidenceCodec.encode(
            PaperTrialEvidenceCodec.Snapshot(
                consecutiveCompletedTrades = 100,
                exceptionsInCurrentStreak = 0,
                latenciesMs = List(100) { 40L },
                minimizeEventsTested = 100,
                minimizeEventsWarnedAndWiped = 100,
                baseViewObserved = true,
                baseViewRegressionDetected = false
            )
        )

        val truncated = MemoryStore(valid.dropLast(6))
        assertFalse(PaperTrialEvidenceTracker(store = truncated).verdict().unlocked)

        val tampered = MemoryStore(valid.replace("trades=100", "trades=999"))
        assertFalse(PaperTrialEvidenceTracker(store = tampered).verdict().unlocked)
    }

    @Test
    fun `given a stale format header when the tracker loads then it is rejected rather than misread`() {
        val valid = PaperTrialEvidenceCodec.encode(
            PaperTrialEvidenceCodec.Snapshot(
                consecutiveCompletedTrades = 100,
                exceptionsInCurrentStreak = 0,
                latenciesMs = List(100) { 40L },
                minimizeEventsTested = 100,
                minimizeEventsWarnedAndWiped = 100,
                baseViewObserved = true,
                baseViewRegressionDetected = false
            )
        )
        val stale = valid.replace(PaperTrialEvidenceCodec.HEADER, "PAPER_TRIAL_EVIDENCE_V0")

        val tracker = PaperTrialEvidenceTracker(store = MemoryStore(stale))

        assertEquals(0, tracker.evidence().consecutivePaperTrades)
        assertFalse(tracker.verdict().unlocked)
    }

    @Test
    fun `given a store that throws on read and write when the tracker records evidence then it keeps working in memory`() {
        val broken = object : PaperTrialEvidenceStore {
            override fun read(): String? = throw IllegalStateException("disk unavailable")
            override fun write(payload: String) = throw IllegalStateException("disk full")
            override fun clear() = throw IllegalStateException("disk unavailable")
        }

        val tracker = PaperTrialEvidenceTracker(store = broken)
        repeat(100) { tracker.recordCompletedPaperTrade() }
        repeat(100) { tracker.recordCancellationLatency(30L) }
        repeat(100) { tracker.recordMinimizeEvent(warnedAndWiped = true) }
        tracker.recordBaseViewObservation(regressionDetected = false)

        assertTrue("Persistence failure must not break the campaign", tracker.verdict().unlocked)
    }
}
