package com.aistudio.alphapulse.ui.components

/**
 * Decision-1 status banner.
 *
 * Microsoft-Decision-1 is a *remote* model: it has no weights to download, so it
 * never gets a row in the model dock. That left a gap - a user could not tell why
 * the decision router kept answering deterministically.
 *
 * This banner closes that gap on the surface the user already has: when the router
 * is not configured it shows the exact, actionable reason (missing provider,
 * endpoint, key or model name). When it *is* configured it renders nothing, so a
 * working setup is silent.
 *
 * It deliberately shows no download control: §29 keeps Decision-1 API-only, and
 * the model dock's downloadable list must never contain it.
 *
 * Spec Ref: §29 Quantitative Decision Router, §42 Security Requirements, §43 API Key Architecture
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.domain.decision.Decision1ConfigOutcome
import com.aistudio.alphapulse.ui.theme.TextTertiary

/** Tag used by the BDD spec to find this row. */
const val DECISION1_STATUS_BANNER_TAG: String = "decision1StatusBanner"

// Amber reads as "needs attention" without shouting like an error red would.
private val BannerDeep = Color(0xFF1A1206)
private val BannerAmber = Color(0xFFF59E0B)

/**
 * Render the router's configuration state.
 *
 * @param status result of resolving the stored Decision-1 settings
 */
@Composable
fun Decision1StatusBanner(
    status: Decision1ConfigOutcome,
    modifier: Modifier = Modifier
) {
    // A configured router has nothing to explain, so it takes no screen space.
    val unconfigured = status as? Decision1ConfigOutcome.Unconfigured ?: return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DECISION1_STATUS_BANNER_TAG)
            .clip(RoundedCornerShape(8.dp))
            .background(BannerDeep)
            .border(1.dp, BannerAmber.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            // Makes it obvious that this model is called, never downloaded.
            text = "DECISION-1 · REMOTE",
            color = BannerAmber,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
        Column {
            Text(
                text = unconfigured.userMessage,
                color = TextTertiary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // States the consequence, so the message is not only a complaint.
                text = "Until then the decision router uses its deterministic rules.",
                color = TextTertiary,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
