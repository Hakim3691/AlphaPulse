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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.engine.OverrideWarning
import com.aistudio.alphapulse.ui.theme.AmberWarning
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CardBorder
import com.aistudio.alphapulse.ui.theme.CrimsonBear
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary
import com.aistudio.alphapulse.ui.theme.SlateTextSecondary

@Composable
fun ManualOverrideDialog(
    isOpen: Boolean,
    campaign: Campaign?,
    warnings: List<OverrideWarning>,
    onApplyOverride: (newStop: Double, newSize: Double) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen || campaign == null) return

    var stopPriceText by remember(campaign.id) {
        mutableStateOf(campaign.stopLogic.hardStopPrice.toString())
    }
    var sizeBudgetText by remember(campaign.id) {
        mutableStateOf(campaign.sizeBudgetUsd.toInt().toString())
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CardBackground,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .testTag("manual_override_dialog")
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Text(
                    text = "MANUAL OPERATOR OVERRIDE",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "${campaign.family.displayName} (${campaign.asset})",
                    style = MaterialTheme.typography.labelSmall.copy(color = SlateTextMuted)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Stop Price input
                OutlinedTextField(
                    value = stopPriceText,
                    onValueChange = { stopPriceText = it },
                    label = { Text("Hard Stop Price ($)") },
                    supportingText = {
                        Text("Engine calculated edge: $${campaign.stopLogic.hardStopPrice}")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("override_stop_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Size Budget input
                OutlinedTextField(
                    value = sizeBudgetText,
                    onValueChange = { sizeBudgetText = it },
                    label = { Text("Size Budget (USD)") },
                    supportingText = {
                        Text("Engine approved allocation: $${campaign.sizeBudgetUsd.toInt()}")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("override_size_input")
                )

                // Persistent Guardrail Warnings Display (Section 24)
                if (warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A1519), RoundedCornerShape(8.dp))
                            .border(1.dp, CrimsonBear, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Risk Warning",
                                tint = CrimsonBear,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PERSISTENT GUARDRAIL WARNING",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = CrimsonBear,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        warnings.forEach { warning ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• ${warning.warningMessage}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SlateTextPrimary,
                                    fontSize = 10.sp
                                )
                            )
                            Text(
                                text = "Plan: ${warning.structuralPlanValue} vs Override: ${warning.userOverrideValue}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = AmberWarning,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Close")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val newStop = stopPriceText.toDoubleOrNull() ?: campaign.stopLogic.hardStopPrice
                            val newSize = sizeBudgetText.toDoubleOrNull() ?: campaign.sizeBudgetUsd
                            onApplyOverride(newStop, newSize)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (warnings.isNotEmpty()) AmberWarning else CyanAccent,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.testTag("apply_override_button")
                    ) {
                        Text("Confirm Override", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
