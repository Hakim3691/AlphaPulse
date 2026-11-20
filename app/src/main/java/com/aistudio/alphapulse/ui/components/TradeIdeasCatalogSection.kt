package com.aistudio.alphapulse.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.domain.catalogue.CatalogueBrowser
import com.aistudio.alphapulse.domain.catalogue.CatalogueFilter
import com.aistudio.alphapulse.domain.catalogue.TradeIdea
import com.aistudio.alphapulse.domain.catalogue.baselineLabel
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandAmber
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.BrandSurface
import com.aistudio.alphapulse.ui.theme.TextPrimary
import com.aistudio.alphapulse.ui.theme.TextSecondary
import com.aistudio.alphapulse.ui.theme.TextTertiary

/**
 * Phase 1 §3.1: the Trade Ideas Catalog, browsable by its four categories.
 *
 * The engine has always been the consumer of the taxonomy; this is the surface that
 * lets a trader audit what it may fire. Baselines are the catalogue's published
 * numbers, clearly separate from the app's own live record.
 *
 * Spec Ref: Phase 1 §3.1 Comprehensive Trade Ideas & Pattern / Quant Catalog
 */
@Composable
fun TradeIdeasCatalogSection(
    ideas: List<TradeIdea>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    var favorites by remember {
        mutableStateOf(prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet())
    }
    var filter by remember { mutableStateOf(CatalogueFilter.ALL) }

    val visible = CatalogueBrowser.visible(ideas, filter, favorites)

    Column(
        modifier = modifier
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
                text = "TRADE IDEAS CATALOG",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "${visible.size} / ${ideas.size}",
                color = BrandAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        HorizontalDivider(color = BrandBorder)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CatalogueFilter.entries.forEach { candidate ->
                val selected = candidate == filter
                Text(
                    text = candidate.label,
                    color = if (selected) BrandAccent else TextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) BrandAccent.copy(alpha = 0.2f) else BrandCard)
                        .border(
                            1.dp,
                            if (selected) BrandAccent else BrandBorder,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { filter = candidate }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        HorizontalDivider(color = BrandBorder)

        if (visible.isEmpty()) {
            Text(
                text = "No setups in this category yet.",
                color = TextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                visible.forEach { idea ->
                    CatalogueRow(
                        idea = idea,
                        isFavorite = idea.id in favorites,
                        onToggleFavorite = {
                            val next = CatalogueBrowser.toggleFavorite(favorites, idea.id)
                            favorites = next
                            prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogueRow(
    idea: TradeIdea,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = if (isFavorite) "Remove favourite" else "Add favourite",
            tint = if (isFavorite) BrandAmber else TextTertiary,
            modifier = Modifier.clickable(onClick = onToggleFavorite)
        )

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

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = idea.baselineLabel(),
                color = if (idea.trendFollowing) BrandAccent else TextSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = idea.category.name,
                color = TextTertiary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    HorizontalDivider(color = BrandBorder.copy(alpha = 0.3f))
}

private const val PREFS = "alphapulse_catalogue"
private const val KEY_FAVORITES = "favorites"
