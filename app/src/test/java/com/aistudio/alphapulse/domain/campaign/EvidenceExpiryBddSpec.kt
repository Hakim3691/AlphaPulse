package com.aistudio.alphapulse.domain.campaign

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-61 — evidence expires: a measurement is not a fact forever.
 *
 * The gates used to treat every rate they were handed as current. A coin whose cone last
 * settled an afternoon ago was still "proven", a pattern ledger that stopped receiving rows
 * kept its 85%, and a campaign could stage on a reading nobody had refreshed. The in-force
 * window is one configured value (C-60's sibling), and every place that decides
 * qualification now asks the same pure helper.
 *
 * Given/When/Then:
 *  - given fresh price evidence, when assessed, then it is in force and proven.
 *  - given evidence older than its window, when assessed, then staging is withheld and the
 *    reason names the layer, the age and the window.
 *  - given an hourly cone, when read at its doubled window, then the per-horizon nuance
 *    keeps it in force one minute longer than the pooled window allows.
 *  - given a wider window, when the same rows are re-read, then they return to force
 *    without a second edit anywhere.
 *  - given an unstamped record, when assessed, then it fails closed.
 *  - given stale PATTERN / STRATEGY evidence, when assessed, then the layer is inactive and
 *    is named stale — additive layers never block, but they never activate either.
 *  - given a stale governing cone, when the campaign readiness gate evaluates, then arming
 *    is refused by name.
 *
 * Spec Ref: Phase 2 C-57/C-58/C-60/C-61, §22, §67, §68
 */
class EvidenceExpiryBddSpec {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @After
    fun restoreShippedDefaults() = Thresholds.install(ThresholdConfig.DEFAULT)

    private fun at(minutesAgo: Long): Long = now - minutesAgo * minute

    private fun readout(layer: EvidenceLayer, verdict: LayeredEvidenceVerdict) =
        ThresholdPresentation.rows(ThresholdConfig.DEFAULT, verdict).first { it.layer == layer }

    @Test
    fun `given price evidence measured a minute ago when assessed then it is in force and the hour is proven`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1h", settled = 4, hits = 4, measuredAt = at(1))),
            nowMs = now
        )

        val row = verdict.price.single()
        assertTrue(verdict.canStage)
        assertNull(row.staleReason)
        assertEquals(1L, row.ageMinutes)
        assertEquals("An hourly reading gets twice its horizon", 120, row.windowMinutes)
    }

    @Test
    fun `given price evidence older than its window when assessed then staging is withheld and the reason names the age and the window`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("5m", settled = 20, hits = 17, measuredAt = at(61))),
            nowMs = now
        )

        val row = verdict.price.single()
        assertFalse(verdict.canStage)
        assertEquals(61L, row.ageMinutes)
        assertEquals(
            "PRICE_EVIDENCE_STALE(age 61min, in-force window 60min)",
            row.staleReason
        )
        assertTrue(
            "The refusal must say why it is stale: ${verdict.blockingReasons}",
            verdict.blockingReasons.single().contains("PRICE_EVIDENCE_STALE(age 61min")
        )
    }

    @Test
    fun `given an hourly cone when read at its doubled window then it is in force and one minute later it is not`() {
        val edge = EvidenceInForce.status("PRICE", at(120), now, 120)
        val past = EvidenceInForce.status("PRICE", at(121), now, 120)

        assertTrue("The window boundary is inclusive", edge.inForce)
        assertNull(edge.reason)
        assertFalse(past.inForce)
        assertEquals("PRICE_EVIDENCE_STALE(age 121min, in-force window 120min)", past.reason)
    }

    @Test
    fun `given a raised in-force window when the same evidence is re-read then it returns to force without a second edit`() {
        val measuredAt = at(180)

        Thresholds.install(ThresholdConfig(evidenceTtlMinutes = 60))
        assertFalse(
            "Three hours old is not evidence under a one-hour window",
            LayeredEvidenceGate.price("5m", settled = 20, hits = 17, measuredAt = measuredAt).qualified
        )

        Thresholds.install(ThresholdConfig(evidenceTtlMinutes = 240))
        val widened = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("5m", settled = 20, hits = 17, measuredAt = measuredAt)),
            nowMs = now
        )

        assertTrue(widened.canStage)
        assertEquals(240, widened.price.single().windowMinutes)
        assertTrue(widened.price.single().qualified)
    }

    @Test
    fun `given an unstamped measurement when assessed then it fails closed instead of being trusted forever`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1h", settled = 4, hits = 4)),
            nowMs = now
        )

        assertFalse(verdict.canStage)
        assertTrue(
            "An unstamped reading must say it is unstamped: ${verdict.price.single().staleReason}",
            verdict.price.single().staleReason!!.startsWith(
                "PRICE_EVIDENCE_STALE(no measurement timestamp"
            )
        )
    }

    @Test
    fun `given a stale pattern horizon when assessed then it is named stale and never becomes an input`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1m", settled = 20, hits = 17, measuredAt = at(1))),
            pattern = listOf(LayeredEvidenceGate.pattern("15m", settled = 4, hits = 4, measuredAt = at(90))),
            nowMs = now
        )

        assertTrue("Pattern never blocks a proven price layer", verdict.canStage)
        assertTrue(verdict.provenPatternHorizons.isEmpty())
        assertTrue(
            "PATTERN is additive, so it must stay inactive",
            EvidenceLayer.PATTERN !in verdict.activeLayers
        )
        assertEquals(
            "PATTERN_EVIDENCE_STALE(age 90min, in-force window 60min)",
            verdict.pattern.single().staleReason
        )
    }

    @Test
    fun `given stale strategy evidence when assessed then the pooled layer stays inactive`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1m", settled = 20, hits = 17, measuredAt = at(1))),
            strategy = LayeredEvidenceGate.strategy("BTCUSDT", settled = 40, hits = 36, measuredAt = at(61)),
            nowMs = now
        )

        assertTrue(verdict.canStage)
        assertFalse(EvidenceLayer.STRATEGY in verdict.activeLayers)
        assertTrue(
            verdict.strategy!!.staleReason!!.startsWith("STRATEGY_EVIDENCE_STALE")
        )
        assertEquals("The pooled layer is scoped by coin, so it gets the plain window", 60, verdict.strategy!!.windowMinutes)
    }

    @Test
    fun `given signal evidence older than the window when read then the horizon is unqualified and describe says why`() {
        val stale = SignalEvidence.assess("5m", settled = 20, hits = 17, measuredAt = at(61), nowMs = now)
        val fresh = SignalEvidence.assess("5m", settled = 20, hits = 17, measuredAt = at(1), nowMs = now)

        assertFalse(stale.qualified)
        assertEquals(
            "PRICE_EVIDENCE_STALE(age 61min, in-force window 60min)",
            stale.staleReason
        )
        assertTrue(stale.describe().contains("PRICE_EVIDENCE_STALE(age 61min"))
        assertFalse(SignalEvidence.isQualified("5m", 20, 17, measuredAt = at(61), nowMs = now))
        assertTrue(fresh.qualified)
        assertTrue(SignalEvidence.isQualified("5m", 20, 17, measuredAt = at(1), nowMs = now))
    }

    @Test
    fun `given a governing cone measured beyond its window when the campaign gate evaluates then arming is refused by name`() {
        val verdict = CampaignReadinessGate().evaluate(
            telemetry = readyTelemetry(measuredMinutesAgo = 61),
            governingHorizons = listOf("15m"),
            nowMs = now
        )

        assertFalse(verdict.canArmOrders)
        assertEquals(CampaignLifecycleState.PLANNED, verdict.nextState)
        assertTrue(
            verdict.rejectionReasons.toString(),
            verdict.rejectionReasons.any {
                it.startsWith("HORIZON_15m_CONE_EVIDENCE_STALE(age 61min")
            }
        )
    }

    @Test
    fun `given a governing cone measured inside its window when the campaign gate evaluates then it stages`() {
        val verdict = CampaignReadinessGate().evaluate(
            telemetry = readyTelemetry(measuredMinutesAgo = 5),
            governingHorizons = listOf("15m"),
            nowMs = now
        )

        assertTrue(verdict.rejectionReasons.toString(), verdict.canArmOrders)
        assertEquals(CampaignLifecycleState.STAGED, verdict.nextState)
    }

    @Test
    fun `given an in-force window outside five to fourteen hundred and forty minutes when validated then it is refused with the range`() {
        for (bad in listOf(4, 0, -1, 1441)) {
            val verdict = ThresholdConfig(evidenceTtlMinutes = bad).validate()
            assertFalse("$bad minutes must not be accepted", verdict.accepted)
            assertTrue(
                "The reason must state the range: ${verdict.rejection}",
                verdict.rejection!!.contains("IN_FORCE_WINDOW_OUT_OF_RANGE_5_TO_1440_MIN")
            )
        }
        assertTrue(ThresholdConfig(evidenceTtlMinutes = 5).validate().accepted)
        assertTrue(ThresholdConfig(evidenceTtlMinutes = 1440).validate().accepted)
        assertEquals(60, ThresholdConfig.DEFAULT.evidenceTtlMinutes)
    }

    @Test
    fun `given the shipped configuration when the in-force window is asked for then it is the longer of the window and twice the horizon`() {
        val config = ThresholdConfig.DEFAULT
        assertEquals(60, config.inForceWindowMinutes("1m"))
        assertEquals(60, config.inForceWindowMinutes("5m"))
        assertEquals(60, config.inForceWindowMinutes("15m"))
        assertEquals(60, config.inForceWindowMinutes("30m"))
        assertEquals(120, config.inForceWindowMinutes("1h"))
        assertEquals("A coin symbol is not a horizon", 60, config.inForceWindowMinutes("BTCUSDT"))
        assertEquals(240, config.copy(evidenceTtlMinutes = 240).inForceWindowMinutes("1h"))
        // The nuance survives a narrower window: an hourly read still gets its two hours.
        assertEquals(120, config.copy(evidenceTtlMinutes = 30).inForceWindowMinutes("1h"))
    }

    @Test
    fun `given stale price evidence when the thresholds readout is built then the effective line names the staleness`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("5m", settled = 20, hits = 17, measuredAt = at(61))),
            nowMs = now
        )

        val row = readout(EvidenceLayer.PRICE, verdict)
        assertTrue(
            "The tab must not print a stale rate as if it were in force: ${row.effective}",
            row.effective.contains("PRICE_EVIDENCE_STALE(age 61min")
        )
    }

    @Test
    fun `given stale pattern evidence when the layered readout is built then the layer reads inactive and names the staleness`() {
        val verdict = LayeredEvidenceGate.assess(
            price = listOf(LayeredEvidenceGate.price("1m", settled = 20, hits = 17, measuredAt = at(1))),
            pattern = listOf(LayeredEvidenceGate.pattern("15m", settled = 4, hits = 4, measuredAt = at(90))),
            nowMs = now
        )

        val pattern = LayeredEvidencePresentation.rows(verdict)
            .first { it.layer == EvidenceLayer.PATTERN }
        assertFalse(pattern.active)
        assertEquals(LayeredEvidencePresentation.INACTIVE, pattern.statusLabel)
        assertTrue(pattern.detail.contains("PATTERN_EVIDENCE_STALE(age 90min"))
    }

    private fun readyTelemetry(measuredMinutesAgo: Long) = AlphaPulseReadinessTelemetry(
        settled1mCount = 4,
        settled5mCount = 2,
        settled15mCount = 3,
        settled30mCount = 2,
        settled1hCount = 2,
        readyEnsembleModels = 5,
        totalEnsembleModels = 5,
        readyTsfmDockModels = 2,
        requiredTsfmDockModels = 2,
        horizonConeHitRatePct = mapOf("15m" to 90.0),
        componentAvailability = mapOf(
            StagingComponent.TSFM to ComponentAvailability.READY,
            StagingComponent.NEURAL_LOB to ComponentAvailability.READY,
            StagingComponent.LIQUIDITY_HEATMAP to ComponentAvailability.READY,
            StagingComponent.ENSEMBLE to ComponentAvailability.READY
        ),
        horizonConeMeasuredAt = mapOf("15m" to at(measuredMinutesAgo))
    )
}
