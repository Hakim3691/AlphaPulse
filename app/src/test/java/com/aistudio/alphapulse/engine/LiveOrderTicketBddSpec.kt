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
import com.aistudio.alphapulse.ui.LiveLeg
import com.aistudio.alphapulse.ui.LiveOrderStatus
import com.aistudio.alphapulse.ui.LiveOrderViewState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * One live ticket from staging to cancellation, at the coordinator / view-state
 * boundary the ViewModel drives: the signed request that actually leaves the app, the
 * order-state the operator sees, and the failure paths that must not invent an order.
 *
 * Spec Ref: §22 Order Staging, §27 Invalidation/Cancellation, §33 Guardrails
 */
class LiveOrderTicketBddSpec {

    private val apiKey = "key".toCharArray()
    private val secret = "secret".toCharArray()

    /** Everything the port was asked to send, and the venue's scripted answers. */
    private class RecordingPort(
        var placeResult: BinanceSignedClient.OrderResult =
            BinanceSignedClient.OrderResult(true, 42L, null, "NEW", null),
        var cancelResult: BinanceSignedClient.OrderResult =
            BinanceSignedClient.OrderResult(true, 42L, null, "CANCELED", null),
        var openOrderReadFails: Boolean = false,
        var openOrders: List<BinanceSignedClient.OpenOrder> = emptyList()
    ) : SignedOrderPort {
        data class Placed(
            val symbol: String,
            val side: String,
            val type: String,
            val qty: Double,
            val price: Double?,
            val stopPrice: Double?,
            val reduceOnly: Boolean,
            val clientOrderId: String
        )

        val placed = mutableListOf<Placed>()
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
            placed += Placed(symbol, side, orderType, qty, price, stopPrice, reduceOnly, clientOrderId)
            return placeResult
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
        ): List<BinanceSignedClient.OpenOrder> {
            if (openOrderReadFails) throw IOException("read timed out")
            return openOrders
        }

        override suspend fun createListenKey(apiKey: CharArray): String? = "listen-key"

