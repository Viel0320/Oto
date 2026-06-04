package com.viel.aplayer.ui.miniplayer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.AudioProgressBar
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

@OptIn(ExperimentalFoundationApi::class)
@Composable
// Setup CompactMediaPlayer Options (Glass Effect Parameter Binding)
// Color tint coloring has been removed. Progress bar uses system Material 3 primary theme color.
// Added backdrop and glassEffectMode parameters to refract the background NavHost viewport when glass effect is enabled, matching search/detail design.
fun CompactMediaPlayer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Audiobook Title",
    author: String = "Unknown",
    narrator: String = "",
    coverPath: String? = null,
    // New cover image last updated/reconstructed timestamp to break Coil cache records
    coverLastUpdated: Long = 0L,
    progress: () -> Float = { 0f },
    showProgressBar: Boolean = true,
    isMediaAvailable: Boolean = true,
    actions: MiniPlayerActions = MiniPlayerActions(),
    // Change the shared blur state to miuix-blur's LayerBackdrop sampling source parameter
    backdrop: LayerBackdrop? = null,
    // New onClick parameter to take over mini-player full-screen expanding clicks, handled at the outermost Surface to get M3 ripples
    onClick: () -> Unit = {},
    // New glassEffectMode parameter to distinguish between frosted glass Gaussian blur and standard Material solid background
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            // Compact player owns reload-time availability handling and exits when the restored file is gone.
            actions.onUnavailable()
        }
    }

    // Align to newly renamed MiuixBlur, checking if frosted glass rendering is enabled based on LayerBackdrop and the new enum
    val isBlurMode = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null

    // Get light/dark theme status of current system to achieve frosted glass adaptation
    val isDark = isSystemInDarkTheme()

    // Surface Click Handler (Unified Ripple Wave Ripple)
    // Modify Surface to support onClick, and bind the incoming onClick action directly.
    // This makes the compact player card fully clickable with consistent Material 3 ripple animations.
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (isBlurMode) {
                    // Use let chain dynamic binding exactly consistent with Pill player for high-fidelity textureBlur rendering
                    it.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(0.dp),
                        blurRadius = 60f,
                        noiseCoefficient = 0.05f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.76f),
                                    mode = BlurBlendMode.SrcOver
                                )
                            )
                        )
                    )
                } else {
                    it
                }
            },
        color = if (isBlurMode) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(0.dp)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (showProgressBar) {
                // Discarded cover color mapping of progress bar; no color attribute passed, making progress bar return to Material 3 primary color
                AudioProgressBar(
                    progress = progress,
                    onProgressChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    showKnob = false
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = actions.onHide
                        )
                ) {
                    if (coverPath != null) {
                        val context = LocalContext.current
                        // Thumbnail Small Caching (Reuse Small List Image Cache)
                        // Mini player uses ThumbnailSmall specification for small persistent cover; skips sync File.exists() to prevent disk I/O on recomposition.
                        val request = remember(coverPath, coverLastUpdated) {
                            CoverImageRequestFactory.build(
                                context = context,
                                sourcePath = coverPath,
                                lastUpdated = coverLastUpdated,
                                variant = CoverImageVariant.ThumbnailSmall,
                                scene = "compact-player-cover"
                            )
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = formatPeopleSubtitle(
                            author.takeIf { it.isNotBlank() } ?: "Unknown",
                            narrator.takeIf { it.isNotBlank() } ?: "Unknown"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = actions.onPlayPauseClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            isPlaying = false,
            progress = { 0.23f }
        )
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerPlayingPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            isPlaying = true,
            progress = { 0.65f }
        )
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerNoProgressBarPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            isPlaying = false,
            showProgressBar = false
        )
    }
}

@Preview(showBackground = true, name = "Long Title & Narrator", apiLevel = 36)
@Composable
fun CompactMediaPlayerLongTitlePreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "A Very Long Audiobook Title That Should Marquee Inside The Mini Player",
            author = "Long Author Name",
            narrator = "Narrator One, Narrator Two, Narrator Three",
            isPlaying = true,
            progress = { 0.45f }
        )
    }
}

@Preview(showBackground = true, name = "Author Only", apiLevel = 36)
@Composable
fun CompactMediaPlayerAuthorOnlyPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "Dawn Star",
            author = "Kanae Minato",
            narrator = "",
            isPlaying = false,
            progress = { 0.12f }
        )
    }
}
