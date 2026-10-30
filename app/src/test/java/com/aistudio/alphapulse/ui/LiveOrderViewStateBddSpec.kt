package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.data.model.BoardRole
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignFamily
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.LadderSlice
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.StopLogic
import com.aistudio.alphapulse.data.model.TakeProfitTarget
import com.aistudio.alphapulse.data.remote.BinanceSignedClient
import com.aistudio.alphapulse.engine.ClientOrderIds
import com.aistudio.alphapulse.engine.LiveOrderCoordinator
import com.aistudio.alphapulse.engine.SignedOrderPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-order view state the operator sees, specified as Given/When/Then.
 *
 * These scenarios pin the projection from coordinator results onto the campaign
 * card — entry and take-profit ids with their statuses, the rejection reason for a
 * refused order, and what a user-initiated cancel reports when the order filled
 * first.
 *
 * Spec Ref: §22 Order Staging, §27 Invalidation/Cancellation, §33 Guardrails
 */
class LiveOrderViewStateBddSpec {

    private val apiKey = "key".toCharArray()
    private val secret = "secret".toCharArray()

    /** Only cancel and open-order reads are exercised here. */
    private class FakePort(
        var cancelResult: BinanceSignedClient.OrderResult =
            BinanceSignedClient.OrderResult(true, 5L, null, "CANCELED", null),
        var openOrders: List<BinanceSignedClient.OpenOrder> = emptyList()
    ) : SignedOrderPort {
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
        ) = BinanceSignedClient.OrderResult(false, null, null, null, "not used")

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

    @Test
    fun `given a campaign with staged entry and take-profit, when the live view is built, then both ids and statuses render`() {
        // Given: both legs were accepted by the venue and are resting.
        val campaign = campaign(slices = 1, status = CampaignState.STAGED)
        val entryId = ClientOrderIds.forSlice("BTCUSDT_LONG", 1)
        val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)
        val records = mapOf(
            entryId to LiveOrderRecord(entryId, LiveLeg.ENTRY, LiveOrderStatus.RESTING),
            tpId to LiveOrderRecord(tpId, LiveLeg.TAKE_PROFIT, LiveOrderStatus.RESTING)
        )

        // When:
        val view = LiveOrderViewState.project(listOf(campaign), records, setOf(entryId, tpId))
            .getValue("BTCUSDT_LONG")

