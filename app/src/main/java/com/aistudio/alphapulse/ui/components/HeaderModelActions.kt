package com.aistudio.alphapulse.ui.components

/**
 * The two TSFM quick actions, on the weights card they feed.
 *
 * Directive §3 removed the bottom model dock and moved these into the header; C-57 moved
 * them again, out of the header and onto `ML ENSEMBLE WEIGHTS`, so a header can stay one
 * identity row while the download controls sit on the numbers they produce.
 *
 * A missing model is muted and tappable and starts exactly one download; a verified model
 * is green and inert; a running transfer shows a progress ring.
 *
 * Spec Ref: Directive §3 TSFM Quick Actions, C-57
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aistudio.alphapulse.data.model.ModelArtifacts
import com.aistudio.alphapulse.data.model.ModelDownloadState
import com.aistudio.alphapulse.data.model.ModelLoadStatus
import com.aistudio.alphapulse.ui.theme.BrandAccent
import com.aistudio.alphapulse.ui.theme.BrandBorder
import com.aistudio.alphapulse.ui.theme.BrandCard

/** Green the quick action turns when its on-device artifact is verified. */
private val ModelReadyGreen = Color(0xFF00E676)

/** Muted grey for an artifact that is not on the device yet. */
private val ModelMissingGrey = Color(0xFF616161)

/** Both quick actions in their shipped order, so every host agrees. */
@Composable
fun HeaderModelActions(
    modelStates: Map<String, ModelDownloadState>,
    onDownloadModel: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HeaderModelIcon(
            state = modelStates[ModelArtifacts.CHRONOS.modelId]
                ?: ModelDownloadState(ModelArtifacts.CHRONOS.modelId),
            icon = Icons.Rounded.Bolt,
            label = "Chronos-Bolt",
            tag = "chronos",
            onDownload = { onDownloadModel(ModelArtifacts.CHRONOS.modelId) }
        )
        HeaderModelIcon(
            state = modelStates[ModelArtifacts.TIMESFM.modelId]
                ?: ModelDownloadState(ModelArtifacts.TIMESFM.modelId),
            icon = Icons.Rounded.Psychology,
            label = "TimesFM-2.5",
            tag = "timesfm",
            onDownload = { onDownloadModel(ModelArtifacts.TIMESFM.modelId) }
        )
    }
}

@Composable
fun HeaderModelIcon(
    state: ModelDownloadState,
    icon: ImageVector,
    label: String,
    tag: String,
    onDownload: () -> Unit
) {
    val ready = state.status == ModelLoadStatus.READY
    val downloading = state.status == ModelLoadStatus.DOWNLOADING
    val tint = when {
        ready -> ModelReadyGreen
        downloading -> BrandAccent
        else -> ModelMissingGrey
    }

    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BrandCard)
            .border(
                1.dp,
                if (ready) ModelReadyGreen.copy(alpha = 0.45f) else BrandBorder,
                RoundedCornerShape(6.dp)
            )
            .clickable(enabled = !ready && !downloading) { onDownload() }
            .testTag("header_model_$tag"),
        contentAlignment = Alignment.Center
    ) {
        if (downloading) {
            CircularProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(14.dp),
                color = BrandAccent,
                strokeWidth = 1.5.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = if (ready) "$label ready" else "Download $label",
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
