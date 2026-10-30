package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.remote.BinanceSignedClient
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Submits, cancels and reconciles staged orders against the venue.
 *
 * Everything here is written against [SignedOrderPort], so the behaviour is proven
 * with a fake in tests and runs on [BinanceSignedOrderPort] in the app.
 *
 * Entry and take-profit order both travel this path: a take-profit is just another
 * reduce-only intent, so submit, cancel and reconcile need no second mechanism.
 *
 * Live concerns handled explicitly:
 * - retry with exponential backoff on rate-limit/timeout rejections (§33);
 * - the fill-beats-cancel race: a failed cancel is resolved against the open-order
 *   snapshot, so an order that filled first counts as a fill rather than a cancel
 *   error (§27);
 * - post-gap reconciliation from REST, because the user-data stream cannot be
 *   trusted to have delivered every event after a listen-key drop (§25).
 */
class LiveOrderCoordinator(
    private val port: SignedOrderPort = BinanceSignedOrderPort,
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 250L,
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {

    /** Result of one submission attempt (final, after retries). */
    data class Submission(
        val intent: OrderIntent,
        val ok: Boolean,
        val orderId: Long?,
        val message: String?
    )

    /** Outcome of a cancel, with the fill race resolved. */
    sealed interface CancelOutcome {
        data class Cancelled(val clientOrderId: String, val orderId: Long?) : CancelOutcome
        data class AlreadyFilled(val clientOrderId: String) : CancelOutcome
        data class Failed(val clientOrderId: String, val reason: String) : CancelOutcome
    }

    /** Campaign state recovered from REST, plus the ids the venue still holds. */
    data class ReconcileResult(
        val campaigns: List<Campaign>,
        val openClientOrderIds: Set<String>,
        val vanishedClientOrderIds: Set<String>,
        /** Take-profit ids resolved as filled, closing their slice at target. */
        val closedClientOrderIds: Set<String>
    )

    /**
     * Submits every intent in [plan] — entries and take-profits alike. A plan is
     * already idempotency-filtered by [OrderStager]; this only handles transport and
     * retries.
     */
    suspend fun submit(
        plan: StagingPlan,
        apiKey: CharArray,
        secret: CharArray
    ): List<Submission> = plan.intents.map { intent ->
        var attempt = 0
        var result = BinanceSignedClient.OrderResult.failure("not attempted")
        while (attempt < maxAttempts) {
            result = port.placeOrder(
                symbol = intent.symbol,
                side = intent.side,
                orderType = intent.type,
                qty = intent.qty,
                price = intent.price,
                stopPrice = intent.stopPrice,
                reduceOnly = intent.reduceOnly,
                clientOrderId = intent.clientOrderId,
                apiKey = apiKey,
                secret = secret
            )
            if (result.ok || !isRetryable(result.message)) break
            attempt++
            if (attempt < maxAttempts) sleeper(backoffDelay(attempt))
        }
        Submission(intent, result.ok, result.orderId, result.message)
    }

    /**
     * Cancels one staged order — entry or take-profit — by client order id.
     *
     * If the cancel is refused because the order is gone, the open-order snapshot
     * decides: absent means it filled first, and that is reported as a fill.
     */
    suspend fun cancelOne(
        symbol: String,
        clientOrderId: String,
        apiKey: CharArray,
        secret: CharArray
    ): CancelOutcome {
        val result = port.cancelOrder(symbol, clientOrderId, apiKey, secret)
        if (result.ok) return CancelOutcome.Cancelled(clientOrderId, result.orderId)

        // The order may have filled (or been cancelled elsewhere) before we asked.
        val stillOpen = runCatching { port.fetchOpenOrders(symbol, apiKey, secret) }
            .getOrDefault(emptyList())
            .any { it.clientOrderId == clientOrderId }
        return if (stillOpen) {
            CancelOutcome.Failed(clientOrderId, result.message ?: "cancel refused")
        } else {
            CancelOutcome.AlreadyFilled(clientOrderId)
        }
    }

    /** Cancels every order the venue holds for one symbol, entries and exits. */
    suspend fun cancelSymbol(symbol: String, apiKey: CharArray, secret: CharArray): Boolean {
        var attempt = 0
        while (attempt < maxAttempts) {
            val ok = runCatching { port.cancelAllOpenOrders(symbol, apiKey, secret) }.getOrDefault(false)
            if (ok) return true
            attempt++
            if (attempt < maxAttempts) sleeper(backoffDelay(attempt))
        }
        return false
    }

    /**
     * Recovers order state from REST — the path used after a listen-key gap, or when
     * a cancel result was ambiguous.
     *
     * A slice whose entry is still open stays resting. A slice whose entry vanished
     * stops resting so the campaign engine no longer assumes it is pending. A
     * take-profit that was staged and has vanished is a close at target: the slice is
     * marked filled, and a campaign whose filled slices are all closed leaves `STAGED`
     * for `COMPLETED`.
     *
     * @param staged client order ids this session submitted; a vanished id is only
     *   read as executed when it is in this set.
     */
    suspend fun reconcile(
        symbol: String,
        campaigns: List<Campaign>,
        apiKey: CharArray,
        secret: CharArray,
        staged: Set<String> = emptySet()
    ): ReconcileResult {
        val openOrders = runCatching { port.fetchOpenOrders(symbol, apiKey, secret) }
            .getOrDefault(emptyList())
        val byClientId = openOrders.associateBy { it.clientOrderId }
        val vanished = mutableSetOf<String>()
        val closed = mutableSetOf<String>()

        val updated = campaigns.map { campaign ->
            if (campaign.asset != symbol) return@map campaign
            var closedAtTarget = 0
            val ladder = campaign.entryLadder.map { slice ->
                val entryId = ClientOrderIds.forSlice(campaign.id, slice.sliceIndex)
                val tpId = ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex)
                val entryOpen = byClientId[entryId]
                val tpOpen = byClientId[tpId]
                val entryFilled = slice.isFilled || (entryOpen?.executedQty ?: 0.0) > 0.0

                when {
                    // Exit still resting at the venue: the position stays open.
                    tpOpen != null -> slice.copy(isFilled = entryFilled, isResting = false)

                    // The exit I staged is gone from the book: the position closed at
                    // target. Fill-beats-cancel applies here too — absence is a fill.
                    // A slice already marked closed is left alone, so a second gap
                    // does not re-report the same exit.
                    tpId in staged && entryFilled && !slice.isClosedAtTarget -> {
                        closed += tpId
                        closedAtTarget++
                        slice.copy(isFilled = true, isResting = false, isClosedAtTarget = true)
                    }

                    // Entry still resting.
                    entryOpen != null -> slice.copy(
                        isResting = true,
                        isFilled = entryFilled
                    )

                    // Entry vanished while it was still meant to rest.
                    slice.isResting && !slice.isFilled -> {
                        vanished += entryId
                        slice.copy(isResting = false)
                    }

                    else -> slice
                }
            }
            val filledSlices = ladder.filter { it.isFilled }
            val status = when {
                filledSlices.isEmpty() -> campaign.status
                filledSlices.all { it.isClosedAtTarget } -> CampaignState.COMPLETED
                campaign.status == CampaignState.STAGED -> CampaignState.PARTIALLY_FILLED
                else -> campaign.status
            }
            campaign.copy(entryLadder = ladder, status = status)
        }
        return ReconcileResult(
            campaigns = updated,
            openClientOrderIds = byClientId.keys,
            vanishedClientOrderIds = vanished,
            closedClientOrderIds = closed
        )
    }

    /** Creates a listen key when the session has none (or the last one expired). */
    suspend fun ensureListenKey(apiKey: CharArray): String? =
        runCatching { port.createListenKey(apiKey) }.getOrNull()

    /** Keeps the user-data stream alive; called well inside Binance's 60-min window. */
    suspend fun keepAlive(apiKey: CharArray): Boolean =
        runCatching { port.keepAliveListenKey(apiKey) }.getOrDefault(false)

    /** Exponential backoff with a hard ceiling, so a throttled venue is not hammered. */
    fun backoffDelay(attempt: Int): Long =
        min(baseDelayMs shl (attempt - 1).coerceAtLeast(0), MAX_BACKOFF_MS)

    private fun isRetryable(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return RETRYABLE_MARKERS.any { it in text }
    }

    private companion object {
        const val MAX_BACKOFF_MS = 4_000L
        val RETRYABLE_MARKERS = listOf(
            "rate limit",
            "too many requests",
            "-1003",
            "-1021",
            "timeout",
            "timed out",
            "temporarily unavailable"
        )
    }
}
