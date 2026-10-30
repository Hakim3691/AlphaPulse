package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.data.remote.BinanceSignedClient

/**
 * One concrete Binance order derived from a campaign ladder slice.
 *
 * A leg produces two of these: the entry that opens the slice, and a reduce-only
 * take-profit that closes it. [reduceOnly] tells them apart on the wire and in
 * reconciliation.
 */
data class OrderIntent(
    val symbol: String,
    val side: String,
    val type: String,
    val qty: Double,
    val price: Double?,
    val stopPrice: Double?,
    val reduceOnly: Boolean,
    val clientOrderId: String,
    val campaignId: String,
    val sliceIndex: Int,
    /** The campaign's first take-profit, priced from AlphaPulse's forecast ladder. */
    val takeProfitPrice: Double?
)

/** Why one leg was not staged. Recorded, never silently dropped (§33). */
data class StagingRejection(
    val campaignId: String,
    val sliceIndex: Int,
    val clientOrderId: String,
    val reason: String
)

/** Everything staging decided: what to submit, and what it refused and why. */
data class StagingPlan(
    val intents: List<OrderIntent>,
    val rejections: List<StagingRejection>
) {
    val isEmpty: Boolean get() = intents.isEmpty()

    /** Opening orders only. */
    val entries: List<OrderIntent> get() = intents.filter { !it.reduceOnly }

    /** Reduce-only take-profit exits. */
    val exits: List<OrderIntent> get() = intents.filter { it.reduceOnly }
}

/**
 * Deterministic client order ids.
 *
 * `<campaignId>_<sliceIndex>` for an entry and `<campaignId>_<sliceIndex>_tp` for its
 * take-profit. Both are stable for a given plan, so re-staging after a restart
 * resolves to the same ids and Binance refuses a duplicate (`-2010` duplicate client
 * order id) instead of silently opening a second position. Entry ids are unchanged
 * from the first released scheme, so nothing already resting on a restart is
 * re-issued under a different id.
 */
object ClientOrderIds {

    const val MAX_LENGTH = 36

    fun forSlice(campaignId: String, sliceIndex: Int): String {
        val raw = "${campaignId}_$sliceIndex"
        if (raw.length <= MAX_LENGTH) return raw
        val suffix = "_$sliceIndex"
        val prefix = campaignId.take(MAX_LENGTH - suffix.length)
        return "$prefix$suffix"
    }

    /** The reduce-only take-profit that closes the slice opened by [forSlice]. */
    fun forTakeProfit(campaignId: String, sliceIndex: Int): String {
        val raw = "${campaignId}_${sliceIndex}_tp"
        if (raw.length <= MAX_LENGTH) return raw
        val suffix = "_${sliceIndex}_tp"
        val prefix = campaignId.take(MAX_LENGTH - suffix.length)
        return "$prefix$suffix"
    }
}

/**
 * Turns planned campaigns into Binance order intents, subject to the existing
 * guardrails: one entry per resting slice plus one reduce-only take-profit priced at
 * the forecast target the campaign already carries.
 *
 * Nothing here talks to the network: [LiveOrderCoordinator] submits the plan. That
 * split is what makes the guardrail behaviour testable without an exchange.
 */
class OrderStager(private val riskEngine: RiskEngine = RiskEngine()) {

