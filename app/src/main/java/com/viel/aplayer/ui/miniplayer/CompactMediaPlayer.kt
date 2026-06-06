package com.viel.aplayer.ui.miniplayer

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState and modifiers.
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.AudioProgressBar
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

@OptIn(ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CompactMediaPlayer(
    bookId: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Audiobook Title",
    author: String = "Unknown",
    narrator: String = "",
    coverPath: String? = null,
    coverLastUpdated: Long = 0L,
    progress: () -> Float = { 0f },
    showProgressBar: Boolean = true,
    isMediaAvailable: Boolean = true,
    actions: MiniPlayerActions = MiniPlayerActions(),
    // Setup HazeState Parameter (Map backdrop parameter to HazeState) Changed LayerBackdrop to HazeState.
    hazeState: HazeState? = null,
    onClick: () -> Unit = {},
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Color Extracted Callback (Pass color callback to upstream MiniPlayerOverlay)
    // Invoked when Coil successfully decodes the Bitmap cover and retrieves its dominant color.
    onColorExtracted: ((Color) -> Unit)? = null,
) {
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            actions.onUnavailable()
        }
    }

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerSourceScope = LocalMini2PlayerSourceScope.current

    /*
     * Outer Card Corner Radius Transition (Dynamic bounds shape interpolation)
     * Transition the outer card corner radius from the compact bar's 0.dp to the full screen's 28.dp.
     * Use target state checking to apply rounded corners when fully morphed, preventing straight corner overflow.
     */
    // Align transition durations: Set compact outer bounds corner radius transition to 300ms.
    val animatedCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "compact_bounds_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 0.dp else 28.dp
    }
        ?: remember { androidx.compose.runtime.mutableStateOf(0.dp) }

    /*
     * Thumbnail Cover Corner Radius Transition (Smooth inner artwork morphing)
     * Interpolate the artwork cover corner radius between the compact card's 8.dp and
     * the full screen player's 24.dp, ensuring no visual pixel steps occur.
     */
    // Align transition durations: Set compact artwork cover corner radius transition to 300ms.
    val animatedCoverCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "compact_cover_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 8.dp else 24.dp
    }
        ?: remember { androidx.compose.runtime.mutableStateOf(8.dp) }

    val boundsModifier = if (sharedTransitionScope != null && mini2PlayerSourceScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                /*
                 * Compact Player Bounds Key (Centralized shared bounds identity)
                 *
                 * Resolves the bottom-bar mini-player bounds key through SharedElementKeys while
                 * preserving the existing bounds_<bookId> transition identity.
                */
                sharedContentState = rememberSharedContentState(key = SharedElementKeys.playerBounds()),
                animatedVisibilityScope = mini2PlayerSourceScope,
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(animatedCornerRadius))
            )
        }
    } else {
        Modifier
    }

    // Setup Haze Mode Switch (Check if Haze mode is configured) Aligned to renamed Haze option.
    val isBlurMode = glassEffectMode == GlassEffectMode.Haze && hazeState != null

    Surface(
        onClick = onClick,
        modifier = modifier
            .then(boundsModifier)
            .fillMaxWidth()
            .let {
                if (isBlurMode) {
                    val compactShape = RoundedCornerShape(animatedCornerRadius)
                    it
                        .clip(compactShape)
                        .liquidGlassCompatEffect(
                            state = hazeState,
                            style = LiquidGlassStyle(
                                // Adaptive Glass Tint: Fallback to theme-based 12% tint (White in Dark, Black in Light) by leaving it Unspecified.
                                specularIntensity = 0.4f,
                                ambientResponse = 0.5f,
                                shape = compactShape
                            )
                        )
                } else {
                    it
                }
            },
        color = if (isBlurMode) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(animatedCornerRadius)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (showProgressBar) {
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
                val coverModifier = if (sharedTransitionScope != null && mini2PlayerSourceScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            /*
                             * Compact Player Cover Key (Centralized artwork identity)
                             *
                             * Resolves the mini-player artwork key through SharedElementKeys so it
                             * stays aligned with the full-player MainCoverView key generation.
                            */
                            rememberSharedContentState(key = SharedElementKeys.mini2playerCover(bookId)),
                            animatedVisibilityScope = mini2PlayerSourceScope
                        )
                    }
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(coverModifier)
                        .clip(RoundedCornerShape(animatedCoverCornerRadius))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = actions.onHide
                        )
                ) {
                    if (coverPath != null) {
                        val context = LocalContext.current
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
                            contentScale = ContentScale.Crop,
                            onSuccess = { successResult ->
                                val colorInt = com.viel.aplayer.media.parser.ImageProcessor.getDominantColorFromDrawable(successResult.result.drawable)
                                // Cache Calculated Color: Write the extracted dominant color into the main process LruCache to speed up future renders.
                                com.viel.aplayer.media.parser.ImageProcessor.putColorToCache(coverPath, colorInt)
                                onColorExtracted?.invoke(Color(colorInt))
                            }
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
