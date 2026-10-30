package com.aistudio.alphapulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.data.model.LevelMap
import com.aistudio.alphapulse.ui.components.CampaignCard
import com.aistudio.alphapulse.ui.components.EvidenceItemRow
import com.aistudio.alphapulse.ui.components.InvalidationGauge
import com.aistudio.alphapulse.ui.components.LevelMapCanvas
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary

@Composable
fun DashboardScreen(
    asset: CryptoAsset?,
    levelMap: LevelMap?,
    campaigns: List<Campaign>,
    selectedCampaign: Campaign?,
    liveEvidence: EvidenceFrame?,
    onSelectCampaign: (Campaign) -> Unit,
    onOpenOverride: (Campaign) -> Unit,
    onSoftCancel: (String) -> Unit,
    onHardFlatten: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (asset == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Select an asset from the Screener", color = SlateTextMuted)
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
            .testTag("dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Asset Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = asset.symbol,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = SlateTextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(${asset.regime.label})",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Text(
                            text = "Pain Path: ${asset.primaryPainPath}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SlateTextSecondary,
                                fontSize = 10.sp
                            )
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$${asset.lastPrice}",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = SlateTextPrimary
                            )
                        )
                        Text(
                            text = "ATR(5m): $${asset.atr5m}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SlateTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        )
                    }
                }
            }
        }

        // Interactive Level Map Canvas (HTF Support/Resistance & LTF Pivot/Reclaim)
        item {
            LevelMapCanvas(
                levelMap = levelMap,
                currentPrice = asset.lastPrice,
                activeCampaign = selectedCampaign
            )
        }

        // Live Evidence Bar
        item {
            EvidenceItemRow(evidence = liveEvidence)
        }

        // Invalidation Engine Gauge for currently selected campaign
        item {
            InvalidationGauge(campaign = selectedCampaign)
        }

        // Section Title: 6 Symmetric Campaign Families
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SYMMETRIC PROACTIVE CAMPAIGNS (6)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "Tap to Inspect Plan",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SlateTextMuted,
                        fontSize = 10.sp
                    )
                )
            }
        }

        // Campaign Cards
        items(campaigns, key = { it.id }) { campaign ->
            CampaignCard(
                campaign = campaign,
                isSelected = campaign.id == selectedCampaign?.id,
                onSelect = { onSelectCampaign(campaign) },
                onOpenOverride = { onOpenOverride(campaign) },
                onSoftCancel = { onSoftCancel(campaign.id) },
                onHardFlatten = { onHardFlatten(campaign.id) }
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
