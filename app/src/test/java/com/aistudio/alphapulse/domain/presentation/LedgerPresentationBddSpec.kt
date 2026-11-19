package com.aistudio.alphapulse.domain.presentation

import com.aistudio.alphapulse.domain.decision.DecisionReport
import com.aistudio.alphapulse.domain.decision.DecisionState
import com.aistudio.alphapulse.domain.strategy.SignalDirection
import com.aistudio.alphapulse.domain.strategy.StrategySignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 1 presentation contract, specified Given/When/Then.
 *
 * Every defect in the Phase 1 audit was a *readability* defect: the numbers were
 * correct and the sentence was unreadable. These scenarios pin the compact forms
 * so a later refactor cannot quietly reintroduce the raw log line.
 *
 * Spec Ref: Phase 1 D1 Tokenization, D2 Neutral Attribution, D3 Conflict Tags, §3 additions
 */
class LedgerPresentationBddSpec {

    // ---- D1 String Tokenization -------------------------------------------

    @Test
    fun `given a below-gate needle verdict when tokenized then model action score gate and fallback all fit one token`() {
        val token = TokenizedReason.of("NEEDLE3", "LONG", 0.50)

        assertEquals("[N3: Long @ 0.50 < 0.70] Fallback: Rules", token)
        // 180dp is the column width; the token must stay far inside it.
        assertTrue("Token must stay compact: $token", token.length <= 40)
    }

    @Test
    fun `given an unscored deterministic verdict when tokenized then no fake score is printed`() {
        val token = TokenizedReason.of("DETERMINISTIC", "NEUTRAL", 0.0)

        assertEquals("[RULES: Neutral @ -- < 0.70] Fallback: Rules", token)
    }

    @Test
    fun `given a confident needle verdict when tokenized then the fallback names needle rather than rules`() {
        assertEquals(
            "[N3: Strong Long @ 0.88 < 0.70] Fallback: Needle 3",
            TokenizedReason.of("NEEDLE3", "STRONG_LONG", 0.88)
        )
    }

    // ---- D2 Neutral Explanations -----------------------------------------

    @Test
    fun `given opposing strategy camps when the verdict is flat then the attribution names the conflict`() {
        val flat = DecisionReport(DecisionState.NEUTRAL, 0.0, "Fallback")

        assertEquals(
            "Directional Conflict: 1 Long vs. 2 Short Strategies",
            NeutralAttribution.of(flat, longCount = 1, shortCount = 2)
        )
    }

    @Test
    fun `given a calibrated score below the gate when the verdict is flat then the attribution says so`() {
        val flat = DecisionReport(DecisionState.NEUTRAL, 0.50, "below gate")

        val text = NeutralAttribution.of(flat, longCount = 0, shortCount = 0)

        assertTrue("Attribution must name the gate: $text", text!!.contains("Confidence Gate Unmet"))
        assertTrue("Attribution must print the score", text.contains("0.50"))
        assertTrue("Attribution must print the requirement", text.contains("0.70"))
    }

    @Test
    fun `given no calibrated score when the verdict is flat then the attribution blames the quorum`() {
        val flat = DecisionReport(DecisionState.NEUTRAL, 0.0, "fallback")

        assertEquals(
            "Quorum Failure: Minimum 2 Model Votes Required",
            NeutralAttribution.of(flat, longCount = 0, shortCount = 0)
        )
    }

    @Test
    fun `given a directional verdict then no neutral attribution is invented`() {
        val long = DecisionReport(DecisionState.LONG, 0.88, "long")

        assertNull(NeutralAttribution.of(long, longCount = 2, shortCount = 1))
        assertNull(NeutralAttribution.of(null, 0, 0))
    }

    // ---- D3 Strategy Conflict Tags ---------------------------------------

    private fun signal(id: String, direction: SignalDirection) = StrategySignal(
        id = id,
        name = id,
        direction = direction,
        entry = null,
        exit = null,
        invalidation = null,
        detail = id
    )

    private val superTrend = signal("SuperTrend", SignalDirection.BULLISH)
    private val dualThrust = signal("Dual Thrust", SignalDirection.BEARISH)
    private val vwap = signal("VWAP Reversion", SignalDirection.BEARISH)

    @Test
    fun `given one long and two shorts when dominance is read then the majority sets the lead`() {
        val dominant = StrategyConflicts.dominantDirection(listOf(superTrend, dualThrust, vwap))

        assertEquals(SignalDirection.BEARISH, dominant)
    }

    @Test
    fun `given the dominant lead when opposing cards are tagged then only they are a conflict`() {
        val dominant = SignalDirection.BULLISH

        assertEquals("[DOMINANT]", StrategyConflicts.tagOf(superTrend, dominant))
        assertEquals("[WATCH: CONFLICT]", StrategyConflicts.tagOf(vwap, dominant))
        assertNull(StrategyConflicts.tagOf(signal("flat", SignalDirection.NEUTRAL), dominant))
    }

    @Test
    fun `given an opposing card when its note is read then it names what it fights`() {
        val note = StrategyConflicts.noteOf(vwap, SignalDirection.BULLISH)

        assertEquals("Counter-trend setup against dominant bullish MTF drift", note)
    }

    // ---- §3 additions -----------------------------------------------------

    @Test
    fun `given a tested shelf when zone metadata renders then it prints distance freshness and touch count`() {
        assertEquals("Dist: 0.3 ATR \u00b7 Zone: Fresh (Touch 0/3)", ZoneMetadata.format(0.3, 0))
        assertEquals("Dist: 1.2 ATR \u00b7 Zone: Tested (Touch 1/3)", ZoneMetadata.format(1.2, 1))
        assertEquals("Dist: -- ATR \u00b7 Zone: Exhausted (Touch 3/3)", ZoneMetadata.format(null, 3))
    }

    @Test
    fun `given a penalty step when its tag is built then it is operational rather than debug prose`() {
        assertEquals(
            "[PENALTY: Loss 1.10] Hanging Man confirmed",
            SgdActionTag.of("SGD Step: - w_penalty (Loss: 1.10)", "Hanging Man")
        )
        assertEquals("[PRESERVED] Weights refined via SGD", SgdActionTag.of("SGD Step: +w_reinforce (Loss: 0.41)", "Wedge"))
        assertEquals("[SKIP: False Alarm] Wedge unconfirmed by tape", SgdActionTag.of("SGD Skip: no realized pattern", "Wedge"))
    }

    @Test
    fun `given a horizon when its group is read then it is tactical or strategic`() {
        assertEquals("TACTICAL", HorizonGroups.groupOf("1m"))
        assertEquals("TACTICAL", HorizonGroups.groupOf("15m"))
        assertEquals("STRATEGIC", HorizonGroups.groupOf("1h"))
        assertNull(HorizonGroups.groupOf("ALL"))
    }

    @Test
    fun `given a suspended setup when its status is read then it never prints empty dashes`() {
        assertEquals("STATUS: Staging Suspended (Awaiting Trigger)", StrategyLevels.unavailableStatus())
        assertEquals("21.9933 (1m Close Below Shelf)", StrategyLevels.invalidationLabel(21.9933))
        assertEquals("--", StrategyLevels.invalidationLabel(null))
    }
}
