package com.aistudio.alphapulse.engine

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
import com.aistudio.alphapulse.data.model.TakeProfitTarget
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.data.remote.BinanceSignedClient
import com.aistudio.alphapulse.ui.CampaignLiveView
import com.aistudio.alphapulse.ui.LiveOrderStatus
import com.aistudio.alphapulse.ui.LiveOrderViewState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live wiring a board goes through — stage it, cancel it, cancel one order — driven
 * end to end through the desk the ViewModel delegates to, against a recorded order port.
 *
 * Spec Ref: §22 Order Staging, §27 Invalidation/Cancellation, §33 Guardrails
 */
class LiveOrderDeskBddSpec {

    private val apiKey = "key".toCharArray()
    private val secret = "secret".toCharArray()

    private class RecordingPort(
        private val place: (String) -> BinanceSignedClient.OrderResult = {
            BinanceSignedClient.OrderResult(true, 42L, null, "NEW", null)
        },
        var cancelResult: BinanceSignedClient.OrderResult =
            BinanceSignedClient.OrderResult(true, 42L, null, "CANCELED", null),
        var openOrders: List<BinanceSignedClient.OpenOrder> = emptyList()
    ) : SignedOrderPort {
        val placed = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun placeOrder(
            symbol: String,
            side: String,
            orderType: String,
            qty: Double,
            price: Double?,
            stopPrice: Double?,
            reduceOnly: Boolean,
            clientOrderId: String,
            apiKey: CharArray,
            secret: CharArray
        ): BinanceSignedClient.OrderResult {
            placed += clientOrderId
            return place(clientOrderId)
        }

        override suspend fun cancelOrder(
            symbol: String,
            clientOrderId: String,
            apiKey: CharArray,
            secret: CharArray
        ): BinanceSignedClient.OrderResult {
            cancelled += clientOrderId
            return cancelResult
        }

        override suspend fun cancelAllOpenOrders(symbol: String, apiKey: CharArray, secret: CharArray) = true

        override suspend fun fetchOpenOrders(
            symbol: String,
            apiKey: CharArray,
            secret: CharArray
        ): List<BinanceSignedClient.OpenOrder> = openOrders

        override suspend fun createListenKey(apiKey: CharArray): String? = "listen-key"

        override suspend fun keepAliveListenKey(apiKey: CharArray): Boolean = true
    }

    /** The desk as the ViewModel wires it, with in-memory boards and a note log. */
    private inner class Desk(port: RecordingPort, initial: List<Campaign>) {
        val campaigns = initial.toMutableList()
        val notes = mutableListOf<String>()
        val streaks = mutableListOf<Pair<String, Int>>()
        var publishes = 0

        val desk = LiveOrderDesk(
            coordinator = LiveOrderCoordinator(port),
            hasActiveSession = { true },
            credentials = { apiKey to secret },
            campaignsOf = { campaigns },
            applyCampaign = { updated ->
                val index = campaigns.indexOfFirst { it.id == updated.id }
                if (index >= 0) campaigns[index] = updated
            },
            note = { campaignId, code, message -> notes += "$campaignId/$code/$message" },
            onRejectStreak = { symbol, rejects -> streaks += symbol to rejects },
            publish = { publishes++ }
        )

        fun board(campaignId: String): CampaignLiveView = LiveOrderViewState.project(
            campaigns,
            desk.liveOrderRecords,
            desk.routedClientIds
        ).getValue(campaignId)
    }

