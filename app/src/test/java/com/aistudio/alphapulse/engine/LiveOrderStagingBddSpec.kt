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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live order staging, cancellation and reconciliation, specified as Given/When/Then.
 *
 * Every scenario runs against [FakeOrderPort]; no test touches the network.
 *
 * Spec Ref: §22 Order Staging, §25 Monitoring, §27 Invalidation/Cancellation, §33 Guardrails
 */
class LiveOrderStagingBddSpec {

    private val apiKey = "key".toCharArray()
    private val secret = "secret".toCharArray()

    /** Records every call and replays scripted results. */
    private class FakeOrderPort(
        var placeResults: MutableList<BinanceSignedClient.OrderResult> = mutableListOf(),
        var cancelResult: BinanceSignedClient.OrderResult = ok(),
        var openOrders: List<BinanceSignedClient.OpenOrder> = emptyList(),
        var cancelAllOk: Boolean = true
    ) : SignedOrderPort {
        val placed = mutableListOf<OrderIntent>()
        val cancelled = mutableListOf<String>()
        var cancelAllCalls = 0
        var fetchCalls = 0

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
            placed += OrderIntent(
                symbol = symbol,
                side = side,
                type = orderType,
                qty = qty,
                price = price,
                stopPrice = stopPrice,
                reduceOnly = reduceOnly,
                clientOrderId = clientOrderId,
                campaignId = clientOrderId.substringBeforeLast('_'),
                sliceIndex = clientOrderId.replace("_tp", "").substringAfterLast('_').toIntOrNull() ?: 0,
                takeProfitPrice = stopPrice
            )
            return if (placeResults.isNotEmpty()) placeResults.removeAt(0) else ok()
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

        override suspend fun cancelAllOpenOrders(
            symbol: String,
            apiKey: CharArray,
            secret: CharArray
        ): Boolean {
            cancelAllCalls++
            return cancelAllOk
        }

        override suspend fun fetchOpenOrders(
            symbol: String,
            apiKey: CharArray,
            secret: CharArray
        ): List<BinanceSignedClient.OpenOrder> {
            fetchCalls++
            return openOrders
        }

        override suspend fun createListenKey(apiKey: CharArray): String? = "listen-key"

        override suspend fun keepAliveListenKey(apiKey: CharArray): Boolean = true

        companion object {
            fun ok(orderId: Long = 1L) =
                BinanceSignedClient.OrderResult(true, orderId, null, "NEW", null)

            fun failed(message: String) =
                BinanceSignedClient.OrderResult(false, null, null, null, message)
        }
    }

    @Test
    fun `given a staged plan, when it is submitted, then each leg carries an entry and a reduce-only take-profit at the forecast target`() =
        runBlocking {
            // Given: a three-slice campaign whose forecast take-profit is 154.5.
            val campaign = campaign(slices = 3, takeProfitPrice = 154.5, budget = 1000.0)
            val stager = OrderStager()
            val port = FakeOrderPort()

            // When:
            val plan = stager.plan(
                listOf(campaign),
                ExecutionMode.SCALED_LIVE,
                RiskEnvelope(),
                VenueHealth(),
                hasActiveSession = true
            )
            val submissions = LiveOrderCoordinator(port).submit(plan, apiKey, secret)

            // Then: three entries open the slices and three reduce-only exits close them.
            assertEquals(3, plan.entries.size)
            assertEquals(3, plan.exits.size)
            assertEquals(6, submissions.size)
            assertTrue(submissions.all { it.ok })
            assertTrue(plan.exits.all { it.reduceOnly })
            assertTrue(plan.entries.none { it.reduceOnly })
            assertTrue(plan.exits.all { it.stopPrice == 154.5 })
            assertEquals("TAKE_PROFIT_MARKET", plan.exits.first().type)
            assertEquals(
                listOf(
                    "BTCUSDT_LONG_2_tp", "BTCUSDT_LONG_1_tp", "BTCUSDT_LONG_3_tp"
                ).sorted(),
                plan.exits.map { it.clientOrderId }.sorted()
            )
            // The exit side is opposite the entry: a long slice exits by selling.
            assertTrue(plan.exits.all { it.side == "SELL" })
            assertEquals(listOf(150.0, 149.0, 148.0), plan.entries.map { it.price })
        }

    @Test
    fun `given a plan with entries and take-profits already staged, when re-planned, then ids are identical and nothing new is submitted`() {
        // Given:
        val campaign = campaign(slices = 3, budget = 1000.0)
        val stager = OrderStager()

        // When:
        val first = stager.plan(
            listOf(campaign), ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth(), true
        )
        val second = stager.plan(
            listOf(campaign),
            ExecutionMode.SCALED_LIVE,
            RiskEnvelope(),
            VenueHealth(),
            true,
            alreadyStaged = first.intents.map { it.clientOrderId }.toSet()
        )

        // Then: entry ids are unchanged from the first scheme, exits get their own ids,
        // and re-staging is a no-op for both.
        assertEquals(
            listOf("BTCUSDT_LONG_1", "BTCUSDT_LONG_1_tp", "BTCUSDT_LONG_2", "BTCUSDT_LONG_2_tp",
                "BTCUSDT_LONG_3", "BTCUSDT_LONG_3_tp"),
            first.intents.map { it.clientOrderId }.sorted()
        )
        assertTrue(second.intents.isEmpty())
        assertTrue(second.rejections.isEmpty())
    }

