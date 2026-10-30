package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.db.ScreenedCoinEntity
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.ForecastTarget
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.data.model.LevelZone
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The campaign engine's universe is AlphaPulse's screen, and its boards are priced
 * from AlphaPulse's forecast.
 *
 * Given/When/Then:
 *  - given two screened coins the upstream ranking is reversed, when the bridge maps
 *    them, then the upstream rank decides the order and no coin is dropped.
 *  - given a coin with no forecast yet, when it is bridged, then it still produces a
 *    usable asset (neutral regime, taker-flow bias) instead of being filtered out.
 *  - given bullish per-horizon predictions, when the asset is bridged, then the bias
 *    and regime follow the forecast.
 *  - given the same predictions, when campaigns are planned, then each take-profit is
 *    priced at the predicted price for its horizon and the long side outranks the short.
 */
class ScreenedUniverseBridgeBddSpec {

    private val bridge = ScreenedUniverseBridge()

    @Test
    fun `given screened rows ranked by alpha pulse, when bridged, then upstream rank decides order`() {
        // Given: AlphaPulse ranked ETH first and BTC second, and the rows arrive reversed.
        val rows = listOf(
            screened("BTCUSDT", rank = 2, price = 60_000.0),
            screened("ETHUSDT", rank = 1, price = 3_000.0)
        )

        // When:
        val assets = bridge.toCryptoAssets(rows, emptyMap())

        // Then: the upstream order survives and neither coin is re-filtered away.
        assertEquals(listOf("ETHUSDT", "BTCUSDT"), assets.map { it.symbol })
    }

    @Test
    fun `given a coin with no forecast, when bridged, then it stays a usable neutral asset`() {
        // Given: a screened coin whose predictions have not run yet, taker flow bullish.
        val row = screened("SOLUSDT", rank = 1, price = 150.0, takerBuyRatio = 62.0)

        // When:
        val asset = bridge.toCryptoAsset(row, emptyList())

        // Then: it is not dropped, and the only directional reading carried over is flow.
        assertEquals(MarketRegime.BALANCE, asset.regime)
        assertTrue("flow bias should be positive", asset.tacticalBias > 0)
        assertTrue("a forecast is still awaited", asset.primaryPainPath.contains("Awaiting AlphaPulse forecast"))
    }

    @Test
    fun `given bullish horizon predictions, when bridged, then bias and regime follow the forecast`() {
        // Given: AlphaPulse predicts +1.2% at 5m and +3.0% at 1h.
        val row = screened("INJUSDT", rank = 1, price = 21.0)
        val predictions = listOf(
            prediction("INJUSDT", "5m", minutes = 5, base = 21.0, predicted = 21.25),
            prediction("INJUSDT", "1h", minutes = 60, base = 21.0, predicted = 21.63)
        )

        // When:
        val asset = bridge.toCryptoAsset(row, predictions)

        // Then: both bias scores are bullish and the regime is a continuation.
        assertTrue("tactical bias should be bullish", asset.tacticalBias > 0)
        assertTrue("strategic bias should be bullish", asset.strategicBias > 0)
        assertEquals(MarketRegime.BULLISH_CONTINUATION, asset.regime)
        assertTrue(asset.primaryPainPath.contains("AlphaPulse 5m forecast"))
    }

    @Test
    fun `given bullish predictions, when campaigns are planned, then targets use forecast prices`() {
        // Given: a support zone under a coin AlphaPulse forecasts upward.
        val asset = bridge.toCryptoAsset(
            screened("SUIUSDT", rank = 1, price = 2.00, low = 1.90, high = 2.10),
            listOf(
                prediction("SUIUSDT", "5m", minutes = 5, base = 2.00, predicted = 2.02),
                prediction("SUIUSDT", "1h", minutes = 60, base = 2.00, predicted = 2.08)
            )
        )
        val forecastTargets = bridge.forecastTargetsFor(
            listOf(
                prediction("SUIUSDT", "5m", minutes = 5, base = 2.00, predicted = 2.02),
                prediction("SUIUSDT", "1h", minutes = 60, base = 2.00, predicted = 2.08)
            )
        )
        val levelMap = LevelMap(
            asset = asset.symbol,
            zones = listOf(
                LevelZone("s", ZoneType.HTF_SUPPORT, 1.94, 1.97, "4h", 80, 4, 90),
                LevelZone("r", ZoneType.HTF_RESISTANCE, 2.08, 2.12, "4h", 78, 3, 88),
                LevelZone("p", ZoneType.LTF_PIVOT, 2.00, 2.01, "1h", 70, 5, 92)
            ),
            dominantPivot = 2.00,
            damageScore = 20,
            confidenceSummary = 80,
            timestamp = 0L
        )

        // When:
        val campaigns = CampaignEngine().planCampaigns(
            asset,
            levelMap,
            emptyList(),
            forecasts = forecastTargets
        )

        // Then: long boards price their targets on the predicted ladder, and the
        // forecast-favoured side outranks the opposed side.
        val supportLong = campaigns.first { it.family == CampaignFamily.SUPPORT_LONG }
        val resistanceShort = campaigns.first { it.family == CampaignFamily.RESISTANCE_SHORT }
        val predictedPrices = forecastTargets.map { it.predictedPrice }
        assertTrue(
            "support long target should be a forecast price",
            supportLong.targets.any { it.price in predictedPrices }
        )
        assertTrue(
            "at least one target should name its horizon",
            supportLong.targets.any { it.levelName.contains("forecast") }
        )
        assertTrue(
            "the bullish forecast should outrank the short board",
            supportLong.priorityScore > resistanceShort.priorityScore
        )
    }

    private fun screened(
        symbol: String,
        rank: Int,
        price: Double,
        low: Double = price * 0.99,
        high: Double = price * 1.01,
        takerBuyRatio: Double = 50.0
    ) = ScreenedCoinEntity(
        symbol = symbol,
        lastPrice = price,
        highPrice = high,
        lowPrice = low,
        openPrice = price,
        volume = 120_000_000.0,
        realTakerBuyRatio = takerBuyRatio,
        displacement = 0.55,
        exactOI = 40_000_000.0,
        volToOiRatio = 1.4,
        priceChangePercent = 1.2,
        calmarRatio = 1.0,
        sharpeRatio = 0.9,
        annualizedVol = 0.8,
        maxDrawdown = 12.0,
        daysPassedAll = 3,
        passedTodayAll = true,
        passed10DRule = true,
        filter1Passed = true,
        filter1Val = "120M Vol",
        filter2Passed = true,
        filter2Val = "58.0%",
        filter3Passed = true,
        filter3Val = "1.40x",
        filter4Passed = true,
        filter4Val = "55.0% Ext",
        rank = rank
    )

    private fun prediction(
        symbol: String,
        horizonId: String,
        minutes: Int,
        base: Double,
        predicted: Double
    ) = PredictionEntity(
        id = "$symbol|$horizonId|0",
        symbol = symbol,
        horizonId = horizonId,
        horizonLabel = horizonId,
        modelType = "Tri-Factor",
        minutes = minutes,
        issuedTimestamp = 1_000L,
        targetTimestamp = 1_000L + minutes * 60_000L,
        basePrice = base,
        predictedPrice = predicted,
        upperBound = predicted * 1.01,
        lowerBound = predicted * 0.99,
        muK = 0.0,
        muP = 0.0,
        muAlpha = 0.0,
        settled = false
    )
}
