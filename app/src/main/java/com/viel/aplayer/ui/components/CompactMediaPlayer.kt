package com.viel.aplayer.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import coil.compose.AsyncImage
import com.viel.aplayer.ui.theme.APlayerTheme
import java.io.File

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CompactMediaPlayer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Audiobook Title",
    author: String = "Unknown Author",
    coverPath: String? = null,
    progress: () -> Float = { 0f },
    showProgressBar: Boolean = true,
    onPlayPauseClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        // Remove top rounded corners
        shape = RoundedCornerShape(0.dp)
    ) {
        Column {
            // Progress bar at the very top, not clipped
            if (showProgressBar) {
                AudioProgressBar(
                    progress = progress,
                    onProgressChange = {}, // Read-only in compact player
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    showKnob = false
                )
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val coverShape = RoundedCornerShape(8.dp)
                val coverModifier = Modifier.size(48.dp)

                Box(
                    modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            coverModifier.sharedElement(
                                rememberSharedContentState(key = "cover_${title}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ -> 
                                    tween(400, easing = FastOutSlowInEasing)
                                }
                            ).clip(coverShape)
                        }
                    } else {
                        coverModifier.clip(coverShape)
                    }
                ) {
                    if (coverPath != null && File(coverPath).exists()) {
                        AsyncImage(
                            model = File(coverPath),
                            contentDescription = "Cover",
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
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
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                
                IconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(showBackground = true)
@Composable
fun CompactMediaPlayerPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "イン・ザ・メガチャーチ",
            author = "朝井 リョウ",
            isPlaying = false,
            progress = { 0.23f }
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(showBackground = true)
@Composable
fun CompactMediaPlayerPlayingPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "イン・ザ・メガチャーチ",
            author = "朝井 リョウ",
            isPlaying = true,
            progress = { 0.65f }
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(showBackground = true)
@Composable
fun CompactMediaPlayerNoProgressBarPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "イン・ザ・メガチャーチ",
            author = "朝井 リョウ",
            isPlaying = false,
            showProgressBar = false
        )
    }
}