    @Test
    fun `given an engaged kill switch, when staging runs, then neither entries nor take-profits are submitted`() =
        runBlocking {
            // Given:
            val campaign = campaign(slices = 2, budget = 1000.0)
            val port = FakeOrderPort()
            val plan = OrderStager().plan(
                listOf(campaign),
                ExecutionMode.SCALED_LIVE,
                RiskEnvelope(isKillSwitchEngaged = true),
                VenueHealth(),
                hasActiveSession = true
            )

            // When:
            LiveOrderCoordinator(port).submit(plan, apiKey, secret)

            // Then: blocked before the venue, with a recorded reason per leg, exits included.
            assertTrue(plan.intents.isEmpty())
            assertEquals(4, plan.rejections.size)
            assertTrue(plan.rejections.all { it.reason.contains("Kill-Switch") })
            assertTrue(plan.rejections.any { it.clientOrderId.endsWith("_tp") })
            assertEquals(0, port.placed.size)
        }

    @Test
    fun `given no forecast take-profit target, when staging runs, then the exit is rejected with a reason and only the entry is submitted`() =
        runBlocking {
            // Given: the plan carries no target, so there is no exit price to stage.
            val campaign = campaign(slices = 2, budget = 1000.0, targets = emptyList())
            val port = FakeOrderPort()
            val plan = OrderStager().plan(
                listOf(campaign),
                ExecutionMode.SCALED_LIVE,
                RiskEnvelope(),
                VenueHealth(),
                hasActiveSession = true
            )

            // When:
            LiveOrderCoordinator(port).submit(plan, apiKey, secret)

            // Then: entries still stage, both exits record why they did not.
            assertEquals(2, plan.entries.size)
            assertTrue(plan.exits.isEmpty())
            assertEquals(2, plan.rejections.size)
            assertTrue(plan.rejections.all { it.clientOrderId.endsWith("_tp") })
            assertTrue(plan.rejections.all { it.reason.contains("take-profit") })
            assertEquals(2, port.placed.size)
            assertTrue(port.placed.none { it.reduceOnly })
        }

    @Test
    fun `given a staged order, when cancelled by client id, then the single-order endpoint is used`() =
        runBlocking {
            // Given:
            val port = FakeOrderPort(cancelResult = FakeOrderPort.ok(7L))

            // When:
            val outcome = LiveOrderCoordinator(port).cancelOne("BTCUSDT", "BTCUSDT_LONG_2", apiKey, secret)

            // Then:
            assertTrue(outcome is LiveOrderCoordinator.CancelOutcome.Cancelled)
            assertEquals(listOf("BTCUSDT_LONG_2"), port.cancelled)
        }

    @Test
    fun `given a staged take-profit, when cancelled by client id, then that exit order is cancelled`() =
        runBlocking {
            // Given:
            val port = FakeOrderPort(cancelResult = FakeOrderPort.ok(9L))

            // When:
            val outcome = LiveOrderCoordinator(port)
                .cancelOne("BTCUSDT", "BTCUSDT_LONG_1_tp", apiKey, secret)

            // Then:
            assertTrue(outcome is LiveOrderCoordinator.CancelOutcome.Cancelled)
            assertEquals(listOf("BTCUSDT_LONG_1_tp"), port.cancelled)
        }

    @Test
    fun `given a symbol with resting entries and exits, when cancelled, then the symbol-wide endpoint is used`() =
        runBlocking {
            // Given:
            val port = FakeOrderPort(cancelAllOk = true)

            // When:
            val ok = LiveOrderCoordinator(port).cancelSymbol("BTCUSDT", apiKey, secret)

            // Then:
            assertTrue(ok)
            assertEquals(1, port.cancelAllCalls)
        }

    @Test
    fun `given an order that filled before the cancel, when cancelled, then it resolves as a fill`() =
        runBlocking {
            // Given: the cancel is refused and the order is no longer open.
            val port = FakeOrderPort(
                cancelResult = FakeOrderPort.failed("Unknown order sent."),
                openOrders = emptyList()
            )

            // When:
            val outcome = LiveOrderCoordinator(port).cancelOne("BTCUSDT", "BTCUSDT_LONG_1", apiKey, secret)

            // Then: not a cancel failure — the order filled first.
            assertTrue(outcome is LiveOrderCoordinator.CancelOutcome.AlreadyFilled)
            assertEquals(1, port.fetchCalls)
        }

    @Test
    fun `given an exit that filled before the cancel, when cancelled, then it also resolves as a fill`() =
        runBlocking {
            // Given:
            val port = FakeOrderPort(
                cancelResult = FakeOrderPort.failed("Unknown order sent."),
                openOrders = emptyList()
            )

            // When:
            val outcome = LiveOrderCoordinator(port)
                .cancelOne("BTCUSDT", "BTCUSDT_LONG_1_tp", apiKey, secret)

            // Then: the fill-beats-cancel rule is identical for an exit.
            assertTrue(outcome is LiveOrderCoordinator.CancelOutcome.AlreadyFilled)
        }

