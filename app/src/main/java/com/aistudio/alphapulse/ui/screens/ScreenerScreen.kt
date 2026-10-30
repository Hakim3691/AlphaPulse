package com.aistudio.alphapulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.data.model.CryptoAsset
import com.aistudio.alphapulse.data.model.MarketRegime
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.EmeraldBull
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary
import com.aistudio.alphapulse.ui.theme.VioletAccent

@Composable
fun ScreenerScreen(
    assets: List<CryptoAsset>,
    selectedAsset: CryptoAsset?,
    isLiveUniverse: Boolean,
    onSelectAsset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedRegimeFilter by remember { mutableStateOf<MarketRegime?>(null) }

    val filteredAssets = remember(assets, selectedRegimeFilter) {
        if (selectedRegimeFilter == null) assets
        else assets.filter { it.regime == selectedRegimeFilter }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
            .testTag("screener_screen")
    ) {
        // Section Header & Universe Filter Boundaries
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
                Text(
                    text = "MID-CAP UNIVERSE DISCOVERY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "Binance + CoinGlass Validated",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = EmeraldBull,
                        fontSize = 9.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Strict Inclusion: 24h Vol $50M - $750M • Spread < 0.10% • Active Funding History",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SlateTextMuted,
                    fontSize = 10.sp
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // §4 display-data honesty banner: never show simulated values as if live.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    (if (isLiveUniverse) EmeraldBull else AmberWarning).copy(alpha = 0.10f),
                    RoundedCornerShape(8.dp)
                )
                .border(
                    1.dp,
                    (if (isLiveUniverse) EmeraldBull else AmberWarning).copy(alpha = 0.5f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isLiveUniverse) EmeraldBull else AmberWarning)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isLiveUniverse) {
                    "LIVE — streaming from Binance + CoinGlass public APIs (no keys needed)"
                } else {
                    "OFFLINE SEED DATA — live discovery in progress or feeds unreachable; values are placeholders, not market data"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (isLiveUniverse) EmeraldBull else AmberWarning,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Regime Filter Tabs
        ScrollableTabRow(
            selectedTabIndex = if (selectedRegimeFilter == null) 0 else selectedRegimeFilter!!.ordinal + 1,
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            contentColor = CyanAccent,
            divider = {}
        ) {
            Tab(
                selected = selectedRegimeFilter == null,
                onClick = { selectedRegimeFilter = null },
                text = { Text("All (${assets.size})", fontSize = 11.sp) }
            )
            MarketRegime.values().forEach { regime ->
                val count = assets.count { it.regime == regime }
                Tab(
                    selected = selectedRegimeFilter == regime,
                    onClick = { selectedRegimeFilter = regime },
                    text = { Text("${regime.label.split(" ").first()} ($count)", fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Asset List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredAssets, key = { it.symbol }) { asset ->
                ScreenerAssetCard(
                    asset = asset,
                    isSelected = asset.symbol == selectedAsset?.symbol,
                    onSelect = { onSelectAsset(asset.symbol) }
                )
            }
        }
    }
}

@Composable
private fun ScreenerAssetCard(
    asset: CryptoAsset,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val isPositive = asset.priceChange24h >= 0
    val changeColor = if (isPositive) EmeraldBull else CrimsonBear

    val regimeColor = when (asset.regime) {
        MarketRegime.BULLISH_CONTINUATION -> EmeraldBull
        MarketRegime.BEARISH_CONTINUATION -> CrimsonBear
        MarketRegime.TRANSITION -> VioletAccent
        MarketRegime.BALANCE -> AmberWarning
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBackground)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) CyanAccent else CardBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onSelect() }
            .padding(12.dp)
            .testTag("asset_row_${asset.symbol}")
    ) {
        // Row 1: Symbol, Price, 24h change, and Regime Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = asset.symbol,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Regime Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(regimeColor.copy(alpha = 0.15f))
                        .border(1.dp, regimeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = asset.regime.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = regimeColor,
                            fontSize = 8.sp
                        )
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${asset.lastPrice}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = SlateTextPrimary
                    )
                )
                Text(
                    text = "${if (isPositive) "+" else ""}${asset.priceChange24h}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = changeColor,
                        fontSize = 11.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 2: Tactical & Strategic Bias Scores (-100 to +100)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BiasGaugePill(
                title = "TACTICAL BIAS (15M-1H)",
                score = asset.tacticalBias
            )
            Spacer(modifier = Modifier.width(8.dp))
            BiasGaugePill(
                title = "STRATEGIC BIAS (4H-1D)",
                score = asset.strategicBias
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 3: Primary Pain Path Route
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF090E1D), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PAIN PATH: ",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent,
                    fontSize = 9.sp
                )
            )
            Text(
                text = asset.primaryPainPath,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SlateTextPrimary,
                    fontSize = 10.sp
                )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 4: Volume & Funding stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "24h Vol: $${(asset.quoteVolume24h / 1_000_000).toInt()}M",
                style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 9.sp)
            )
            Text(
                text = "Spread: ${asset.orderBookSpreadPct}%",
                style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 9.sp)
            )
            Text(
                text = "Funding: ${(asset.fundingRatePct * 100).let { Math.round(it * 100.0) / 100.0 }}%",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (asset.fundingRatePct < 0) CyanAccent else AmberWarning,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            )
            Text(
                text = "OI: $${(asset.openInterestUsd / 1_000_000).toInt()}M",
                style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 9.sp)
            )
        }
    }
}

@Composable
private fun BiasGaugePill(title: String, score: Int) {
    val color = when {
        score >= 60 -> EmeraldBull
        score >= 20 -> Color(0xFF69F0AE)
        score in -19..19 -> SlateTextSecondary
        score in -59..-20 -> Color(0xFFFF8A80)
        else -> CrimsonBear
    }

    Column(
        modifier = Modifier
            .background(Color(0xFF0D1526), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted, fontSize = 8.sp)
        )
        Text(
            text = "${if (score > 0) "+" else ""}$score",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color,
                fontSize = 11.sp
            )
        )
    }
}
