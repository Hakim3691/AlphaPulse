package com.aistudio.alphapulse.ui.viewmodel

import com.aistudio.alphapulse.data.model.FilterBadgeData
import com.aistudio.alphapulse.data.model.ScreenedCoin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-38 — choosing a coin is a hard boundary in the workspace.
 *
 * Why this spec exists
 * --------------------
 * The ten-second evaluation cycle reads `selectedSymbol` once and then writes its result
 * back with `state.copy(...)`. Nothing checked that the symbol it evaluated is still the
 * symbol on screen, so an evaluation already in flight for the previous coin landed after
 * the switch and replaced the new coin's spot price, chart, cone and verdict with the old
 * coin's. Until the next cycle completed, the screen showed one asset's numbers under
 * another asset's name — and the ledger row that was written described the coin the user
 * had already left.
 *
 * The second half of the same defect: on selection only `spotPrice`/`spotChange`/
 * `high24h`/`low24h` changed, so the previous coin's chart, forecast cone, verdict,
 * readiness badges and hit rates stayed on screen as if they described the new one.
 *
 * Spec Ref: §2 Screening & Universe Discovery, §39 workspace panels, C-38
 */
class AssetSelectionBddSpec {

    private fun coin(symbol: String, price: Double) = ScreenedCoin(
        symbol = symbol,
        lastPrice = price,
        highPrice = price * 1.05,
        lowPrice = price * 0.95,
        openPrice = price,
        volume = 1_000_000.0,
        realTakerBuyRatio = 0.55,
        displacement = 0.5,
        exactOI = 1_000_000.0,
        volToOiRatio = 2.0,
        priceChangePercent = 1.5,
        calmarRatio = 1.0,
        sharpeRatio = 1.0,
        annualizedVol = 0.4,
        maxDrawdown = 0.2,
        daysPassedAll = 9,
        passedTodayAll = true,
        passed10DRule = false,
        filter1 = FilterBadgeData(true, "1M Vol"),
        filter2 = FilterBadgeData(true, "55.0%"),
        filter3 = FilterBadgeData(true, "2.00x"),
        filter4 = FilterBadgeData(true, "50.0% Ext")
    )

    /** A workspace already showing BTCUSDT with a full set of per-asset readings. */
    private fun showingBtc() = AlphaPulseUiState(
        isReady = true,
        screenedCoins = listOf(coin("BTCUSDT", 64_000.0), coin("ETHUSDT", 3_200.0)),
        selectedSymbol = "BTCUSDT",
        spotPrice = 64_000.0,
        spotChange = 1.5,
        high24h = 67_200.0,
        low24h = 60_800.0,
        rsi = 71.0,
        macdSignal = "BULLISH",
        netImbalanceUsdt = 2_400_000.0,
        bidRatioPct = 63.4,
        heatmapSource = "API",
        alphaPoc = 63_500.0,
        alphaVah = 65_100.0,
        alphaVal = 62_200.0,
        ltfHitRate = 64.0,
        mtfHitRate = 58.0,
        htfHitRate = 61.0,
        totalSettledForCoin = 42,
        coneHitRateForCoin = 61.9,
        executiveBrief = "BTCUSDT holds the 63.5k shelf.",
        painPathPrior = "Downside magnet 62.2k",
        isLTFReady = true,
        isMTFReady = true,
        isHTFReady = true
    )

    @Test
    fun `given the workspace is showing one asset when another is selected then the previous asset's readings are gone and the new coin's price is shown`() {
        val next = showingBtc().withSelection("ETHUSDT", coin("ETHUSDT", 3_200.0))

        assertEquals("ETHUSDT", next.selectedSymbol)
        assertEquals(3_200.0, next.spotPrice, 1e-9)
        assertEquals(1.5, next.spotChange, 1e-9)

        assertEquals("BTC's chart must not survive the switch", emptyList<Any>(), next.chartPoints)
        assertEquals("BTC's cone must not survive the switch", emptyList<Any>(), next.forecastTargets)
        assertNull("BTC's verdict must not survive the switch", next.decision)
        assertEquals("", next.executiveBrief)
        assertNull(next.painPathPrior)
        assertEquals(0.0, next.alphaPoc, 0.0)
        assertEquals(0.0, next.alphaVah, 0.0)
        assertEquals(0.0, next.alphaVal, 0.0)
        assertEquals("--", next.heatmapSource)
        assertEquals(50.0, next.rsi, 0.0)
        assertEquals("--", next.macdSignal)
        assertEquals(0.0, next.netImbalanceUsdt, 0.0)
        assertNull("A bid ratio belongs to the book that produced it", next.bidRatioPct)

        assertEquals("Readiness is earned per coin", 0, next.totalSettledForCoin)
        assertNull(next.coneHitRateForCoin)
        assertNull(next.ltfHitRate)
        assertNull(next.mtfHitRate)
        assertNull(next.htfHitRate)
        assertFalse(next.isLTFReady)
        assertFalse(next.isMTFReady)
        assertFalse(next.isHTFReady)
    }

    @Test
    fun `given an evaluation for the previously selected asset lands after the switch then it is refused`() {
        val switched = showingBtc().withSelection("ETHUSDT", coin("ETHUSDT", 3_200.0))

        assertFalse(
            "A BTCUSDT evaluation completing after the user selected ETHUSDT must not write to the screen",
            switched.acceptsEvaluation("BTCUSDT")
        )
        assertTrue(switched.acceptsEvaluation("ETHUSDT"))
        assertFalse(
            "An evaluation with no symbol cannot be attributed, so it must be refused",
            switched.acceptsEvaluation("")
        )
    }

    @Test
    fun `given nothing is selected yet when an evaluation lands then it is refused`() {
        assertFalse(AlphaPulseUiState().acceptsEvaluation("BTCUSDT"))
    }

    @Test
    fun `given the production selection path when it is read then it clears through the boundary instead of copying four prices`() {
        val viewModel = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/viewmodel/AlphaPulseViewModel.kt"
        ).readText()

        assertTrue(
            "selectCoin must route the workspace through withSelection so no per-asset reading survives",
            Regex("""fun selectCoin\(symbol: String\)[\s\S]{0,600}?withSelection\(""").containsMatchIn(viewModel)
        )
        assertTrue(
            "The evaluation cycle must ask whether the result still belongs to the selection",
            Regex("""acceptsEvaluation\(symbol\)""").containsMatchIn(viewModel)
        )
        assertTrue(
            "The cycle's state write must be guarded by that answer",
            Regex("""if \(!state\.acceptsEvaluation\(symbol\)\)""").containsMatchIn(viewModel)
        )
    }

    /** Locates a repository file from whichever directory tests run in. */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
