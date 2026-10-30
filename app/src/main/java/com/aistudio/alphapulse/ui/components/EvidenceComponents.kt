package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.EvidenceFrame
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary
import java.util.Locale

/**
 * §25 live evidence strip: the readings the cancellation engine is actually using.
 * Each chip is a measurement, not a placeholder — when a feed is quiet the value
 * shows zero rather than an invented number.
 */
@Composable
fun EvidenceItemRow(evidence: EvidenceFrame?, modifier: Modifier = Modifier) {
    if (evidence == null) {
        Text(
            text = "LIVE EVIDENCE: waiting for the first real sample",
            color = SlateTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = modifier
                .fillMaxWidth()
                .background(CardBackground, RoundedCornerShape(10.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                .padding(10.dp)
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "LIVE EVIDENCE",
            color = SlateTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            EvidenceChip(
                label = "TAKER BUY",
                value = String.format(Locale.US, "%.0f%%", evidence.takerBuyRatio * 100.0),
                tint = if (evidence.takerBuyRatio >= 0.5) EmeraldBull else CrimsonBear
            )
            EvidenceChip(
                label = "OI \u0394 1H",
                value = String.format(Locale.US, "%.2f%%", evidence.oiDelta1hPct),
                tint = if (evidence.oiDelta1hPct >= 0.0) EmeraldBull else CrimsonBear
            )
            EvidenceChip(
                label = "FUNDING",
                value = String.format(Locale.US, "%.4f%%", evidence.fundingRateChangePct),
                tint = CyanAccent
            )
            EvidenceChip(
                label = "TRAP",
                value = "${evidence.trapProbabilityPct}%",
                tint = when {
                    evidence.trapProbabilityPct >= 60 -> CrimsonBear
                    evidence.trapProbabilityPct >= 40 -> AmberWarning
                    else -> EmeraldBull
                }
            )
        }
        Text(
            text = evidence.recentImpulseQuality,
            color = SlateTextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun EvidenceChip(label: String, value: String, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = SlateTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        Text(text = value, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * §27 invalidation gauge: where the selected board sits between its soft cancel
 * threshold and its hard flatten threshold.
 */
@Composable
fun InvalidationGauge(campaign: Campaign?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        if (campaign == null) {
            Text(
                text = "INVALIDATION: no campaign selected",
                color = SlateTextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            return@Column
        }
        val score = campaign.invalidationScore
        val soft = campaign.stopLogic.softThreshold
        val hard = campaign.stopLogic.hardThreshold
        val fraction = (score.toFloat() / 100f).coerceIn(0f, 1f)
        val tint = when {
            score >= hard -> CrimsonBear
            score >= soft -> AmberWarning
            else -> EmeraldBull
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "INVALIDATION ${campaign.family.displayName}",
                color = SlateTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "$score / soft $soft / hard $hard",
                color = tint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(6.dp)
                .background(CardBorder, RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(tint, RoundedCornerShape(3.dp))
            )
        }
    }
}
