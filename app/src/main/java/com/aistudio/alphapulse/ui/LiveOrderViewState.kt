package com.aistudio.alphapulse.ui

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.engine.ClientOrderIds
import com.aistudio.alphapulse.engine.LiveOrderCoordinator
import com.aistudio.alphapulse.engine.OrderIntent

/** Which leg of a campaign slice a live order belongs to. */
enum class LiveLeg(val label: String) {
    ENTRY("Entry"),
    TAKE_PROFIT("Take-profit")
}

/**
 * Last known display status of one client order id.
 *
 * This is a record of what the coordinator reported — it is not trading state. The
 * campaign ladder stays authoritative for fills and closures; the record only adds
 * what the ladder cannot express: the client id, a rejection reason, or that a
 * cancel lost the race to a fill.
 */
enum class LiveOrderStatus(val label: String) {
    NOT_STAGED("Not staged"),
    STAGED("Staged"),
    RESTING("Resting"),
    FILLED("Filled"),
    CLOSED_AT_TARGET("Closed at TP"),
    CANCELLED("Cancelled"),
    CANCEL_FAILED("Cancel failed"),
    REJECTED("Rejected"),
    VANISHED("Vanished")
}

data class LiveOrderRecord(
    val clientOrderId: String,
    val leg: LiveLeg,
    val status: LiveOrderStatus,
    val reason: String? = null
)

/** One leg of one slice, as the campaign card renders it. */
data class LiveOrderView(
    val clientOrderId: String,
    val leg: LiveLeg,
    val status: LiveOrderStatus,
    val price: Double?,
    val reason: String?
) {
    val isCancellable: Boolean
        get() = status == LiveOrderStatus.STAGED || status == LiveOrderStatus.RESTING
}

data class SliceLiveView(
    val sliceIndex: Int,
    val entryPrice: Double,
    val entry: LiveOrderView,
    val takeProfit: LiveOrderView
)

data class CampaignLiveView(
    val campaignId: String,
    val asset: String,
    val status: CampaignState,
    val slices: List<SliceLiveView>,
    /** Client ids the operator may cancel right now. */
    val cancellable: Set<String>
)

/** Last reconcile outcome, so a stream gap is visible instead of implied. */
data class ReconcileOutcome(
    val symbol: String,
    val openCount: Int,
    val vanishedCount: Int,
    val closedAtTargetCount: Int,
    val atMillis: Long
) {
    val summary: String
        get() = buildString {
            append("$openCount open")
            if (vanishedCount > 0) append(" · $vanishedCount gone")
            if (closedAtTargetCount > 0) append(" · $closedAtTargetCount closed at TP")
        }
}

/**
 * Projects campaign ladders plus recorded coordinator results into the per-slice
 * live view. One function on purpose: the card has no state of its own, so what it
 * shows can always be traced back to a coordinator result or a ladder flag.
 */
object LiveOrderViewState {