        override suspend fun keepAliveListenKey(apiKey: CharArray): Boolean = true
    }

    @Test
    fun `given a staged slice, when the live entry is placed, then the signed request and the resting state both match the plan`() =
        runBlocking {
            // Given: one passive entry and its reduce-only take-profit.
            val campaign = campaign()
            val port = RecordingPort()
            val plan = OrderStager().plan(
                listOf(campaign), ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth(), true
            )
            val entry = plan.entries.single()

            // When: the entry is submitted through the coordinator.
            val submission = LiveOrderCoordinator(port).submit(plan, apiKey, secret)
                .single { it.intent.clientOrderId == entry.clientOrderId }

            // Then: exactly the planned leg reaches the venue, with the mapped side,
            // quantity and price, no reduce-only flag, and the stable client id.
            val sent = port.placed.single { it.clientOrderId == entry.clientOrderId }
            assertTrue(submission.ok)
            assertEquals("BTCUSDT", sent.symbol)
            assertEquals("BUY", sent.side)
            assertEquals("LIMIT", sent.type)
            assertEquals(10.0, sent.qty, 1e-9)
            assertEquals(150.0, sent.price!!, 1e-9)
            assertFalse(sent.reduceOnly)
            assertEquals(ClientOrderIds.forSlice("BTCUSDT_LONG", 1), sent.clientOrderId)

            val record = LiveOrderViewState.recordFor(entry, submission.ok, submission.message)
            val view = LiveOrderViewState.project(
                listOf(campaign), mapOf(sent.clientOrderId to record), setOf(sent.clientOrderId)
            ).getValue("BTCUSDT_LONG")
            assertEquals(LiveOrderStatus.STAGED, record.status)
            assertEquals(LiveLeg.ENTRY, record.leg)
            assertTrue(view.cancellable.contains(sent.clientOrderId))
        }

    @Test
    fun `given a resting entry, when the operator cancels it, then the cancel names that id and the ticket stops being cancellable`() =
        runBlocking {
            // Given: an entry the venue accepted.
            val campaign = campaign()
            val port = RecordingPort()
            val coordinator = LiveOrderCoordinator(port)
            val plan = OrderStager().plan(
                listOf(campaign), ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth(), true
            )
            val entry = plan.entries.single()
            val submission = coordinator.submit(plan, apiKey, secret)
                .single { it.intent.clientOrderId == entry.clientOrderId }
            val placed = LiveOrderViewState.recordFor(entry, submission.ok, submission.message)

            // When:
            val record = LiveOrderViewState.cancel(
                coordinator = coordinator,
                symbol = "BTCUSDT",
                clientOrderId = placed.clientOrderId,
                leg = placed.leg,
                apiKey = apiKey,
                secret = secret
            )

            // Then: the venue was asked to cancel that exact id, and the board shows a
            // cancellation with nothing left to cancel.
            assertEquals(listOf(placed.clientOrderId), port.cancelled)
            assertEquals(LiveOrderStatus.CANCELLED, record.status)
            val view = LiveOrderViewState.project(
                listOf(campaign), mapOf(placed.clientOrderId to record), emptySet()
            ).getValue("BTCUSDT_LONG")
            assertTrue(view.cancellable.isEmpty())
            assertEquals(LiveOrderStatus.CANCELLED, view.slices.single().entry.status)
        }

    @Test
    fun `given the venue rejects the entry, when it is submitted, then the reason is user visible and no phantom order is offered`() =
        runBlocking {
            // Given: the venue refuses the order outright.
            val campaign = campaign()
            val port = RecordingPort(
                placeResult = BinanceSignedClient.OrderResult(
                    false, null, null, null, "Margin is insufficient."
                )
            )
            val plan = OrderStager().plan(
                listOf(campaign), ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth(), true
            )
            val entry = plan.entries.single()

            // When:
            val submission = LiveOrderCoordinator(port).submit(plan, apiKey, secret)
                .single { it.intent.clientOrderId == entry.clientOrderId }
            val record = LiveOrderViewState.recordFor(entry, submission.ok, submission.message)
            val view = LiveOrderViewState.project(
                listOf(campaign), mapOf(record.clientOrderId to record), emptySet()
            ).getValue("BTCUSDT_LONG")

            // Then: the refusal is recorded against that id, is not cancellable, and
            // does not read as resting at the venue.
            assertFalse(submission.ok)
            assertEquals(LiveOrderStatus.REJECTED, record.status)
            assertTrue(record.reason!!.contains("Margin is insufficient."))
            assertTrue(view.cancellable.isEmpty())
            assertFalse(view.slices.single().entry.status == LiveOrderStatus.RESTING)
        }

    @Test
    fun `given the cancel is refused and the book cannot be read, when cancelled, then the ticket is not booked as a fill`() =
        runBlocking {
            // Given: the cancel is refused and the follow-up open-order read fails, so
            // the app genuinely does not know whether the order is still resting.
            val campaign = campaign()
            val port = RecordingPort(
                cancelResult = BinanceSignedClient.OrderResult(
                    false, null, null, null, "Unknown order sent."
                ),
                openOrderReadFails = true
            )

            // When:
            val record = LiveOrderViewState.cancel(
                coordinator = LiveOrderCoordinator(port),
                symbol = "BTCUSDT",
                clientOrderId = ClientOrderIds.forSlice("BTCUSDT_LONG", 1),
                leg = LiveLeg.ENTRY,
                apiKey = apiKey,
                secret = secret
            )

            // Then: an unreadable book is reported as an unresolved cancel, never as an
            // execution — claiming a fill would mark the slice filled and stop the
            // ladder from managing a position that may not exist.
            assertFalse(
                "A failed open-order read must not become a fill: ${record.status}",
                record.status == LiveOrderStatus.FILLED
            )
            assertEquals(LiveOrderStatus.CANCEL_FAILED, record.status)
            assertTrue(record.reason!!.contains("Unknown order sent."))
            val view = LiveOrderViewState.project(listOf(campaign), mapOf(record.clientOrderId to record), emptySet())
                .getValue("BTCUSDT_LONG")
            assertFalse(view.slices.single().entry.status == LiveOrderStatus.FILLED)
        }

    private fun campaign(): Campaign = Campaign(
        id = "BTCUSDT_LONG",
        asset = "BTCUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 80.0,
        sizeBudgetUsd = 1000.0,
        sizeMultiplier = 1.0,
        entryLadder = listOf(
            LadderSlice(
                sliceIndex = 1,
                price = 150.0,
                qty = 10.0,
                notionalUsd = 1000.0,
                orderType = OrderType.PASSIVE_LIMIT,
                isFilled = false,
                isResting = true
            )
        ),
        stopLogic = StopLogic(45, 70, 145.0),
        targets = listOf(TakeProfitTarget("Forecast 1h", 154.5, 50)),
        invalidationScore = 10,
        topReasons = emptyList(),
        status = CampaignState.STAGED
    )
}
