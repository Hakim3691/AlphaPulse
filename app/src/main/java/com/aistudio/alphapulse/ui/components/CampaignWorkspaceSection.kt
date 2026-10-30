package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.ui.CampaignLiveView
import com.aistudio.alphapulse.ui.LiveOrderRecord
import com.aistudio.alphapulse.ui.LiveOrderViewState
import com.aistudio.alphapulse.ui.ReconcileOutcome
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary

/**
 * The campaign workspace: one card per planned campaign, each showing its staged
 * entry and reduce-only take-profit by client order id with the venue status the
 * coordinator last reported, and a cancel action per live order.
 *
 * It renders inside the app's single scrolling workspace, so it is a plain Column
 * rather than a nested lazy list. The projection is re-derived from the ladder and
 * the coordinator records on every state change — there is no view-side copy of
 * order state to drift.
 *
 * Spec Ref: §22 Order Staging, §27 Cancellation, §33 Guardrails, §39 UI
 */
@Composable
fun CampaignWorkspaceSection(
    campaigns: List<Campaign>,
    selectedCampaignId: String?,
    executionMode: ExecutionMode,
    liveOrderRecords: Map<String, LiveOrderRecord>,
    openClientOrderIds: Set<String>,
    lastReconcile: ReconcileOutcome?,
    onSelectCampaign: (Campaign) -> Unit,
    onOpenOverride: (Campaign) -> Unit,
    onSoftCancel: (String) -> Unit,
    onHardFlatten: (String) -> Unit,
    onCancelLiveOrder: (String) -> Unit,
    /** True when a live session can re-read order state from the venue. */
    canResync: Boolean = false,
    onResync: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val views: Map<String, CampaignLiveView> = remember(
        campaigns,
        liveOrderRecords,
        openClientOrderIds
    ) {
        LiveOrderViewState.project(campaigns, liveOrderRecords, openClientOrderIds)
    }
    val isLive = executionMode == ExecutionMode.CAPPED_LIVE || executionMode == ExecutionMode.SCALED_LIVE

    Column(modifier = modifier.fillMaxWidth().testTag("campaign_workspace")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LIVE CAMPAIGNS & ORDERS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = if (isLive) {
                        "Mode ${executionMode.name} · tap a card to inspect its orders"
                    } else {
                        "Mode ${executionMode.name} · no exchange orders are routed"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 9.sp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lastReconcile != null) {
                    Text(
                        text = "RECONCILED ${lastReconcile.summary}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = AmberWarning,
                            fontSize = 9.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (canResync) {
                    OutlinedButton(
                        onClick = onResync,
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("resync_orders"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent)
                    ) {
                        Text("Resync", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (campaigns.isEmpty()) {
            Text(
                text = "No campaigns planned yet — run a screen to build the boards.",
                style = MaterialTheme.typography.labelSmall.copy(color = SlateTextSecondary, fontSize = 10.sp)
            )
        }

        campaigns.forEach { campaign ->
            CampaignCard(
                campaign = campaign,
                isSelected = campaign.id == selectedCampaignId,
                onSelect = { onSelectCampaign(campaign) },
                onOpenOverride = { onOpenOverride(campaign) },
                onSoftCancel = { onSoftCancel(campaign.id) },
                onHardFlatten = { onHardFlatten(campaign.id) },
                liveView = views[campaign.id],
                onCancelLiveOrder = onCancelLiveOrder,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}
