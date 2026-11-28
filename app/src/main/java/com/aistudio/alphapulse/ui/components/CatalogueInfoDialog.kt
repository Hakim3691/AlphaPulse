package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aistudio.alphapulse.domain.catalogue.CatalogueLedger
import com.aistudio.alphapulse.domain.catalogue.CatalogueLedgerInfo
import com.aistudio.alphapulse.domain.catalogue.TradeIdea
import com.aistudio.alphapulse.domain.catalogue.baselineLabel
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandSurface
import com.aistudio.alphapulse.ui.theme.TextPrimary
import com.aistudio.alphapulse.ui.theme.TextSecondary
import com.aistudio.alphapulse.ui.theme.TextTertiary

/**
 * C-66 — the catalogue slice behind one ledger.
 *
 * The 44-row catalogue card used to sit at the bottom of the AlphaPulse scroll, detached
 * from the PRICE, PATTERN and QUANT ledgers it feeds. Each ledger now opens this dialog
 * with exactly its own setups, so the taxonomy is reachable where it is used and the
 * three slices add up to the full catalogue once.
 *
 * Spec Ref: Phase 1 §3.1, C-66
 */
@Composable
fun CatalogueInfoDialog(
    isOpen: Boolean,
    ledger: CatalogueLedger,
    ideas: List<TradeIdea>,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = BrandSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BrandBorder, RoundedCornerShape(14.dp))
                .testTag("catalogue_info_dialog_${ledger.name.lowercase()}")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = CatalogueLedgerInfo.titleFor(ledger, ideas),
                        color = BrandAmber,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${ideas.size} SETUPS",
                        color = BrandAccent,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = CatalogueLedgerInfo.descriptionFor(ledger),
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )

                HorizontalDivider(color = BrandBorder)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .testTag("catalogue_info_rows")
                ) {
                    items(ideas) { idea ->
                        CatalogueInfoRow(idea)
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("catalogue_info_close")
                ) {
                    Text("CLOSE", color = BrandAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CatalogueInfoRow(idea: TradeIdea) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = idea.name,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = idea.regimes.joinToString(" · ") { it.name.replace('_', ' ') },
                color = TextTertiary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = idea.baselineLabel(),
            color = if (idea.trendFollowing) BrandAccent else TextSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