    fun project(
        campaigns: List<Campaign>,
        records: Map<String, LiveOrderRecord>,
        openClientOrderIds: Set<String> = emptySet()
    ): Map<String, CampaignLiveView> = campaigns.associate { campaign ->
        val targetPrice = campaign.targets.firstOrNull()?.price
        val slices = campaign.entryLadder.map { slice ->
            val entryId = ClientOrderIds.forSlice(campaign.id, slice.sliceIndex)
            val tpId = ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex)
            SliceLiveView(
                sliceIndex = slice.sliceIndex,
                entryPrice = slice.price,
                entry = entryView(slice.price, entryId, records[entryId], openClientOrderIds, slice.isFilled, slice.isResting),
                takeProfit = exitView(
                    targetPrice,
                    tpId,
                    records[tpId],
                    openClientOrderIds,
                    slice.isClosedAtTarget
                )
            )
        }
        campaign.id to CampaignLiveView(
            campaignId = campaign.id,
            asset = campaign.asset,
            status = campaign.status,
            slices = slices,
            cancellable = slices.flatMap { listOf(it.entry, it.takeProfit) }
                .filter { it.isCancellable }
                .map { it.clientOrderId }
                .toSet()
        )
    }

    /**
     * The display record for one submission result. A venue rejection keeps its
     * message; an accepted order is staged until reconciliation confirms it rests.
     */
    fun recordFor(intent: OrderIntent, ok: Boolean, message: String?): LiveOrderRecord =
        LiveOrderRecord(
            clientOrderId = intent.clientOrderId,
            leg = if (intent.reduceOnly) LiveLeg.TAKE_PROFIT else LiveLeg.ENTRY,
            status = if (ok) LiveOrderStatus.STAGED else LiveOrderStatus.REJECTED,
            reason = if (ok) null else (message ?: "Rejected by venue")
        )

    /**
     * Routes one operator cancel through the existing coordinator and answers with
     * what the UI should show. A vanished order is a fill, never a silent no-op or a
     * cancel error; a refusal that leaves the order open is reported with its reason.
     */
    suspend fun cancel(
        coordinator: LiveOrderCoordinator,
        symbol: String,
        clientOrderId: String,
        leg: LiveLeg,
        apiKey: CharArray,
        secret: CharArray
    ): LiveOrderRecord = when (val outcome = coordinator.cancelOne(symbol, clientOrderId, apiKey, secret)) {
        is LiveOrderCoordinator.CancelOutcome.Cancelled ->
            LiveOrderRecord(clientOrderId, leg, LiveOrderStatus.CANCELLED, "Cancelled at venue")
        is LiveOrderCoordinator.CancelOutcome.AlreadyFilled ->
            LiveOrderRecord(clientOrderId, leg, LiveOrderStatus.FILLED, FILLED_BEFORE_CANCEL)
        is LiveOrderCoordinator.CancelOutcome.Failed ->
            LiveOrderRecord(
                clientOrderId,
                leg,
                LiveOrderStatus.CANCEL_FAILED,
                "Cancel refused: ${outcome.reason}"
            )
    }

    /**
     * Cancels a set of routed ids — the kill-switch sweep — with the same per-id
     * mapping as a single cancel, so an order that filled first stays a fill.
     */
    suspend fun cancelAll(
        coordinator: LiveOrderCoordinator,
        symbol: String,
        clientOrderIds: Collection<String>,
        apiKey: CharArray,
        secret: CharArray
    ): Map<String, LiveOrderRecord> = clientOrderIds.associate { clientOrderId ->
        clientOrderId to cancel(coordinator, symbol, clientOrderId, legOf(clientOrderId), apiKey, secret)
    }

    /**
     * The records a REST reconcile implies: what the venue still holds, what is gone,
     * and which exits closed at target. Anything the reconcile did not cover keeps the
     * record it already had, so a per-symbol reconcile never erases another symbol.
     */
    fun reconcileRecords(
        result: LiveOrderCoordinator.ReconcileResult,
        previous: Map<String, LiveOrderRecord> = emptyMap()
    ): Map<String, LiveOrderRecord> {
        val derived = buildMap {
            result.openClientOrderIds.forEach { id ->
                put(id, LiveOrderRecord(id, legOf(id), LiveOrderStatus.RESTING))
            }
            result.closedClientOrderIds.forEach { id ->
                put(
                    id,
                    LiveOrderRecord(
                        id,
                        LiveLeg.TAKE_PROFIT,
                        LiveOrderStatus.CLOSED_AT_TARGET,
                        "Take-profit filled at target"
                    )
                )
            }
            result.vanishedClientOrderIds.forEach { id ->
                put(id, LiveOrderRecord(id, legOf(id), LiveOrderStatus.VANISHED, VANISHED_NOT_RESTING))
            }
        }
        return previous + derived
    }

    const val FILLED_BEFORE_CANCEL = "Filled before cancellation"
    const val VANISHED_NOT_RESTING = "No longer resting at venue"

    /** Which leg a client order id belongs to, by the id scheme itself. */
    fun legOf(clientOrderId: String): LiveLeg =
        if (clientOrderId.endsWith("_tp")) LiveLeg.TAKE_PROFIT else LiveLeg.ENTRY

    private fun entryView(
        price: Double,
        id: String,
        record: LiveOrderRecord?,
        open: Set<String>,
        isFilled: Boolean,
        isResting: Boolean
    ): LiveOrderView {
        val status = when {
            isFilled || record?.status == LiveOrderStatus.FILLED -> LiveOrderStatus.FILLED
            // A successful cancel outranks a stale resting flag on the ladder.
            record?.status == LiveOrderStatus.CANCELLED -> LiveOrderStatus.CANCELLED
            record?.status == LiveOrderStatus.REJECTED -> LiveOrderStatus.REJECTED
            record?.status == LiveOrderStatus.VANISHED -> LiveOrderStatus.VANISHED
            id in open || isResting -> LiveOrderStatus.RESTING
            record?.status == LiveOrderStatus.STAGED || record?.status == LiveOrderStatus.RESTING ->
                LiveOrderStatus.STAGED
            else -> LiveOrderStatus.NOT_STAGED
        }
        return LiveOrderView(id, LiveLeg.ENTRY, status, price, record?.reason)
    }

    private fun exitView(
        targetPrice: Double?,
        id: String,
        record: LiveOrderRecord?,
        open: Set<String>,
        isClosedAtTarget: Boolean
    ): LiveOrderView {
        val status = when {
            isClosedAtTarget || record?.status == LiveOrderStatus.FILLED -> LiveOrderStatus.CLOSED_AT_TARGET
            record?.status == LiveOrderStatus.CANCELLED -> LiveOrderStatus.CANCELLED
            record?.status == LiveOrderStatus.REJECTED -> LiveOrderStatus.REJECTED
            record?.status == LiveOrderStatus.VANISHED -> LiveOrderStatus.VANISHED
            id in open -> LiveOrderStatus.RESTING
            record?.status == LiveOrderStatus.STAGED || record?.status == LiveOrderStatus.RESTING ->
                LiveOrderStatus.STAGED
            else -> LiveOrderStatus.NOT_STAGED
        }
        return LiveOrderView(id, LiveLeg.TAKE_PROFIT, status, targetPrice, record?.reason)
    }
}
