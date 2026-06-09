package com.viel.aplayer.ui.miniplayer

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState and modifiers.
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * Pill Compact Media Player: A floating stadium-shaped mini player that overlays at the bottom of the screen.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PillCompactMediaPlayer(
    bookId: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    coverPath: String? = null,
    coverLastUpdated: Long = 0L,
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
     * Pill Bounds Corner Radius Transition (Dynamic stadium shape morphing)
     * Transition the outer card shape from stadium (100.dp) down to the target full screen's 28.dp.
     * Prevents flat shapes or straight corners during intermediate animation frames.
     */
    // Align transition durations: Set pill outer stadium bounds corner radius transition to 300ms.
    val animatedCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "pill_bounds_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 100.dp else 28.dp
    }
        ?: remember { androidx.compose.runtime.mutableStateOf(100.dp) }

    /*
     * Pill Cover Corner Radius Transition (Stretches disk shapes into squares)
     * Morph the compact rotating disk artwork corner radius (100.dp for circle)
     * down to the full screen's rounded square artwork corner radius (24.dp).
     */
    // Align transition durations: Set rotating disk cover corner radius transition to 300ms.
    val animatedCoverCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "pill_cover_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 100.dp else 24.dp
    }
        ?: remember { androidx.compose.runtime.mutableStateOf(100.dp) }

    val boundsModifier = if (sharedTransitionScope != null && mini2PlayerSourceScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                /*
                 * Pill Player Bounds Key (Centralized shared bounds identity)
                 *
                 * Resolves the wide-screen pill mini-player bounds key through SharedElementKeys
                 * with the current book ID, matching the book-scoped mini-to-full player transition.
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
    val pillShape = RoundedCornerShape(animatedCornerRadius)
    // Localized Player Accessibility Copy (Keep mini-player icon labels aligned with the selected app language)
    // The pill variant has no visible text, so content descriptions are the only user-facing copy in this component.
    val coverContentDescription = stringResource(R.string.media_cover_content_description)
    val playPauseContentDescription = stringResource(
        if (isPlaying) R.string.playback_pause_content_description else R.string.playback_play_content_description
    )
    // Pill Mini Player Assistive Actions (Name cover click and hide gestures)
    // The pill layout exposes only artwork and transport icons, so cover semantics must carry the open and hide commands explicitly.
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

    val currentRotation = rotation.value
    // Theme Aware Rotation Border (Use LocalDarkTheme to resolve active theme state instead of system defaults) Read theme preference state.
    val isDark = com.viel.aplayer.ui.common.theme.LocalDarkTheme.current

    Surface(
        onClick = onClick,
        modifier = modifier
            .then(boundsModifier)
            .fillMaxWidth()
            .wrapContentWidth(Alignment.End)
            .widthIn(max = 400.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .let {
                if (isBlurMode) {
                    it
                        .clip(pillShape)
                        .liquidGlassCompatEffect(
                            state = hazeState,
                            style = LiquidGlassStyle(
                                // Adaptive Glass Tint: Fallback to theme-based 12% tint (White in Dark, Black in Light) by leaving it Unspecified.
                                specularIntensity = 0.4f,
                                ambientResponse = 0.5f,
                                shape = pillShape
                            )
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
                // Transition Key Consistency Validation (Prevent invalid or empty shared transition keys)
                // Ensures that the bookId is non-blank before attempting to apply the shared element transition.
                // If the bookId is empty, falls back to a normal transition.
                val isKeyConsistent = bookId.isNotBlank()

                val coverModifier = if (isKeyConsistent && sharedTransitionScope != null && mini2PlayerSourceScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            /*
                             * Pill Player Cover Key (Centralized artwork identity)
                             *
                             * Resolves the pill artwork key through SharedElementKeys so it stays
                             * aligned with full-player MainCoverView key generation.
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
                        .graphicsLayer { rotationZ = currentRotation }
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
                    if (coverPath != null) {
                        val context = LocalContext.current
                        val request = remember(coverPath, coverLastUpdated) {
                            CoverImageRequestFactory.build(
                                context = context,
                                sourcePath = coverPath,
                                lastUpdated = coverLastUpdated,
                                variant = CoverImageVariant.ThumbnailSmall,
                                scene = "pill-player-cover"
                            )
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = coverContentDescription,
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

                IconButton(
                    onClick = actions.onPlayPauseClick,
                    // Compact Transport Target (Preserve the pill's small visual control while restoring a 48.dp accessible command area)
                    // The IconButton owns focus, touch, and Switch Access bounds, and the inner glyph keeps the compact transport appearance.
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = playPauseContentDescription,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
