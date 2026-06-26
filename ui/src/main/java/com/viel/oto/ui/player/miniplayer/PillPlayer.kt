package com.viel.oto.ui.player.miniplayer

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.CoverImage
import com.viel.oto.ui.common.CoverImageVariant
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.LocalDarkTheme
import com.viel.oto.ui.motion.LocalMini2PlayerSourceScope
import com.viel.oto.ui.motion.LocalSharedTransitionScope
import com.viel.oto.ui.motion.SharedElementKeys
import com.viel.oto.ui.player.MiniPlayerActions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Pill Compact Media Player: A floating stadium-shaped mini player that overlays at the bottom of the screen.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class, ExperimentalAnimationGraphicsApi::class)
@Composable
fun PillCompactMediaPlayer(
    bookId: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    coverPath: String? = null,
    coverLastUpdated: Long = 0L,
    actions: MiniPlayerActions = MiniPlayerActions(),
    hazeState: HazeState? = null,
    onClick: () -> Unit = {},
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerSourceScope = LocalMini2PlayerSourceScope.current
    val windowClass = LocalAppWindowSizeClass.current

    val animatedCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "pill_bounds_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 100.dp else 28.dp
    }
        ?: remember { mutableStateOf(100.dp) }

    val animatedCoverCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "pill_cover_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 100.dp else 24.dp
    }
        ?: remember { mutableStateOf(100.dp) }

    val boundsModifier = if (sharedTransitionScope != null && mini2PlayerSourceScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = SharedElementKeys.playerBounds()),
                animatedVisibilityScope = mini2PlayerSourceScope,
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(animatedCornerRadius))
            )
        }
    } else {
        Modifier
    }

    /**
     * Shares the play/pause glyph with the full player's transport button, matching the compact
     * variant via SharedElementKeys.mini2playerPlayPause(). The pill surface keeps its default
     * sharedBounds morph; only the icon is connected so it flies and scales (28dp -> 40dp) into the
     * full player instead of cross-fading in place.
     */
    val playPauseSharedModifier = if (sharedTransitionScope != null && mini2PlayerSourceScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = SharedElementKeys.mini2playerPlayPause()),
                animatedVisibilityScope = mini2PlayerSourceScope,
                boundsTransform = { _, _ -> tween(durationMillis = 300) }
            )
        }
    } else {
        Modifier
    }

    val isBlurMode = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    val pillShape = RoundedCornerShape(animatedCornerRadius)
    val coverContentDescription = stringResource(R.string.media_cover_content_description)
    val playPauseContentDescription = stringResource(
        if (isPlaying) R.string.playback_pause_content_description else R.string.playback_play_content_description
    )
    val openMiniPlayerActionLabel = stringResource(R.string.mini_player_open_action)
    val hideMiniPlayerActionLabel = stringResource(R.string.mini_player_hide_action)

    val rotation = remember { Animatable(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                rotation.snapTo(rotation.value % 360f)
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 15000, easing = LinearEasing)
                )
            }
        }
    }

    val isDark = LocalDarkTheme.current

    Surface(
        onClick = onClick,
        modifier = modifier
            .then(boundsModifier)
            .padding(horizontal = windowClass.screenHorizontalPadding)
            .navigationBarsPadding()
            .let {
                if (isBlurMode) {
                    it
                        .clip(pillShape)
                        .hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin()
                        )
                } else {
                    it
                }
            },
        color = if (isBlurMode) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = pillShape,
        border = null,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isKeyConsistent = bookId.isNotBlank()

                val coverModifier = if (isKeyConsistent && sharedTransitionScope != null && mini2PlayerSourceScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = SharedElementKeys.mini2playerCover()),
                            animatedVisibilityScope = mini2PlayerSourceScope
                        )
                    }
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .then(coverModifier)
                        .clip(RoundedCornerShape(animatedCoverCornerRadius))
                        .let {
                            if (isBlurMode) {
                                it.border(
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
                                    shape = RoundedCornerShape(animatedCoverCornerRadius)
                                )
                            } else {
                                it
                            }
                        }
                        .combinedClickable(
                            onClickLabel = openMiniPlayerActionLabel,
                            onClick = onClick,
                            onLongClickLabel = hideMiniPlayerActionLabel,
                            onLongClick = actions.onHide
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = rotation.value
                            }
                    ) {
                        CoverImage(
                            sourcePath = coverPath,
                            lastUpdated = coverLastUpdated,
                            variant = CoverImageVariant.ThumbnailSmall,
                            scene = "pill-player-cover",
                            contentDescription = coverContentDescription,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            allowHardware = true,
                            bitmapConfig = null
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                IconButton(
                    onClick = actions.onPlayPauseClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    val playPauseImage = AnimatedImageVector.animatedVectorResource(R.drawable.avd_play_pause)
                    val playPausePainter = rememberAnimatedVectorPainter(
                        animatedImageVector = playPauseImage,
                        atEnd = isPlaying
                    )
                    Image(
                        painter = playPausePainter,
                        contentDescription = playPauseContentDescription,
                        modifier = Modifier
                            .size(28.dp)
                            .then(playPauseSharedModifier),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
        }
    }
}