    @Test
    fun `given an autotrader campaign signal when routed then the board shows the entry and its take-profit staged`() =
        runBlocking {
            // Given: a staged board the autotrader produced.
            val port = RecordingPort()
            val h = Desk(port, listOf(campaign()))

            // When: the board is routed while live.
            h.desk.route(h.campaigns, ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth())

            // Then: the entry and its reduce-only exit both reached the venue, both are
            // tracked as held, and the board offers both to the operator.
            val entryId = ClientOrderIds.forSlice("BTCUSDT_LONG", 1)
            val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)
            assertEquals(setOf(entryId, tpId), port.placed.toSet())
            assertEquals(setOf(entryId, tpId), h.desk.routedClientIds)
            assertEquals(LiveOrderStatus.STAGED, h.desk.liveOrderRecords[entryId]!!.status)
            assertEquals(LiveOrderStatus.STAGED, h.desk.liveOrderRecords[tpId]!!.status)
            assertTrue(h.board("BTCUSDT_LONG").cancellable.containsAll(listOf(entryId, tpId)))
            assertEquals(listOf("BTCUSDT" to 0), h.streaks)
        }

    @Test
    fun `given the venue rejects a leg when routed then the board records the refusal and holds nothing`() =
        runBlocking {
            // Given: the venue takes the entry and refuses the take-profit outright.
            val port = RecordingPort(place = { id ->
                if (id.endsWith("_tp")) {
                    BinanceSignedClient.OrderResult(false, null, null, null, "Immediate cancel")
                } else {
                    BinanceSignedClient.OrderResult(true, 42L, null, "NEW", null)
                }
            })
            val h = Desk(port, listOf(campaign()))

            // When:
            h.desk.route(h.campaigns, ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth())

            // Then: the refusal is user visible against that exact id, only the accepted
            // entry is tracked as held, and the reject streak counts one.
            val entryId = ClientOrderIds.forSlice("BTCUSDT_LONG", 1)
            val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)
            val rejected = h.desk.liveOrderRecords[tpId]!!
            assertEquals(LiveOrderStatus.REJECTED, rejected.status)
            assertTrue(rejected.reason!!.contains("Immediate cancel"))
            assertEquals(setOf(entryId), h.desk.routedClientIds)
            assertFalse(h.board("BTCUSDT_LONG").cancellable.contains(tpId))
            assertTrue(h.notes.any { "ORDER_REJECTED" in it })
            assertEquals(listOf("BTCUSDT" to 1), h.streaks)
        }

    @Test
    fun `given a two-symbol board when one symbol is refused then only that symbol's streak rises`() =
        runBlocking {
            // Given: two live boards, and the venue refusing every ETHUSDT leg.
            val port = RecordingPort(place = { id ->
                if (id.startsWith("ETHUSDT")) {
                    BinanceSignedClient.OrderResult(false, null, null, null, "Margin is insufficient.")
                } else {
                    BinanceSignedClient.OrderResult(true, 42L, null, "NEW", null)
                }
            })
            val h = Desk(port, listOf(campaign(), campaign(symbol = "ETHUSDT", basePrice = 3_000.0)))

            // When:
            h.desk.route(h.campaigns, ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth())

            // Then: the streak that pauses a venue is per symbol, so the healthy symbol is
            // not throttled for the other one's refusals.
            assertEquals(listOf("BTCUSDT" to 0, "ETHUSDT" to 2), h.streaks)
        }

    @Test
    fun `given staged orders when the board is cancelled then exactly the staged set is cancelled`() = runBlocking {
        // Given: a two-slice board, both slices routed.
        val port = RecordingPort()
        val h = Desk(port, listOf(campaign(slices = 2)))
        h.desk.route(h.campaigns, ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth())
        val slice1Entry = ClientOrderIds.forSlice("BTCUSDT_LONG", 1)
        val slice1Tp = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)
        val slice2Entry = ClientOrderIds.forSlice("BTCUSDT_LONG", 2)
        val slice2Tp = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 2)
        assertTrue(h.desk.routedClientIds.containsAll(listOf(slice1Entry, slice1Tp, slice2Entry, slice2Tp)))

        // And: the second slice is no longer resting, and its exit is not tracked as
        // staged any more — so nothing of it may be cancelled blind.
        h.desk.routedClientIds.remove(slice2Tp)
        val settled = h.campaigns.single().copy(
            entryLadder = h.campaigns.single().entryLadder.map {
                if (it.sliceIndex == 2) it.copy(isResting = false) else it
            }
        )
        h.campaigns[0] = settled

        // When:
        h.desk.cancelStaged(settled)

        // Then: only the still-resting, still-staged first slice is cancelled.
        assertEquals(listOf(slice1Entry, slice1Tp), port.cancelled)
        assertEquals(LiveOrderStatus.CANCELLED, h.desk.liveOrderRecords[slice1Entry]!!.status)
        assertFalse(h.desk.routedClientIds.contains(slice1Entry))
        assertTrue("the untouched slice stays tracked", h.desk.routedClientIds.contains(slice2Entry))
    }

    @Test
    fun `given the venue refuses the cancel while the order still rests then the board shows a cancel failure`() =
        runBlocking {
            // Given: a routed entry, and a cancel the venue refuses because it still
            // holds the order.
            val port = RecordingPort()
            val h = Desk(port, listOf(campaign()))
            h.desk.route(h.campaigns, ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth())
            val entryId = ClientOrderIds.forSlice("BTCUSDT_LONG", 1)
            port.cancelResult = BinanceSignedClient.OrderResult(false, null, null, null, "Unknown order sent.")
            port.openOrders = listOf(openOrder(entryId))

            // When:
            h.desk.cancelLiveOrder(entryId)

            // Then: the failure is named, the order stays tracked as held, and the board
            // still offers it — nothing claims it was cancelled or filled.
            val record = h.desk.liveOrderRecords[entryId]!!
            assertEquals(LiveOrderStatus.CANCEL_FAILED, record.status)
            assertTrue(record.reason!!.contains("Unknown order sent."))
            assertTrue(h.desk.routedClientIds.contains(entryId))
            assertTrue(h.board("BTCUSDT_LONG").cancellable.contains(entryId))
            assertTrue(h.notes.any { "ORDER_CANCEL_FAILED" in it })
        }

    @Test
    fun `given the cancel loses the race to a fill then the slice is booked filled rather than cancelled`() =
        runBlocking {
            // Given: a routed entry the venue no longer holds — it filled first.
            val port = RecordingPort()
            val h = Desk(port, listOf(campaign()))
            h.desk.route(h.campaigns, ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth())
            val entryId = ClientOrderIds.forSlice("BTCUSDT_LONG", 1)
            port.cancelResult = BinanceSignedClient.OrderResult(false, null, null, null, "Unknown order sent.")
            port.openOrders = emptyList()

            // When:
            h.desk.cancelLiveOrder(entryId)

            // Then: the fill is booked on the ladder, the campaign leaves STAGED, and the
            // order is no longer tracked as held.
            val record = h.desk.liveOrderRecords[entryId]!!
            assertEquals(LiveOrderStatus.FILLED, record.status)
            assertEquals(LiveOrderViewState.FILLED_BEFORE_CANCEL, record.reason)
            assertFalse(h.desk.routedClientIds.contains(entryId))
            val booked = h.campaigns.single()
            assertTrue(booked.entryLadder.single().isFilled)
            assertEquals(CampaignState.PARTIALLY_FILLED, booked.status)
            assertTrue(h.notes.any { "FILL_BEATS_CANCEL" in it })
        }

    private fun openOrder(clientOrderId: String) = BinanceSignedClient.OpenOrder(
        orderId = 42L,
        symbol = "BTCUSDT",
        clientOrderId = clientOrderId,
        price = 150.0,
        origQty = 3.0,
        executedQty = 0.0,
        status = "NEW",
        type = "LIMIT",
        side = "BUY"
    )

    private fun campaign(
        symbol: String = "BTCUSDT",
        basePrice: Double = 150.0,
        slices: Int = 1
    ): Campaign = Campaign(
        id = "${symbol}_LONG",
        asset = symbol,
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 80.0,
        sizeBudgetUsd = 1000.0,
        sizeMultiplier = 1.0,
        entryLadder = (1..slices).map { index ->
            val price = basePrice - (index - 1)
            LadderSlice(
                sliceIndex = index,
                price = price,
                qty = 3.0,
                notionalUsd = 3.0 * price,
                orderType = OrderType.PASSIVE_LIMIT,
                isFilled = false,
                isResting = true
            )
        },
        stopLogic = StopLogic(45, 70, basePrice - 5.0),
        targets = listOf(TakeProfitTarget("Forecast 1h", basePrice + 4.5, 50)),
        invalidationScore = 10,
        topReasons = emptyList(),
        status = CampaignState.STAGED
    )
}
