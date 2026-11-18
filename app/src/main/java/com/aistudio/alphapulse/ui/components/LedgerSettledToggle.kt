package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.TextSecondary

/**
 * The settled-rows switch the three ledgers share.
 *
 * The ledgers are working lists: what still awaits its settlement is the actionable
 * part, so settled rows are hidden by default and this button is the only way to
 * review them. It is one composable rather than three copies so the price, pattern
 * and decisions ledgers cannot drift apart in wording or behaviour.
 *
 * Spec Ref: §21 Prediction Audit Ledger, §73 Pattern Ledger, Directive §1 Dual Ledger
 */
@Composable
fun LedgerSettledToggle(
    showSettled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BrandCard)
            .border(1.dp, BrandBorder, RoundedCornerShape(4.dp))
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (showSettled) "HIDE SETTLED" else "SHOW SETTLED",
            color = if (showSettled) BrandBullish else TextSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
