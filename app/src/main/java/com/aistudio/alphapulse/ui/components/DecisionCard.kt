package com.aistudio.alphapulse.ui.components

/**
 * The live trade decision card.
 *
 * §29 produces one of six decision states, with a calibrated confidence and the
 * router's own reason. The workspace showed the forecasts and the ensemble weights
 * but never the verdict, so a user could not tell what the engine actually
 * concluded - nor whether Cactus Needle 3 answered or the deterministic rules did.
 * This card closes that gap: the state, its confidence, and the reason the router
 * recorded.
 *
 * A confidence of 0.0 is not a weak belief, it is the absence of a calibrated
 * number: the deterministic rules answered, or LoRA weights made Needle 3 withhold
 * its confidence head. It reads as UNSCORED rather than as a flattering 0.00.
 *
 * Spec Ref: §29 Quantitative Decision Router, §39 User Interface, §40 Primary Dashboard
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.aistudio.alphapulse.domain.decision.DecisionReport
import com.aistudio.alphapulse.domain.decision.DecisionState
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBearish
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.BrandSurface
import com.aistudio.alphapulse.ui.theme.TextSecondary
import com.aistudio.alphapulse.ui.theme.TextTertiary
import java.util.Locale

/** Tag used by the BDD spec to find this card. */
const val DECISION_CARD_TAG: String = "decisionCard"

/**
 * Render the router's verdict for the selected asset.
 *
 * @param decision the latest §29 report, or null before the first evaluation
 */
@Composable
fun DecisionCard(
    decision: DecisionReport?,
    modifier: Modifier = Modifier
) {
    val tint = DecisionCardText.tintOf(decision)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DECISION_CARD_TAG)
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TRADE DECISION",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    // Confidence only claims a number when the router calibrated one.
                    text = DecisionCardText.confidenceOf(decision),
                    color = if (decision?.isCalibrated == true) BrandAccent else TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = DecisionCardText.titleOf(decision),
                color = tint,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                // §29: the reason is part of the output, not a log line.
                text = DecisionCardText.reasonOf(decision),
                color = TextSecondary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The words the card shows for each verdict.
 *
 * Kept apart from the layout so the text and the colour of every state can be
 * asserted directly, instead of one rendered tree per assertion.
 */
internal object DecisionCardText {

    const val AWAITING_TITLE: String = "AWAITING FIRST EVALUATION"
    const val AWAITING_REASON: String = "No decision has been produced for this asset yet."
    const val UNSCORED: String = "UNSCORED"

    /** The state as words: `STRONG_LONG` reads as `STRONG LONG`. */
    fun titleOf(decision: DecisionReport?): String =
        decision?.state?.name?.replace('_', ' ') ?: AWAITING_TITLE

    /** A calibrated confidence, or a plain statement that there is none. */
    fun confidenceOf(decision: DecisionReport?): String {
        if (decision == null) return "CONF --"
        return if (decision.isCalibrated) {
            String.format(Locale.US, "CONF %.2f", decision.confidence)
        } else {
            UNSCORED
        }
    }

    fun reasonOf(decision: DecisionReport?): String =
        decision?.reason?.takeIf { it.isNotBlank() } ?: AWAITING_REASON

    fun tintOf(decision: DecisionReport?): Color = when (decision?.state) {
        DecisionState.STRONG_LONG, DecisionState.LONG -> BrandBullish
        DecisionState.STRONG_SHORT, DecisionState.SHORT -> BrandBearish
        DecisionState.NEUTRAL -> BrandAmber
        DecisionState.NO_TRADE -> TextSecondary
        null -> TextTertiary
    }
}
