package com.aistudio.alphapulse.ui.components

/**
 * The recent §29 verdicts recorded for the selected asset.
 *
 * The dashboard card shows the live decision; this log shows what the engine
 * concluded *before* it, so a route that flipped from LONG to NO_TRADE is visible
 * as history rather than only as the latest frame. It reads the same table the
 * audit export joins on, so the log and a CSV row can never disagree.
 *
 * An uncalibrated verdict reads UNSCORED rather than `0.00`, for the same reason
 * the card does: zero is a calibrated claim the router never made.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §29 Quantitative Decision Router, §40 Primary Dashboard
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.aistudio.alphapulse.data.db.DecisionRecordEntity
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBearish
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.TextSecondary
import com.aistudio.alphapulse.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Verdicts rendered before the log stops; the header still reports how many were read. */
const val DECISION_LOG_ROWS: Int = 5

/** Tag used by the BDD spec to find this section. */
const val DECISION_LOG_TAG: String = "decisionLog"

/**
 * Render the newest recorded verdicts, newest first.
 *
 * @param history recorded verdicts for the selected asset, as read from the ledger
 * @param maxRows how many of them to show
 */
@Composable
fun DecisionHistorySection(
    history: List<DecisionRecordEntity>,
    modifier: Modifier = Modifier,
    maxRows: Int = DECISION_LOG_ROWS
) {
    val visible = history.take(maxRows)

    Column(modifier = modifier.fillMaxWidth().testTag(DECISION_LOG_TAG)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DECISION LOG",
                color = TextSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = DecisionLogText.countLabel(history.size, visible.size),
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        if (history.isEmpty()) {
            Text(
                text = DecisionLogText.EMPTY,
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            )
        } else {
            visible.forEach { record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DecisionLogText.timeLabel(record.issuedTimestamp),
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(58.dp)
                    )
                    Text(
                        text = DecisionLogText.stateLabel(record.state),
                        color = DecisionLogText.tintOf(record.state),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(96.dp)
                    )
                    Text(
                        text = DecisionLogText.confidenceLabel(record.confidence),
                        color = if (record.confidence > 0.0) BrandAccent else TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(60.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(BrandCard)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = DecisionLogText.sourceLabel(record.source),
                            color = if (DecisionLogText.answeredOnDevice(record.source)) {
                                BrandBullish
                            } else {
                                BrandAmber
                            },
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        // Which horizon the snapshot scored, so the log is not mistaken
                        // for a per-horizon breakdown of the table below it.
                        text = record.referenceHorizon,
                        color = TextTertiary,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = DecisionLogText.reasonLabel(record.reason),
                        color = TextSecondary,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The words the log shows, kept apart from the layout so each rule can be
 * asserted directly instead of through a rendered tree.
 */
internal object DecisionLogText {

    const val EMPTY: String = "No decisions recorded yet for this asset."

    /** `STRONG_LONG` reads as `STRONG LONG`, matching the live card. */
    fun stateLabel(state: String): String = state.replace('_', ' ')

    /** A calibrated confidence, or a plain statement that there is none. */
    fun confidenceLabel(confidence: Double): String =
        if (confidence > 0.0) String.format(Locale.US, "%.2f", confidence) else "UNSCORED"

    /** Which router answered, in the words a reader uses. */
    fun sourceLabel(source: String): String = when (source) {
        "NEEDLE3" -> "NEEDLE 3"
        "DETERMINISTIC" -> "RULES"
        else -> source.ifBlank { "--" }
    }

    /** True when the on-device model answered rather than the rules. */
    fun answeredOnDevice(source: String): Boolean = source == "NEEDLE3"

    /** "NEWEST 3 OF 40", or "NONE RECORDED" when nothing has been stored. */
    fun countLabel(total: Int, shown: Int): String =
        if (total == 0) "NONE RECORDED" else "NEWEST $shown OF $total"

    fun timeLabel(issuedTimestamp: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(issuedTimestamp))

    fun reasonLabel(reason: String): String =
        reason.takeIf { it.isNotBlank() } ?: "No reason recorded."

    fun tintOf(state: String): Color = when (state) {
        "STRONG_LONG", "LONG" -> BrandBullish
        "STRONG_SHORT", "SHORT" -> BrandBearish
        "NEUTRAL" -> BrandAmber
        "NO_TRADE" -> TextSecondary
        else -> TextTertiary
    }
}
