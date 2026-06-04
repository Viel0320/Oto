package com.viel.aplayer.ui.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Brush
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
import com.viel.aplayer.ui.detail.DetailUiState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * DetailControlPanel Setup (Detail Operations Panel Component)
 *
 * Encapsulates the operations control panel of the details page (DetailControlPanel).
 * Integrates metadata chip group (DetailInfoChip), primary playback control buttons, and physical file path display.
 * Supports height, corner radius, and spacing adjustments adaptively based on [isLandscape], and perfectly fits the miuix-blur frosted glass effect.
 */
@Composable
fun DetailControlPanel(
    book: BookEntity?,
    uiState: DetailUiState,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop?,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    val isDark = isSystemInDarkTheme()
    val localBlurBackgroundColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
    val localBlurBorderColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.12f)
    val localBlurBorder = androidx.compose.foundation.BorderStroke(0.5.dp, localBlurBorderColor)
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
                backdrop = backdrop
            )
            DetailInfoChip(
                icon = Icons.Rounded.Timelapse,
                value = formatTime(book?.totalDurationMs ?: 0L),
                glassEffectMode = glassEffectMode,
                backdrop = backdrop
            )
            if ((book?.totalFileSize ?: 0L) > 0) {
                DetailInfoChip(
                    icon = Icons.Rounded.Storage,
                    value = formatFileSize(book?.totalFileSize ?: 0L),
                    glassEffectMode = glassEffectMode,
                    backdrop = backdrop
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
                        if (backdrop != null) {
                            // Apply Texture Blur (Hardware-Level Blur Setup)
                            // 1. Use textureBlur for hardware-level Gaussian blur rendering on the button, adding delicate 0.05f frosted noise.
                            // 2. Set the opacity of blendColors (0.35f for dark mode, 0.65f for light mode) as the background keynote, ensuring excellent tint transmission.
                            it.textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(cornerRadius),
                                blurRadius = 60f,
                                noiseCoefficient = 0.05f,
                                colors = BlurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(
                                            color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f),
                                            mode = BlurBlendMode.SrcOver
                                        )
                                    )
                                )
                            )
                            // Apply Specular Glare (Physical Reflection Gradient Overlay)
                            // 3. Chain-append a diagonal white reflective glare overlay (Specular Glare) to simulate physical light reflection on a real crystal glass surface.
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.12f),
                                        Color.White.copy(alpha = 0.03f),
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.06f)
                                    )
                                ),
                                shape = RoundedCornerShape(cornerRadius)
                            )
                            // Apply Refraction Edge (Finely Detailed Border Stroke)
                            // 4. Chain-append a 1.dp thin-light gradient refraction border (Refraction Edge) to highlight excellent dimensional layers on any cluttered background.
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    colors = if (isDark) {
                                        listOf(
                                            Color.White.copy(alpha = 0.18f),
                                            Color.White.copy(alpha = 0.02f),
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.08f)
                                        )
                                    } else {
                                        listOf(
                                            Color.White.copy(alpha = 0.45f),
                                            Color.White.copy(alpha = 0.10f),
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.25f)
                                        )
                                    }
                                ),
                                shape = RoundedCornerShape(cornerRadius)
                            )
                        } else {
                            it
                        }
                    },
                shape = RoundedCornerShape(cornerRadius),
                color = Color.Transparent, // Set background fully transparent to prevent traditional base colors from shielding the underlying frosted glass and liquid polarization rendering
                border = null, // Set to null to discard traditional solid borders, leaving the rendering entirely to the gradient border modifier above
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
 * Perfectly supports dynamically applying the "elegant white-feather atomization" design specification in miuix-blur mode, while falling back to native Material3 classic shimmer borders in traditional opaque modes to ensure zero extra overhead.
 */
@Composable
fun DetailInfoChip(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    // Pass global glass effect mode, defaulting to GlassEffectMode.Material
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Pass global Backdrop background blur state
    backdrop: LayerBackdrop? = null
) {
    // Detect whether the newly renamed MiuixBlur frosted glass mode is enabled and sampling source is not null
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null

    // Adaptively configure exclusive premium translucent mask base color and hairline shimmer border inside DetailInfoChip based on current system/app theme, avoiding unresolved reference errors
    val isDark = isSystemInDarkTheme()
    val localBlurBackgroundColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
    val localBlurBorderColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.12f)
    val localBlurBorder = androidx.compose.foundation.BorderStroke(0.5.dp, localBlurBorderColor)

    // Using a custom Surface instead of SuggestionChip for tighter spacing and no extra transparent clickable area
    Surface(
        modifier = modifier
            .let {
                if (isBlur) {
                    // Apply Texture Blur (Micro-sized Chip Gaussian Blur)
                    // 1. Use textureBlur for Gaussian blur rendering on the metadata chip (radius 40f to ensure text legibility), adding 0.03f noise texture.
                    // 2. Set the opacity of blendColors (0.3f for dark mode, 0.6f for light mode) as the background keynote.
                    it.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(12.dp),
                        blurRadius = 40f,
                        noiseCoefficient = 0.03f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = if (isDark) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.6f),
                                    mode = BlurBlendMode.SrcOver
                                )
                            )
                        )
                    )
                    // Apply Specular Glare (Glass Refraction Layer Overlay)
                    // 3. Chain-append a specular diagonal white physical glare refraction overlay, giving a water-droplet frosted glass aesthetic.
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.08f),
                                Color.White.copy(alpha = 0.02f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.04f)
                            )
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    // Apply Refraction Edge (Gradient Refraction Border)
                    // 4. Chain-append a 0.5.dp micro-light gradient refraction border to prevent edge sticking on large variegated backgrounds.
                    .border(
                        width = 0.5.dp,
                        brush = Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0.02f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.06f)
                                )
                            } else {
                                listOf(
                                    Color.White.copy(alpha = 0.35f),
                                    Color.White.copy(alpha = 0.08f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.18f)
                                )
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    it
                }
            },
        shape = RoundedCornerShape(12.dp),
        border = if (isBlur) {
            null // Set native border to null under blur state to delegate drawing entirely to gradient border modifier above
        } else {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = LocalContentColor.current.copy(alpha = 0.5f)
            )
        },
        color = if (isBlur) {
            Color.Transparent // Make Surface background fully transparent under blur state to avoid overlapping background colors
        } else {
            Color.Transparent
        }
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
