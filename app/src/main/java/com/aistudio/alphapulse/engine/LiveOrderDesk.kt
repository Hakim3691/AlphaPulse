package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import com.aistudio.alphapulse.ui.LiveLeg
import com.aistudio.alphapulse.ui.LiveOrderRecord
import com.aistudio.alphapulse.ui.LiveOrderStatus
import com.aistudio.alphapulse.ui.LiveOrderViewState
import java.util.Arrays

/**
 * The live order wiring a board goes through: stage the ladder, cancel what is staged,
 * and record what the venue answered.
 *
 * This is the sequencing that used to live inside the ViewModel, lifted out unchanged
 * so it can be driven end to end against a [SignedOrderPort] fake. It composes the
 * pieces that already exist — [OrderStager] decides, [LiveOrderCoordinator] talks to
 * the venue, [LiveOrderViewState] maps outcomes to board records — and owns only the
 * per-session bookkeeping none of them can: which client ids the venue holds, and the
 * display record for each.
 *
 * Nothing here reaches the network directly; the coordinator's port does.
 */
class LiveOrderDesk(
    private val coordinator: LiveOrderCoordinator = LiveOrderCoordinator(BinanceSignedOrderPort),
    private val stager: OrderStager = OrderStager(),
    private val hasActiveSession: () -> Boolean,
    private val credentials: () -> Pair<CharArray, CharArray>?,
    private val campaignsOf: () -> List<Campaign>,
    private val applyCampaign: (Campaign) -> Unit,
    private val note: suspend (campaignId: String, code: String, message: String) -> Unit,
    private val onRejectStreak: (symbol: String, rejects: Int) -> Unit,
    private val publish: () -> Unit
) {

    /** Client order ids this session submitted and the venue still holds. */
    val routedClientIds: MutableSet<String> = linkedSetOf()

    /** Display records for staged orders, keyed by client order id. */
    val liveOrderRecords: MutableMap<String, LiveOrderRecord> = linkedMapOf()

    /**
     * §22/§36: stages and routes resting slices for live modes with a valid session.
     *
     * Nothing is submitted for a board the guardrails refuse: each refusal is recorded
     * against its client id instead, so a blocked slice is visible rather than silent.
     */
    suspend fun route(
        boards: List<Campaign>,
        mode: ExecutionMode,
        envelope: RiskEnvelope,
        venueHealth: VenueHealth
    ) {
        if (mode != ExecutionMode.CAPPED_LIVE && mode != ExecutionMode.SCALED_LIVE) return
        if (!hasActiveSession()) return
        val toRoute = boards.filter { it.status == CampaignState.STAGED }
        if (toRoute.isEmpty()) return

        val plan = stager.plan(
            campaigns = toRoute,
            mode = mode,
            envelope = envelope,
            venueHealth = venueHealth,
            hasActiveSession = true,
            alreadyStaged = routedClientIds
        )
        // §33: a guardrail refusal is a recorded decision, never a silent drop.
        for (rejection in plan.rejections) {
            liveOrderRecords[rejection.clientOrderId] = LiveOrderRecord(
                clientOrderId = rejection.clientOrderId,
                leg = LiveOrderViewState.legOf(rejection.clientOrderId),
                status = LiveOrderStatus.REJECTED,
                reason = rejection.reason
            )
            note(
                rejection.campaignId,
                "GUARDRAIL_BLOCKED",
                "slice ${rejection.sliceIndex} not staged: ${rejection.reason}"
            )
        }
        if (plan.isEmpty) return

        val creds = credentials() ?: return
        // §33: the pause a reject streak triggers is per symbol, so a refusal is
        // counted against the symbol that was refused — never the first board's.
        val rejectsBySymbol = toRoute.associate { it.asset to 0 }.toMutableMap()
        try {
            val submissions = coordinator.submit(plan, creds.first, creds.second)
            for (submission in submissions) {
                val intent = submission.intent
                liveOrderRecords[intent.clientOrderId] =
                    LiveOrderViewState.recordFor(intent, submission.ok, submission.message)
                if (submission.ok) {
                    routedClientIds += intent.clientOrderId
                    note(
                        intent.campaignId,
                        "ORDER_PLACED",
                        "${intent.side} slice ${intent.sliceIndex} routed at ${intent.price} " +
                            "(client ${intent.clientOrderId}, TP ${intent.takeProfitPrice})"
                    )
                } else {
                    rejectsBySymbol[intent.symbol] = (rejectsBySymbol[intent.symbol] ?: 0) + 1
                    note(
                        intent.campaignId,
                        "ORDER_REJECTED",
                        "slice ${intent.sliceIndex} rejected: ${submission.message ?: "unknown"}"
                    )
                }
            }
        } finally {
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
        }
        publish()
        rejectsBySymbol.forEach { (symbol, rejects) -> onRejectStreak(symbol, rejects) }
    }

    /**
     * §27: cancels this campaign's staged orders, resolving the fill-beats-cancel race.
     *
     * Only legs the venue can actually hold are cancelled: a resting entry, and a
     * take-profit this session staged. A slice already filled, or one whose orders are
     * no longer resting, is left alone rather than cancelled blind.
     */
    suspend fun cancelStaged(campaign: Campaign) {
        if (!hasActiveSession()) return
        val creds = credentials() ?: return
        try {
            for (slice in campaign.entryLadder.filter { !it.isFilled }) {
                val entryId = ClientOrderIds.forSlice(campaign.id, slice.sliceIndex)
                val tpId = ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex)
                val legs = buildList {
                    if (slice.isResting) add(entryId to LiveLeg.ENTRY)
                    // Only an exit we actually staged is worth cancelling.
                    if (tpId in routedClientIds) add(tpId to LiveLeg.TAKE_PROFIT)
                }
                for ((clientId, leg) in legs) {
                    when (val outcome = coordinator.cancelOne(campaign.asset, clientId, creds.first, creds.second)) {
                        is LiveOrderCoordinator.CancelOutcome.Cancelled -> {
                            routedClientIds -= clientId
                            liveOrderRecords[clientId] =
                                LiveOrderRecord(clientId, leg, LiveOrderStatus.CANCELLED, "Cancelled at venue")
                            note(campaign.id, "ORDER_CANCELLED", "$clientId cancelled at venue")
                        }
                        is LiveOrderCoordinator.CancelOutcome.AlreadyFilled -> {
                            routedClientIds -= clientId
                            liveOrderRecords[clientId] = LiveOrderRecord(
                                clientId,
                                leg,
                                LiveOrderStatus.FILLED,
                                LiveOrderViewState.FILLED_BEFORE_CANCEL
                            )
                            note(campaign.id, "FILL_BEATS_CANCEL", "$clientId filled before cancellation")
                        }
                        is LiveOrderCoordinator.CancelOutcome.Failed -> {
                            liveOrderRecords[clientId] = LiveOrderRecord(
                                clientId,
                                leg,
                                LiveOrderStatus.CANCEL_FAILED,
                                "Cancel refused: ${outcome.reason}"
                            )
                            note(campaign.id, "ORDER_CANCEL_FAILED", "$clientId: ${outcome.reason}")
                        }
                    }
                }
            }
        } finally {
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
            publish()
        }
    }

    /**
     * §27: operator cancel for one staged order from the campaign card.
     *
     * A cancel that loses the race to a fill is booked as a fill — the slice becomes
     * filled and the position is the ladder's problem — never as a cancellation.
     */
    suspend fun cancelLiveOrder(clientOrderId: String) {
        val record = liveOrderRecords[clientOrderId]
            ?: LiveOrderRecord(clientOrderId, LiveOrderViewState.legOf(clientOrderId), LiveOrderStatus.STAGED)
        val campaign = campaignsOf().firstOrNull { clientOrderId.startsWith(it.id) } ?: return
        if (!hasActiveSession()) {
            note(campaign.id, "CANCEL_NO_SESSION", "Cannot cancel $clientOrderId: no API session")
            return
        }
        val creds = credentials() ?: return
        try {
            val outcome = LiveOrderViewState.cancel(
                coordinator = coordinator,
                symbol = campaign.asset,
                clientOrderId = clientOrderId,
                leg = record.leg,
                apiKey = creds.first,
                secret = creds.second
            )
            liveOrderRecords[clientOrderId] = outcome
            when (outcome.status) {
                LiveOrderStatus.CANCELLED -> {
                    routedClientIds -= clientOrderId
                    note(campaign.id, "ORDER_CANCELLED", "$clientOrderId cancelled at venue")
                }
                LiveOrderStatus.FILLED -> {
                    routedClientIds -= clientOrderId
                    markSliceFilled(campaign, clientOrderId, record.leg)?.let(applyCampaign)
                    note(campaign.id, "FILL_BEATS_CANCEL", "$clientOrderId filled before cancellation")
                }
                LiveOrderStatus.CANCEL_FAILED ->
                    note(campaign.id, "ORDER_CANCEL_FAILED", "$clientOrderId: ${outcome.reason}")
                else -> Unit
            }
        } finally {
            Arrays.fill(creds.first, '\u0000')
            Arrays.fill(creds.second, '\u0000')
            publish()
        }
    }

    /** Books a fill the venue reported while we asked to cancel (§27). */
    private fun markSliceFilled(campaign: Campaign, clientOrderId: String, leg: LiveLeg): Campaign? {
        val sliceIndex = campaign.entryLadder.firstOrNull { slice ->
            val id = if (leg == LiveLeg.TAKE_PROFIT) {
                ClientOrderIds.forTakeProfit(campaign.id, slice.sliceIndex)
            } else {
                ClientOrderIds.forSlice(campaign.id, slice.sliceIndex)
            }
            id == clientOrderId
        }?.sliceIndex ?: return null
        val ladder = campaign.entryLadder.map { slice ->
            when {
                slice.sliceIndex != sliceIndex -> slice
                leg == LiveLeg.TAKE_PROFIT ->
                    slice.copy(isFilled = true, isResting = false, isClosedAtTarget = true)
                else -> slice.copy(isFilled = true, isResting = false)
            }
        }
        val filled = ladder.filter { it.isFilled }
        val status = when {
            filled.isEmpty() -> campaign.status
            filled.all { it.isClosedAtTarget } -> CampaignState.COMPLETED
            else -> CampaignState.PARTIALLY_FILLED
        }
        return campaign.copy(entryLadder = ladder, status = status)
    }
}
