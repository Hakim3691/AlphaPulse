package com.aistudio.alphapulse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.aistudio.alphapulse.data.model.ModelManager
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.BrandSurface
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandCard
import com.aistudio.alphapulse.ui.theme.BrandSurface
import com.aistudio.alphapulse.ui.theme.BrandBullish
import com.aistudio.alphapulse.ui.theme.TextPrimary
import com.aistudio.alphapulse.ui.theme.TextTertiary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun ModelDownloadSection(
    onDownloadClick: (
        modelId: String,
        version: String,
        checksum: String,
        backend: String,
        sizeBytes: Long,
        parameterCount: Int,
        quantization: String
    ) -> Unit
) {
    var open by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "MODEL DOWNLOAD",
                        color = BrandBullish,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Download mid-tier TSFM models for enhanced inference",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    if (open) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ModelDownloadRow(
                                modelKey = "chronos",
                                modelName = "Chronos Bolt Tiny",
                                repo = "bakshinghampalace/chronos-bolt-tiny-onnx",
                                fileName = "chronos-bolt-tiny-fp16.onnx",
                                sizeMb = 18,
                                onClick = { onDownloadClick(
                                    "chronos",
                                    "1.0",
                                    "",
                                    "KRONOS_SMALL",
                                    18L * 1024 * 1024,
                                    1000,
                                    "FP16"
                                )}
                            )

                            ModelDownloadRow(
                                modelKey = "timesfm",
                                modelName = "TimesFM 2.5 200M",
                                repo = "onnx-community/timesfm-2.5-200m-int4",
                                fileName = "model_quantized.onnx",
                                sizeMb = 120,
                                onClick = { onDownloadClick(
                                    "timesfm",
                                    "2.5",
                                    "",
                                    "TIMES_FM",
                                    120L * 1024 * 1024,
                                    200_000_000,
                                    "INT4"
                                )}
                            )
                        }
                    } else {
                        Text(
                            text = "Models ready for inference",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Column(
                    modifier = Modifier.size(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = "Toggle model details",
                        tint = BrandAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (open) {
                Divider(modifier = Modifier.fillMaxWidth(), thickness = 1.dp, color = BrandBorder)
            }
        }
    }
}

@Composable
fun ModelDownloadRow(
    modelKey: String,
    modelName: String,
    repo: String,
    fileName: String,
    sizeMb: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BrandSurface)
            .border(1.dp, BrandBorder, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = modelName,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Repo: $repo",
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "$sizeMb MB",
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BrandCard)
                        .border(1.dp, BrandBorder, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Download",
                        color = BrandBullish,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Click handler
            
        }
    }
}