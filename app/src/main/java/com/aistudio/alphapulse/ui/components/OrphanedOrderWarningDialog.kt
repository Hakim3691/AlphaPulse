package com.aistudio.alphapulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary

@Composable
fun OrphanedOrderWarningDialog(
    isOpen: Boolean,
    onAcknowledgeAndWipe: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CardBackground,
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, AmberWarning, RoundedCornerShape(14.dp))
                .testTag("orphaned_order_intercept_dialog")
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(27.dp))
                        .background(AmberWarning.copy(alpha = 0.15f))
                        .border(1.5.dp, AmberWarning, RoundedCornerShape(27.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Alert",
                        tint = AmberWarning,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ORPHANED ORDER OS INTERCEPT",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = AmberWarning,
                        letterSpacing = 0.5.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "WARNING: Minimizing or closing this app will instantly disconnect your API session and disable the cancellation engine. Your resting limit orders will NOT be automatically managed or cancelled. You must acknowledge that you are now manually responsible for these trades on the Binance platform.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SlateTextPrimary,
                        lineHeight = 18.sp
                    ),
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Zero-Persistence Mandate: Upon acknowledgment, the session API keys will be immediately expunged from device RAM.",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SlateTextMuted,
                        fontSize = 10.sp
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SlateTextSecondary)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = onAcknowledgeAndWipe,
                        modifier = Modifier.weight(1.5f).testTag("acknowledge_wipe_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrimsonBear,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Acknowledge & Wipe", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
