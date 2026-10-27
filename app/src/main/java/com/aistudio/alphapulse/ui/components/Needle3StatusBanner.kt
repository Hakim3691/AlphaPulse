package com.aistudio.alphapulse.ui.components

/**
 * Needle 3 engine status banner.
 *
 * Cactus Needle 3 is the *on-device* orchestration model (§30): unlike the TSFMs it
 * is not only a file, it is a file that needs an engine to run it. That left a gap -
 * a user could download 34 MB of weights and still get nothing, with no explanation
 * on screen.
 *
 * This banner closes that gap on the surface the user already has, next to the
 * Decision-1 banner so both decision routes are explained in one place. It renders
 * the engine's state in plain words:
 *
 *  * nothing at all when the engine is linked and a checkpoint is mounted;
 *  * otherwise the reason and what to do about it - an emulator without an arm
 *    engine, a checkpoint that has not been downloaded, or a download the engine
 *    rejected as truncated.
 *
 * It shows no download control: downloading is the model dock's job, and this row
 * only explains whether the mounted weights can run.
 *
 * Spec Ref: §30 Needle / Agent Orchestration, §47 Model Download Architecture,
 * §59 AI Narrator Requirements
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
import com.aistudio.alphapulse.domain.decision.Needle3EngineStatus
import com.aistudio.alphapulse.ui.theme.TextTertiary

/** Tag used by the BDD spec to find this row. */
const val NEEDLE3_STATUS_BANNER_TAG: String = "needle3StatusBanner"

// Cyan reads as "on-device" and stays distinct from the amber remote-model banner.
private val BannerDeep = Color(0xFF05141C)
private val BannerCyan = Color(0xFF38BDF8)

/**
 * Render the on-device engine's state.
 *
 * @param status result of asking the engine whether it can run the mounted checkpoint
 */
@Composable
fun Needle3StatusBanner(
    status: Needle3EngineStatus,
    modifier: Modifier = Modifier
) {
    // A ready engine has nothing to explain, so it takes no screen space.
    if (status is Needle3EngineStatus.Ready) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NEEDLE3_STATUS_BANNER_TAG)
            .clip(RoundedCornerShape(8.dp))
            .background(BannerDeep)
            .border(1.dp, BannerCyan.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            // Makes it obvious these weights execute on the device, not over a network.
            text = "NEEDLE 3 · ON-DEVICE",
            color = BannerCyan,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
        Column {
            Text(
                text = Needle3BannerText.reasonOf(status),
                color = TextTertiary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // States the consequence, so the message is not only a complaint.
                text = Needle3BannerText.remedyOf(status),
                color = TextTertiary,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The words the banner shows for each engine state.
 *
 * Kept apart from the layout so every state's text can be asserted directly, for
 * all states at once, instead of one rendered tree per assertion.
 */
internal object Needle3BannerText {

    /** Said on every refusal: the brief still exists, it just comes from the rules (§59). */
    const val NARRATOR_NOTICE: String = " Until then the brief uses the deterministic narrator."

    /** The one-sentence reason for a non-ready engine state. */
    fun reasonOf(status: Needle3EngineStatus): String = when (status) {
        is Needle3EngineStatus.Ready -> ""
        is Needle3EngineStatus.WeightsNotMounted -> status.reason
        is Needle3EngineStatus.NotBundled -> status.reason
        is Needle3EngineStatus.LoadFailed -> status.reason
        is Needle3EngineStatus.InferenceFailed -> status.reason
    }

    /** What the user (or the engineer) can do about it. */
    fun remedyOf(status: Needle3EngineStatus): String = when (status) {
        is Needle3EngineStatus.Ready -> ""
        is Needle3EngineStatus.WeightsNotMounted -> status.remedy + NARRATOR_NOTICE
        is Needle3EngineStatus.NotBundled -> status.remedy + NARRATOR_NOTICE
        is Needle3EngineStatus.LoadFailed -> status.remedy + NARRATOR_NOTICE
        is Needle3EngineStatus.InferenceFailed ->
            status.remedy + " This turn used the deterministic narrator."
    }
}