    @Test
    fun `given a listen-key gap with a filled order, when reconciled from REST, then the campaign stops assuming it rests`() =
        runBlocking {
            // Given: three slices were resting; the venue now reports one open and two gone.
            val campaign = campaign(slices = 3, budget = 1000.0)
            val port = FakeOrderPort(
                openOrders = listOf(
                    BinanceSignedClient.OpenOrder(
                        orderId = 11L,
                        symbol = "BTCUSDT",
                        clientOrderId = "BTCUSDT_LONG_2",
                        price = 149.0,
                        origQty = 1.0,
                        executedQty = 0.4,
                        status = "PARTIALLY_FILLED",
                        type = "LIMIT",
                        side = "BUY"
                    )
                )
            )

            // When:
            val result = LiveOrderCoordinator(port).reconcile("BTCUSDT", listOf(campaign), apiKey, secret)

            // Then: the open slice keeps resting and records its fill; vanished slices stop resting.
            val reconciled = result.campaigns.single()
            assertEquals(setOf("BTCUSDT_LONG_2"), result.openClientOrderIds)
            assertEquals(setOf("BTCUSDT_LONG_1", "BTCUSDT_LONG_3"), result.vanishedClientOrderIds)
            assertTrue(reconciled.entryLadder.first { it.sliceIndex == 2 }.isFilled)
            assertFalse(reconciled.entryLadder.first { it.sliceIndex == 1 }.isResting)
            assertFalse(reconciled.entryLadder.first { it.sliceIndex == 3 }.isResting)
        }

    @Test
    fun `given a filled take-profit, when reconciled from REST, then the position closes and the campaign leaves STAGED`() =
        runBlocking {
            // Given: slice 1 filled and its reduce-only exit was staged, then vanished
            // from the book — the position closed at the forecast target.
            val campaign = campaign(
                slices = 1,
                budget = 1000.0,
                filledSlices = setOf(1),
                status = CampaignState.STAGED
            )
            val port = FakeOrderPort(openOrders = emptyList())
            val tpId = ClientOrderIds.forTakeProfit("BTCUSDT_LONG", 1)
            assertEquals("BTCUSDT_LONG_1_tp", tpId)

            // When:
            val result = LiveOrderCoordinator(port).reconcile(
                symbol = "BTCUSDT",
                campaigns = listOf(campaign),
                apiKey = apiKey,
                secret = secret,
                staged = setOf(tpId)
            )

            // Then: the exit counts as filled, the slice is closed, not resting, and the
            // campaign is completed rather than still staged.
            val reconciled = result.campaigns.single()
            assertEquals(setOf(tpId), result.closedClientOrderIds)
            assertEquals(CampaignState.COMPLETED, reconciled.status)
            assertTrue(reconciled.entryLadder.single().isFilled)
            assertFalse(reconciled.entryLadder.single().isResting)
            assertTrue(reconciled.entryLadder.single().isClosedAtTarget)
        }

    @Test
    fun `given a rate-limit rejection, when submitting, then it backs off and retries`() =
        runBlocking {
            // Given: the first attempt is throttled, the second succeeds.
            val port = FakeOrderPort(
                placeResults = mutableListOf(
                    FakeOrderPort.failed("Too many requests; rate limit exceeded (-1003)")
                )
            )
            val delays = mutableListOf<Long>()
            val coordinator = LiveOrderCoordinator(port, baseDelayMs = 10L, sleeper = { delays += it })
            val campaign = campaign(slices = 1, budget = 1000.0)
            val plan = OrderStager().plan(
                listOf(campaign), ExecutionMode.SCALED_LIVE, RiskEnvelope(), VenueHealth(), true
            )

            // When:
            val submissions = coordinator.submit(plan, apiKey, secret)

            // Then: retried once with backoff and ultimately accepted.
            assertTrue(submissions.all { it.ok })
            assertEquals(1, delays.size)
            assertEquals(10L, delays.single())
        }

    private fun campaign(
        slices: Int,
        takeProfitPrice: Double = 154.5,
        budget: Double,
        targets: List<TakeProfitTarget>? = null,
        filledSlices: Set<Int> = emptySet(),
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
                isResting = !filled
            )
        }
        return Campaign(
            id = "BTCUSDT_LONG",
            asset = "BTCUSDT",
            family = CampaignFamily.SUPPORT_LONG,
            regime = MarketRegime.BULLISH_CONTINUATION,
            role = BoardRole.PRIMARY,
            priorityScore = 80.0,
            sizeBudgetUsd = budget,
            sizeMultiplier = 1.0,
            entryLadder = ladder,
            stopLogic = StopLogic(45, 70, 145.0),
            targets = targets ?: listOf(TakeProfitTarget("Forecast 1h", takeProfitPrice, 50)),
            invalidationScore = 10,
            topReasons = emptyList(),
            status = status
        )
    }
}
