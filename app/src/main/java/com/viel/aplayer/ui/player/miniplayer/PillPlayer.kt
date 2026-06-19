package com.viel.aplayer.ui.player.miniplayer

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState and modifiers.
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
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImage
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import com.viel.aplayer.ui.player.MiniPlayerActions
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
    // Setup HazeState Parameter (Map backdrop parameter to HazeState) Changed LayerBackdrop to HazeState.
    hazeState: HazeState? = null,
    onClick: () -> Unit = {},
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerSourceScope = LocalMini2PlayerSourceScope.current
    val windowClass = LocalAppWindowSizeClass.current

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
        ?: remember { mutableStateOf(100.dp) }

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
        ?: remember { mutableStateOf(100.dp) }

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

    // Theme Aware Rotation Border (Use LocalDarkTheme to resolve active theme state instead of system defaults) Read theme preference state.
    val isDark = LocalDarkTheme.current

    Surface(
        onClick = onClick,
        modifier = modifier
            .then(boundsModifier)
            // Clean Layout Modifiers (Remove redundant fillMaxWidth, wrapContentWidth, and hardcoded widthIn constraints to allow natural capsule width wrap)
            // The parent layout container manages position alignment, so wrapping constraints internally is redundant and hampers responsive layouts.
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
                    /*
                     * Pill Source Artwork Rendering (Direct mini-only rotating artwork)
                     *
                     * The shared-element, border, click, and corner modifiers stay on the stable outer node.
                     * Only this inner artwork decoration reads the rotation value, so the shared transition
                     * receives a non-rotating geometry node while the mini player still looks like a spinning disc.
                     */
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
                    // Compact Transport Target (Preserve the pill's small visual control while restoring a 48.dp accessible command area)
                    // The IconButton owns focus, touch, and Switch Access bounds, and the inner glyph keeps the compact transport appearance.
                    modifier = Modifier.size(48.dp)
                ) {
                    // Animated play <-> pause glyph driven by isPlaying, morphing
                    // via the shared avd_play_pause asset and tinted with onSurface.
                    val playPauseImage = AnimatedImageVector.animatedVectorResource(R.drawable.avd_play_pause)
                    val playPausePainter = rememberAnimatedVectorPainter(
                        animatedImageVector = playPauseImage,
                        atEnd = isPlaying
                    )
                    Image(
                        painter = playPausePainter,
                        contentDescription = playPauseContentDescription,
                        modifier = Modifier.size(28.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
        }
    }
}