    /**
     * @param alreadyStaged client order ids already submitted this session — the
     *   idempotency set. A leg (entry or exit) whose id is present is skipped, so
     *   re-planning the same campaigns after a restart cannot double-fire.
     */
    fun plan(
        campaigns: List<Campaign>,
        mode: ExecutionMode,
        envelope: RiskEnvelope,
        venueHealth: VenueHealth,
        hasActiveSession: Boolean,
        alreadyStaged: Set<String> = emptySet()
    ): StagingPlan {
        val intents = mutableListOf<OrderIntent>()
        val rejections = mutableListOf<StagingRejection>()

        if (mode != ExecutionMode.CAPPED_LIVE && mode != ExecutionMode.SCALED_LIVE) {
            return StagingPlan(emptyList(), rejectAll(campaigns, "Mode ${mode.name} does not submit exchange orders"))
        }
        if (!hasActiveSession) {
            return StagingPlan(emptyList(), rejectAll(campaigns, "No active API session"))
        }
        if (ExecutionGuardrails.staleFeedAction(venueHealth) == ExecutionGuardrails.StaleFeedAction.SUPPRESS_AND_TRIM) {
            return StagingPlan(emptyList(), rejectAll(campaigns, "Data feed stale; staging suppressed"))
        }

        for (campaign in campaigns) {
            val (allowed, reason) = riskEngine.validateCampaignStaging(campaign, campaigns, envelope, venueHealth)
            if (!allowed) {
                campaign.entryLadder.forEach { slice ->
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        ClientOrderIds.forSlice(campaign.id, slice.sliceIndex),
                        reason ?: "Campaign rejected by risk engine"
                    )
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex),
                        reason ?: "Campaign rejected by risk engine"
                    )
                }
                continue
            }
            if (campaign.status == CampaignState.SUPPRESSED || campaign.status == CampaignState.CANCELLED) {
                campaign.entryLadder.forEach { slice ->
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        ClientOrderIds.forSlice(campaign.id, slice.sliceIndex),
                        "Campaign ${campaign.status.name} — not stageable"
                    )
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex),
                        "Campaign ${campaign.status.name} — not stageable"
                    )
                }
                continue
            }

            val takeProfitPrice = campaign.targets.firstOrNull()?.price
            for (slice in campaign.entryLadder) {
                val entryId = ClientOrderIds.forSlice(campaign.id, slice.sliceIndex)
                val tpId = ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex)
                if (slice.isFilled || !slice.isResting) continue

                val capScale = if (mode == ExecutionMode.CAPPED_LIVE && campaign.sizeBudgetUsd > 0.0) {
                    val cap = envelope.cappedNotionalPerCampaignUsd
                    (cap / campaign.sizeBudgetUsd).coerceIn(0.0, 1.0)
                } else 1.0
                val qty = slice.qty * capScale
                val side = if (campaign.isLong) "BUY" else "SELL"

                if (qty <= 0.0 || slice.price <= 0.0) {
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        entryId,
                        "Non-positive quantity or price (qty=$qty, price=${slice.price})"
                    )
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        tpId,
                        "Entry not stageable, so its take-profit is not staged"
                    )
                    continue
                }

                val entryStaged = entryId in alreadyStaged
                if (!entryStaged) {
                    intents += OrderIntent(
                        symbol = campaign.asset,
                        side = side,
                        type = binanceType(slice.orderType),
                        qty = qty,
                        price = slice.price,
                        stopPrice = if (slice.orderType == OrderType.PASSIVE_LIMIT) null else slice.price,
                        reduceOnly = false,
                        clientOrderId = entryId,
                        campaignId = campaign.id,
                        sliceIndex = slice.sliceIndex,
                        takeProfitPrice = takeProfitPrice
                    )
                }

                // The exit closes exactly what this slice opened. Staging the exit is
                // independent of the entry's id: an entry already resting from an
                // earlier run still needs its take-profit.
                if (tpId in alreadyStaged) continue
                if (takeProfitPrice == null || takeProfitPrice <= 0.0) {
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        tpId,
                        "No forecast take-profit target on the campaign plan"
                    )
                    continue
                }
                if (qty > slice.qty + QTY_EPSILON) {
                    rejections += rejection(
                        campaign,
                        slice.sliceIndex,
                        tpId,
                        "Reduce-only quantity $qty exceeds the staged entry size ${slice.qty}"
                    )
                    continue
                }
                intents += OrderIntent(
                    symbol = campaign.asset,
                    side = if (campaign.isLong) "SELL" else "BUY",
                    type = TAKE_PROFIT_MARKET,
                    qty = qty,
                    price = null,
                    // TAKE_PROFIT_MARKET triggers on stopPrice; the forecast target is
                    // the trigger, so the exit cannot be left behind by a fast move.
                    stopPrice = takeProfitPrice,
                    reduceOnly = true,
                    clientOrderId = tpId,
                    campaignId = campaign.id,
                    sliceIndex = slice.sliceIndex,
                    takeProfitPrice = takeProfitPrice
                )
            }
        }
        return StagingPlan(intents, rejections)
    }

    private fun rejectAll(campaigns: List<Campaign>, reason: String): List<StagingRejection> =
        campaigns.flatMap { campaign ->
            campaign.entryLadder.flatMap { slice ->
                listOf(
                    rejection(campaign, slice.sliceIndex, ClientOrderIds.forSlice(campaign.id, slice.sliceIndex), reason),
                    rejection(campaign, slice.sliceIndex, ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex), reason)
                )
            }
        }

    private fun binanceType(orderType: OrderType): String = when (orderType) {
        OrderType.PASSIVE_LIMIT -> "LIMIT"
        OrderType.STOP_LIMIT -> "STOP"
        OrderType.STOP_MARKET -> "STOP_MARKET"
    }

    private fun rejection(campaign: Campaign, sliceIndex: Int, clientOrderId: String, reason: String) =
        StagingRejection(campaign.id, sliceIndex, clientOrderId, reason)

    private companion object {
        const val TAKE_PROFIT_MARKET = "TAKE_PROFIT_MARKET"
        const val QTY_EPSILON = 1e-9
    }
}

