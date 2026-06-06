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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.formatFileSize
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import com.viel.aplayer.ui.detail.DetailUiState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * DetailControlPanel Setup (Detail Operations Panel Component)
 *
 * Encapsulates the operations control panel of the details page (DetailControlPanel).
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun DetailControlPanel(
    book: BookEntity?,
    uiState: DetailUiState,
    glassEffectMode: GlassEffectMode,
    // Setup HazeState Parameter (Map backdrop from LayerBackdrop to HazeState) Changed LayerBackdrop to HazeState.
    hazeState: HazeState?,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    // Setup Haze Effect Switch (Check configured visual style) Aligned to renamed Haze option.
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val displayProgress = uiState.displayProgressPercent

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
                value = book?.year?.takeIf { it.isNotBlank() } ?: "Unknown",
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
                                .liquidGlassCompatEffect(
                                    state = hazeState,
                                    style = LiquidGlassStyle(
                                        //tint = Color.White.copy(alpha = 0.15f), // 玻璃色调
                                        specularIntensity = 0.6f,              // 高光反射强度
                                        ambientResponse = 0.5f,                // 边缘漫反射反射强度
                                        shape = RoundedCornerShape(cornerRadius)       // 玻璃形状（会自动适应边框绘制）
                                    )
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
                        text = if (displayProgress > 0) "Continue at $displayProgress%" else "Start Listening",
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
                    text = if (!uiState.isAvailable) "File not found"
                           else if (displayProgress > 0) "Continue at $displayProgress%" 
                           else "Start Listening",
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

    // Using a custom Surface instead of SuggestionChip for tighter spacing and no extra transparent clickable area
    Surface(
        modifier = modifier
            .let {
                if (isBlur) {
                    val chipShape = RoundedCornerShape(12.dp)
                    it
                        .clip(chipShape)
                        .liquidGlassCompatEffect(
                            state = hazeState,
                            style = LiquidGlassStyle(
                                // Adaptive Glass Tint: Fallback to theme-based 12% tint (White in Dark, Black in Light) by leaving it Unspecified.
                                specularIntensity = 0.4f,
                                ambientResponse = 0.5f,
                                shape = chipShape
                            )
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
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                softWrap = false
            )
        }
    }
}
