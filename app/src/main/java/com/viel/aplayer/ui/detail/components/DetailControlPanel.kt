package com.viel.aplayer.ui.detail.components

// Import Clip Extension (Fix unresolved clip extension reference) Add explicit draw.clip import to allow using Modifier.clip.
// Setup Haze Integration (Import dev.chrisbanes.haze library) Import HazeState and Haze modifiers for panel.
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.shared.formatFileSize
import com.viel.aplayer.shared.formatTime
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.detail.DetailUiState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * DetailControlPanel Setup (Detail Operations Panel Component)
 *
 * Encapsulates the operations control panel of the details page (DetailControlPanel).
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun DetailControlPanel(
    book: DetailBookItem?,
    uiState: DetailUiState,
    glassEffectMode: GlassEffectMode,
    // Setup HazeState Parameter (Map backdrop from LayerBackdrop to HazeState) Changed LayerBackdrop to HazeState.
    hazeState: HazeState?,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    // Control Panel Detail Item (Read chips from the scene projection)
    // Year, duration, and size are render-only fields, so the panel no longer needs the Room book entity.
    // Setup Haze Effect Switch (Check configured visual style) Aligned to renamed Haze option.
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val displayProgress = uiState.displayProgressPercent
    // Detail Action Copy (Move app-owned labels into resources)
    // Resource-backed text lets localization and accessibility review validate TalkBack output and large-font fit.
    val unknownText = stringResource(R.string.common_unknown)
    val actionText = when {
        !uiState.isAvailable -> stringResource(R.string.detail_file_not_found)
        displayProgress > 0 -> stringResource(R.string.detail_continue_at_progress, displayProgress)
        else -> stringResource(R.string.detail_start_listening)
    }

    val buttonHeight = if (isLandscape) 48.dp else 56.dp
    val cornerRadius = if (isLandscape) 12.dp else 16.dp
    val chipSpacing = if (isLandscape) 8.dp else 10.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Metadata Chip Group
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(chipSpacing, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DetailInfoChip(
                icon = Icons.Rounded.Event,
                value = book?.year?.takeIf { it.isNotBlank() } ?: unknownText,
                glassEffectMode = glassEffectMode,
                hazeState = hazeState
            )
            DetailInfoChip(
                icon = Icons.Rounded.Timelapse,
                value = formatTime(book?.totalDurationMs ?: 0L),
                glassEffectMode = glassEffectMode,
                hazeState = hazeState
            )
            if ((book?.totalFileSize ?: 0L) > 0) {
                DetailInfoChip(
                    icon = Icons.Rounded.Storage,
                    value = formatFileSize(book?.totalFileSize ?: 0L),
                    glassEffectMode = glassEffectMode,
                    hazeState = hazeState
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Main Playback Control Button
        if (isBlur && uiState.isAvailable) {
            Surface(
                onClick = {
                    onPlayPressed()
                    onPlayClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(buttonHeight)
                    .let {
                        if (hazeState != null) {
                            // Setup Play Button Haze Modifier (Configure hazeChild blur effects) Apply hazeChild to button when in Haze mode.
                            it
                                .clip(RoundedCornerShape(cornerRadius))
                                .hazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.ultraThin()
                                )
                        } else {
                            it
                        }
                    },
                shape = RoundedCornerShape(cornerRadius),
                color = Color.Transparent,
                border = null,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (displayProgress > 0) Icons.Rounded.History else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(if (isLandscape) 20.dp else 24.dp)
                    )
                    Spacer(modifier = Modifier.width(if (isLandscape) 6.dp else 8.dp))
                    Text(
                        text = actionText,
                        style = if (isLandscape) {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        } else {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        },
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Button(
                onClick = { 
                    if (uiState.isAvailable) {
                        onPlayPressed()
                        onPlayClick()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(buttonHeight),
                shape = RoundedCornerShape(cornerRadius),
                colors = if (uiState.isAvailable) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            ) {
                Icon(
                    imageVector = if (!uiState.isAvailable) Icons.Rounded.Storage 
                    else if (displayProgress > 0) Icons.Rounded.History 
                    else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(if (isLandscape) 20.dp else 24.dp)
                )
                Spacer(modifier = Modifier.width(if (isLandscape) 6.dp else 8.dp))
                Text(
                    text = actionText,
                    style = if (isLandscape) {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    } else {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    }
                )
            }
        }

        // 3. Physical File Path Display
        if (uiState.fullSourcePath.isNotEmpty()) {
            Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))
            Text(
                text = uiState.fullSourcePath,
                style = if (isLandscape) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


/**
 * DetailInfoChip Setup (Metadata Chip Component)
 *
 * Refactored details metadata card (DetailInfoChip).
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun DetailInfoChip(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    // Pass global glass effect mode, defaulting to GlassEffectMode.Material
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Setup HazeState Parameter (Map backdrop parameter to HazeState) Changed LayerBackdrop to HazeState.
    hazeState: HazeState? = null
) {
    // Detect whether Haze glass effect mode is enabled and sampling source is not null
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    // Detail Chip Width Boundary (Cap metadata pills before they can dominate narrow panels)
    // Year, duration, and size are summary values, so long localized fallback text should truncate inside the chip instead of widening the FlowRow indefinitely.
    val chipMaxWidth = 168.dp

    // Using a custom Surface instead of SuggestionChip for tighter spacing and no extra transparent clickable area
    Surface(
        modifier = modifier
            .widthIn(max = chipMaxWidth)
            .let {
                if (isBlur) {
                    val chipShape = RoundedCornerShape(12.dp)
                    it
                        .clip(chipShape)
                        .hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin()
                        )
                } else {
                    it
                }
            },
        shape = RoundedCornerShape(12.dp),
        border = if (isBlur) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = LocalContentColor.current.copy(alpha = 0.5f)
            )
        },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current
            )
            Text(
                text = value,
                // Detail Chip Text Boundary (Measure metadata text only within the remaining chip row space)
                // Weight gives the text a finite width after the icon, and clipToBounds prevents large-font glyphs from painting outside the pill.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clipToBounds(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }
    }
}
