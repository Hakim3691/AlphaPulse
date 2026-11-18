package com.aistudio.alphapulse.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.aistudio.alphapulse.data.db.PatternLedgerEntity
import com.aistudio.alphapulse.domain.pattern.PatternLedgerEntry
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.BrandNeural
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BDD spec for the Pattern Prediction Ledger.
 * 
 * Issue: The pattern prediction should show all predictions similar to the 
 * prediction audit ledger. It should also have the hit rate and export function 
 * similarly.
 * 
 * Spec Ref: §73 Pattern Prediction & Learning Ledger
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class PatternLedgerBddSpec {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given pattern predictions when the ledger renders then shows all entries`() {
        // Create sample pattern ledger entries
        val entries = listOf(
            PatternLedgerEntity(
                id = "1",
                issuedTimestamp = System.currentTimeMillis(),
                asset = "BTCUSDT",
                horizon = "1h",
                targetTime = System.currentTimeMillis() + 3600000,
                predictedPatternId = "pattern1",
                predictedPatternName = "Higher High",
                predictedCategory = "Bullish Reversal",
                confidence = 0.85,
                realizedPattern = "Higher High",
                fitLoss = 0.12,
                status = "Settled",
                sgdAction = "+w_reinforce",
                settled = true,
                tsfmProvider = "TimesFM",
                tsfmDriftPct = 0.5,
                tsfmAligned = true
            ),
            PatternLedgerEntity(
                id = "2",
                issuedTimestamp = System.currentTimeMillis(),
                asset = "ETHUSDT",
                horizon = "5m",
                targetTime = System.currentTimeMillis() + 300000,
                predictedPatternId = "pattern2",
                predictedPatternName = "Lower Low",
                predictedCategory = "Bearish Reversal",
                confidence = 0.72,
                realizedPattern = "Lower Low",
                fitLoss = 0.18,
                status = "Pending",
                sgdAction = "- w_penalty",
                settled = false,
                tsfmProvider = "",
                tsfmDriftPct = 0.0,
                tsfmAligned = null
            )
        )
        
        compose.setContent {
            PatternLedgerSection(
                entries = entries.map { it.toEntry() },
                horizonFilter = "ALL",
                onFilterChange = { _ -> },
                onExportClick = {},
                // This scenario is about rendering every row, so it opens the settled
                // rows the ledger hides by default (C-29).
                initiallyShowSettled = true
            )
        }
        
        // Should show both entries
        compose.onNodeWithText("BTCUSDT").assertExists()
        compose.onNodeWithText("ETHUSDT").assertExists()
        // Should show hit rate
        compose.onNodeWithText("Hit", substring = true).assertExists()
    }

    @Test
    fun `given settled predictions when the ledger renders then shows hit rate`() {
        val entries = listOf(
            PatternLedgerEntity(
                id = "1",
                issuedTimestamp = System.currentTimeMillis(),
                asset = "BTCUSDT",
                horizon = "1h",
                targetTime = System.currentTimeMillis() + 3600000,
                predictedPatternId = "pattern1",
                predictedPatternName = "Higher High",
                predictedCategory = "Bullish Reversal",
                confidence = 0.85,
                realizedPattern = "Higher High",
                fitLoss = 0.12,
                status = "Settled",
                sgdAction = "+w_reinforce",
                settled = true,
                tsfmProvider = "TimesFM",
                tsfmDriftPct = 0.5,
                tsfmAligned = true
            ),
            PatternLedgerEntity(
                id = "2",
                issuedTimestamp = System.currentTimeMillis(),
                asset = "ETHUSDT",
                horizon = "5m",
                targetTime = System.currentTimeMillis() + 300000,
                predictedPatternId = "pattern2",
                predictedPatternName = "Lower Low",
                predictedCategory = "Bearish Reversal",
                confidence = 0.72,
                realizedPattern = "Lower Low",
                fitLoss = 0.18,
                status = "Settled",
                sgdAction = "- w_penalty",
                settled = true,
                tsfmProvider = "",
                tsfmDriftPct = 0.0,
                tsfmAligned = true
            )
        )
        
        compose.setContent {
            PatternLedgerSection(
                entries = entries.map { it.toEntry() },
                horizonFilter = "ALL",
                onFilterChange = { _ -> },
                onExportClick = {},
            )
        }
        
        // Both are settled, should show hit rate
        compose.onNodeWithText("Hit: 100.0%", substring = true).assertExists()
    }
}