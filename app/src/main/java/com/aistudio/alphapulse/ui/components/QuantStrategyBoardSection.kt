package com.aistudio.alphapulse.ui.components

/**
 * The §74 Quant Strategy Board.
 *
 * Why this exists
 * ---------------
 * §3's library is only useful if its verdicts are visible. This board renders the seven
 * technical confluence screeners and the nine quantitative models evaluated on the same
 * bars the current snapshot already fetched, with each model's own entry, exit and
 * invalidation levels. A model with too little history renders as FLAT rather than
 * borrowing a level it did not compute.
 *
 * Spec Ref: §74 Auxiliary Confluence Screeners & Quantitative Strategy Models, Directive §3
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.domain.presentation.StrategyConflicts
import com.aistudio.alphapulse.domain.presentation.StrategyLevels
import com.aistudio.alphapulse.domain.presentation.ZoneMetadata
import com.aistudio.alphapulse.domain.strategy.ScreenerSignal
import com.aistudio.alphapulse.domain.strategy.SignalDirection
import com.aistudio.alphapulse.domain.strategy.StrategySignal
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBearish
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.BrandSurface
import java.util.Locale

private fun directionColor(direction: SignalDirection): Color = when (direction) {
    SignalDirection.BULLISH -> BrandBullish
    SignalDirection.BEARISH -> BrandBearish
    SignalDirection.NEUTRAL -> Color(0xFF94A3B8)
}

private fun directionLabel(direction: SignalDirection): String = when (direction) {
    SignalDirection.BULLISH -> "LONG"
    SignalDirection.BEARISH -> "SHORT"
    SignalDirection.NEUTRAL -> "FLAT"
}

private fun level(value: Double?): String =
    if (value == null) "—" else String.format(Locale.US, "%.4f", value)

@Composable
fun QuantStrategyBoardSection(
    screenerSignals: List<ScreenerSignal>,
    strategySignals: List<StrategySignal>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "QUANT STRATEGY BOARD",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            val active = strategySignals.count { it.isActive }
            Text(
                text = "$active / ${strategySignals.size} ACTIVE",
                color = BrandAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        HorizontalDivider(color = BrandBorder)

        if (screenerSignals.isEmpty() && strategySignals.isEmpty()) {
            Text(
                text = "Waiting for the first evaluation cycle…",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
            return@Column
        }

        // Confluence screeners: one compact chip per §3 screener.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            screenerSignals.forEach { screener ->
                Text(
                    text = "${screener.name}: ${directionLabel(screener.direction)}",
                    color = directionColor(screener.direction),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BrandBorder.copy(alpha = 0.35f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        HorizontalDivider(color = BrandBorder)

        // D3: one direction leads; opposing setups are retained but tagged.
        val dominant = StrategyConflicts.dominantDirection(strategySignals)
        strategySignals.forEach { strategy ->
            StrategyRow(strategy, dominant)
        }
    }
}

@Composable
private fun StrategyRow(strategy: StrategySignal, dominant: SignalDirection?) {
    val conflictTag = StrategyConflicts.tagOf(strategy, dominant)
    val conflictNote = StrategyConflicts.noteOf(strategy, dominant)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = strategy.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                conflictTag?.let { tag ->
                    val isConflict = tag.contains("CONFLICT")
                    Text(
                        text = tag,
                        color = if (isConflict) BrandAmber else BrandBullish,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (isConflict) BrandAmber.copy(alpha = 0.15f)
                                else BrandBullish.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = directionLabel(strategy.direction),
                color = directionColor(strategy.direction),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // §3 addition: a card with no trigger publishes no fake levels.
        val hasLevels = strategy.entry != null || strategy.exit != null || strategy.invalidation != null
        Text(
            text = if (hasLevels) {
                "ENTRY ${level(strategy.entry)}   EXIT ${level(strategy.exit)}   " +
                    "INVALID ${strategy.invalidation?.let { String.format(Locale.US, "%.4f", it) } ?: "--"}"
            } else {
                StrategyLevels.unavailableStatus()
            },
            color = Color(0xFF94A3B8),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )

        if (hasLevels) {
            Text(
                text = StrategyLevels.invalidationLabel(strategy.invalidation),
                color = Color(0xFF64748B),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // §3 addition: structural metadata — how far the shelf is and whether it
        // has been tested already.
        Text(
            text = ZoneMetadata.format(strategy.distanceAtr, strategy.zoneTouches),
            color = Color(0xFF64748B),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )

        Text(
            text = conflictNote ?: strategy.detail,
            color = if (conflictNote != null) BrandAmber else Color(0xFF64748B),
            fontSize = 9.sp
        )
    }
}
