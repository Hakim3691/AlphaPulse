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
import org.junit.Assert.assertFalse
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
 *  - given a horizon this coin has not proven (C-55), when it is bridged, then its
 *    forecast is withheld and the pain path says why, while a proven horizon on the
 *    same coin still feeds the campaign.
 */
class ScreenedUniverseBridgeBddSpec {

    private val bridge = ScreenedUniverseBridge(now = { 60_000L })

    @Test
    fun `given screened rows ranked by alpha pulse, when bridged, then upstream rank decides order`() {
        // Given: AlphaPulse ranked ETH first and BTC second, and the rows arrive reversed.
        // Both coins have proven a 5m horizon, so both are admissible (C-57).
        val rows = listOf(
            screened("BTCUSDT", rank = 2, price = 60_000.0),
            screened("ETHUSDT", rank = 1, price = 3_000.0)
        )
        val forecasts = mapOf(
            "BTCUSDT" to proof("BTCUSDT", "5m", 60_000.0),
            "ETHUSDT" to proof("ETHUSDT", "5m", 3_000.0)
        )

        // When:
        val assets = bridge.toCryptoAssets(rows, forecasts)

        // Then: the upstream order survives and neither proven coin is dropped.
        assertEquals(listOf("ETHUSDT", "BTCUSDT"), assets.map { it.symbol })
    }

    @Test
    fun `given a coin with no proven horizon, when the universe is bridged, then it is removed rather than carried as a neutral asset`() {
        // Given: a screened coin whose predictions have not proven any horizon yet.
        val rows = listOf(
            screened("SOLUSDT", rank = 1, price = 150.0, takerBuyRatio = 62.0),
            screened("INJUSDT", rank = 2, price = 21.0)
        )
        val forecasts = mapOf("INJUSDT" to proof("INJUSDT", "5m", 21.0))

        // When:
        val assets = bridge.toCryptoAssets(rows, forecasts)

        // Then: only the proven coin reaches the campaign engine. Carrying SOL as a
        // neutral asset would let a board be shaped for a coin with no evidence, and
        // its only reading would have been taker flow.
        assertEquals(listOf("INJUSDT"), assets.map { it.symbol })
        assertTrue(bridge.isProven(forecasts.getValue("INJUSDT")))
        assertFalse(bridge.isProven(emptyList()))
    }

    @Test
    fun `given bullish horizon predictions, when bridged, then bias and regime follow the forecast`() {
        // Given: AlphaPulse predicts +1.2% at 5m and +3.0% at 1h, both proven (C-55).
        val row = screened("INJUSDT", rank = 1, price = 21.0)
        val predictions = listOf(
            prediction("INJUSDT", "5m", minutes = 5, base = 21.0, predicted = 21.25),
            prediction("INJUSDT", "1h", minutes = 60, base = 21.0, predicted = 21.63)
        ) + proof("INJUSDT", "5m", 21.0) + proof("INJUSDT", "1h", 21.0)

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
        val predictions = listOf(
            prediction("SUIUSDT", "5m", minutes = 5, base = 2.00, predicted = 2.02),
            prediction("SUIUSDT", "1h", minutes = 60, base = 2.00, predicted = 2.08)
        ) + proof("SUIUSDT", "5m", 2.00) + proof("SUIUSDT", "1h", 2.00)
        val asset = bridge.toCryptoAsset(
            screened("SUIUSDT", rank = 1, price = 2.00, low = 1.90, high = 2.10),
            predictions
        )
        val forecastTargets = bridge.forecastTargetsFor(predictions)
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

    @Test
    fun `given a horizon the coin has never proven, when the universe is bridged, then the coin is withheld entirely`() {
        // Given: one fresh 1h forecast beside only three settlements at a perfect rate.
        // C-57's hourly floor is four, so three lucky calls are an anecdote.
        val row = screened("TIAUSDT", rank = 1, price = 8.0)
        val predictions = listOf(
            prediction("TIAUSDT", "1h", minutes = 60, base = 8.0, predicted = 8.24)
        ) + List(3) { settledProof("TIAUSDT", "1h", 8.0) }

        // When:
        val targets = bridge.forecastTargetsFor(predictions)
        val assets = bridge.toCryptoAssets(listOf(row), mapOf("TIAUSDT" to predictions))

        // Then: no forecast is fed, and the coin is removed from the campaign universe
        // rather than carried with a "withheld" note.
        assertEquals(0, targets.size)
        assertTrue(assets.isEmpty())
    }

    @Test
    fun `given one proven and one unproven horizon, when bridged, then only the proven horizon reaches the campaign`() {
        // Given: 5m has called its cone 21 times at 90%, 1h has settled four times at
        // 75% (3 of 4), which is below C-57's 85% floor even though the sample is full.
        val predictions = listOf(
            prediction("ARBUSDT", "5m", minutes = 5, base = 0.90, predicted = 0.92),
            prediction("ARBUSDT", "1h", minutes = 60, base = 0.90, predicted = 0.95)
        ) + List(21) { settledProof("ARBUSDT", "5m", 0.90, inCone = it < 19) } +
            List(4) { settledProof("ARBUSDT", "1h", 0.90, inCone = it < 3) }

        // When:
        val targets = bridge.forecastTargetsFor(predictions)

        // Then: the unproven 1h target is withheld while 5m feeds the campaign, per coin
        // and per horizon — not as one blended rate.
        assertEquals(listOf("5m"), targets.map { it.horizonId })
        assertEquals(
            listOf("1h", "5m"),
            bridge.evidenceFor(predictions).map { it.horizonId }
        )
    }

    /** Twenty settled calls of one horizon: the minimum sample C-55 calls evidence. */
    private fun proof(symbol: String, horizonId: String, base: Double): List<PredictionEntity> =
        List(20) { settledProof(symbol, horizonId, base) }

    /** A settled prediction whose cone was called; [inCone] decides whether it was right. */
    private fun settledProof(
        symbol: String,
        horizonId: String,
        base: Double,
        inCone: Boolean = true
    ): PredictionEntity = prediction(
        symbol = symbol,
        horizonId = horizonId,
        minutes = if (horizonId == "1h") 60 else 5,
        base = base,
        predicted = base
        // Older than the fresh row the scenario issues, so "newest wins" still picks it.
    ).copy(settled = true, inCone = inCone, issuedTimestamp = 900L)

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