/**
 * The exchange port. A narrow interface so staging/cancel logic is exercised
 * against a fake in tests and against [BinanceSignedClient] in production.
 */
interface SignedOrderPort {
    suspend fun placeOrder(
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
    ): BinanceSignedClient.OrderResult

    suspend fun cancelOrder(
        symbol: String,
        clientOrderId: String,
        apiKey: CharArray,
        secret: CharArray
    ): BinanceSignedClient.OrderResult

    suspend fun cancelAllOpenOrders(symbol: String, apiKey: CharArray, secret: CharArray): Boolean

    suspend fun fetchOpenOrders(
        symbol: String,
        apiKey: CharArray,
        secret: CharArray
    ): List<BinanceSignedClient.OpenOrder>

    suspend fun createListenKey(apiKey: CharArray): String?

    suspend fun keepAliveListenKey(apiKey: CharArray): Boolean
}

/** Production adapter: the real signed REST client, unchanged. */
object BinanceSignedOrderPort : SignedOrderPort {
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
    ) = BinanceSignedClient.placeOrder(
        symbol = symbol,
        side = side,
        orderType = orderType,
        qty = qty,
        price = price,
        stopPrice = stopPrice,
        reduceOnly = reduceOnly,
        clientOrderId = clientOrderId,
        apiKey = apiKey,
        secret = secret
    )

    override suspend fun cancelOrder(
        symbol: String,
        clientOrderId: String,
        apiKey: CharArray,
        secret: CharArray
    ) = BinanceSignedClient.cancelOrder(symbol, clientOrderId, apiKey, secret)

    override suspend fun cancelAllOpenOrders(symbol: String, apiKey: CharArray, secret: CharArray) =
        BinanceSignedClient.cancelAllOpenOrders(symbol, apiKey, secret)

    override suspend fun fetchOpenOrders(symbol: String, apiKey: CharArray, secret: CharArray) =
        BinanceSignedClient.fetchOpenOrders(symbol, apiKey, secret)

    override suspend fun createListenKey(apiKey: CharArray) = BinanceSignedClient.createListenKey(apiKey)

    override suspend fun keepAliveListenKey(apiKey: CharArray) = BinanceSignedClient.keepAliveListenKey(apiKey)
}