        // Then: one row per slice, each leg carrying its own id, price and status, and
        // the campaign reports the state the operator needs to see.
        val slice = view.slices.single()
        assertEquals("BTCUSDT_LONG", view.campaignId)
        assertEquals(CampaignState.STAGED, view.status)
        assertEquals(entryId, slice.entry.clientOrderId)
        assertEquals(LiveLeg.ENTRY, slice.entry.leg)
        assertEquals(LiveOrderStatus.RESTING, slice.entry.status)
        assertEquals(150.0, slice.entry.price!!, 1e-9)
        assertEquals(tpId, slice.takeProfit.clientOrderId)
        assertEquals(LiveLeg.TAKE_PROFIT, slice.takeProfit.leg)
        assertEquals(LiveOrderStatus.RESTING, slice.takeProfit.status)
        assertEquals(154.5, slice.takeProfit.price!!, 1e-9)
        assertEquals(setOf(entryId, tpId), view.cancellable)
    }

    @Test
    fun `given a completed campaign, when the live view is built, then the closed exit reads as closed at target`() {
        // Given: the take-profit executed and reconcile marked the slice closed.
        val campaign = campaign(
            slices = 1,
            filledSlices = setOf(1),
            closedSlices = setOf(1),
            status = CampaignState.COMPLETED
        )
        val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)

        // When:
        val view = LiveOrderViewState.project(listOf(campaign), emptyMap(), emptySet()).getValue("BTCUSDT_LONG")

        // Then: the entry reads filled, the exit reads closed at target, the campaign is
        // completed, and nothing is offered for cancellation.
        assertEquals(LiveOrderStatus.FILLED, view.slices.single().entry.status)
        assertEquals(LiveOrderStatus.CLOSED_AT_TARGET, view.slices.single().takeProfit.status)
        assertEquals(CampaignState.COMPLETED, view.status)
        assertTrue(view.cancellable.isEmpty())
        assertEquals(tpId, view.slices.single().takeProfit.clientOrderId)
    }

    @Test
    fun `given the operator cancels a staged exit, when the cancel is routed, then it reaches the coordinator for that exact client id`() =
        runBlocking {
            // Given:
            val port = FakePort()
            val coordinator = LiveOrderCoordinator(port)
            val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 2)

            // When:
            val record = LiveOrderViewState.cancel(
                coordinator = coordinator,
                symbol = "BTCUSDT",
                clientOrderId = tpId,
                leg = LiveLeg.TAKE_PROFIT,
                apiKey = apiKey,
                secret = secret
            )

            // Then: the exact id was sent, and the UI records a cancellation.
            assertEquals(listOf(tpId), port.cancelled)
            assertEquals(tpId, record.clientOrderId)
            assertEquals(LiveLeg.TAKE_PROFIT, record.leg)
            assertEquals(LiveOrderStatus.CANCELLED, record.status)
        }

    @Test
    fun `given an exit that filled before the cancel, when the cancel is routed, then the UI reports a fill not a cancellation`() =
        runBlocking {
            // Given: the cancel is refused and the order is no longer on the book.
            val port = FakePort(
                cancelResult = BinanceSignedClient.OrderResult(
                    false, null, null, null, "Unknown order sent."
                ),
                openOrders = emptyList()
            )
            val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)

            // When:
            val record = LiveOrderViewState.cancel(
                coordinator = LiveOrderCoordinator(port),
                symbol = "BTCUSDT",
                clientOrderId = tpId,
                leg = LiveLeg.TAKE_PROFIT,
                apiKey = apiKey,
                secret = secret
            )

            // Then: a fill, stated as such — never a silent no-op or a cancel error.
            assertEquals(LiveOrderStatus.FILLED, record.status)
            assertEquals("Filled before cancellation", record.reason)
            assertEquals(tpId, record.clientOrderId)
        }

    @Test
    fun `given a rejected exit with a reason, when the live view is built, then the reason is shown against that id`() {
        // Given: the kill switch blocked staging, and the refusal was recorded by id.
        val campaign = campaign(slices = 1, status = CampaignState.PLANNED)
        val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)
        val entryId = ClientOrderIds.forSlice("BTCUSDT_LONG", 1)
        val records = mapOf(
            entryId to LiveOrderRecord(entryId, LiveLeg.ENTRY, LiveOrderStatus.REJECTED, "Kill-Switch engaged"),
            tpId to LiveOrderRecord(tpId, LiveLeg.TAKE_PROFIT, LiveOrderStatus.REJECTED, "Kill-Switch engaged")
        )

        // When:
        val view = LiveOrderViewState.project(listOf(campaign), records, emptySet()).getValue("BTCUSDT_LONG")

        // Then: each refused leg carries its reason, is not offered for cancellation and
        // does not read as resting.
        assertEquals(LiveOrderStatus.REJECTED, view.slices.single().takeProfit.status)
        assertEquals("Kill-Switch engaged", view.slices.single().takeProfit.reason)
        assertEquals("Kill-Switch engaged", view.slices.single().entry.reason)
        assertTrue(view.cancellable.isEmpty())
        assertNull(view.slices.single().entry.status.takeIf { it == LiveOrderStatus.RESTING })
    }

    /** A cancel that fails for a real reason leaves the order's status untouched. */
    @Test
    fun `given a cancel the venue refused, when the cancel is routed, then the reason is reported and no fill is claimed`() =
        runBlocking {
            // Given: the cancel fails while the order is still open.
            val port = FakePort(
                cancelResult = BinanceSignedClient.OrderResult(false, null, null, null, "Order would trigger immediately."),
                openOrders = listOf(
                    BinanceSignedClient.OpenOrder(
                        orderId = 3L,
                        symbol = "BTCUSDT",
                        clientOrderId = "BTCUSDT_LONG_1_tp",
                        price = 0.0,
                        origQty = 1.0,
                        executedQty = 0.0,
                        status = "NEW",
                        type = "TAKE_PROFIT_MARKET",
                        side = "SELL"
                    )
                )
            )

            // When:
            val record = LiveOrderViewState.cancel(
                coordinator = LiveOrderCoordinator(port),
                symbol = "BTCUSDT",
                clientOrderId = "BTCUSDT_LONG_1_tp",
                leg = LiveLeg.TAKE_PROFIT,
                apiKey = apiKey,
                secret = secret
            )

            // Then: not a fill, not a cancellation — the real reason.
            assertFalse(record.status == LiveOrderStatus.FILLED)
            assertFalse(record.status == LiveOrderStatus.CANCELLED)
            assertTrue(record.reason!!.contains("Order would trigger immediately."))
        }

    private fun campaign(
        slices: Int,
        filledSlices: Set<Int> = emptySet(),
        closedSlices: Set<Int> = emptySet(),
        status: CampaignState = CampaignState.STAGED
    ): Campaign {
        val ladder = (1..slices).map { i ->
            val filled = i in filledSlices
            LadderSlice(
                sliceIndex = i,
                price = 151.0 - i,
                qty = 10.0,
                notionalUsd = 1000.0,
                orderType = OrderType.PASSIVE_LIMIT,
                isFilled = filled,
                isResting = !filled,
                isClosedAtTarget = i in closedSlices
            )
        }
        return Campaign(
            id = "BTCUSDT_LONG",
            asset = "BTCUSDT",
            family = CampaignFamily.SUPPORT_LONG,
            regime = MarketRegime.BULLISH_CONTINUATION,
            role = BoardRole.PRIMARY,
            priorityScore = 80.0,
            sizeBudgetUsd = 1000.0,
            sizeMultiplier = 1.0,
            entryLadder = ladder,
            stopLogic = StopLogic(45, 70, 145.0),
            targets = listOf(TakeProfitTarget("Forecast 1h", 154.5, 50)),
            invalidationScore = 10,
            topReasons = emptyList(),
            status = status
        )
    }
}
