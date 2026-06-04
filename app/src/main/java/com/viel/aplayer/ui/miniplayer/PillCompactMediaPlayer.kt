package com.viel.aplayer.ui.miniplayer

// Import widthIn modifier to constrain the maximum width of the floating pill player on wide viewports.
// Import wrapContentWidth modifier to enable responsive content-based sizing for the pill container.
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * Pill Compact Media Player: A floating stadium-shaped mini player that overlays at the bottom of the screen.
 *
 * Design features:
 * 1. Employs a stadium-shaped card structure floating above core layouts.
 * 2. Integrates with MiuixBlur. Wraps boundaries in clipped round corners to prevent pixel artifacts,
 *    and overlays a translucent background tint for enhanced visual legibility.
 * 3. Draws a progress indicator track manually via Canvas, ending in a tiny balance anchor.
 * 4. Shapes the cover graphic using a 100.dp circular boundary. Employs clean flat button icons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillCompactMediaPlayer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    coverPath: String? = null,
    // Cover timestamp (Flipped to break Coil image caching on localized metadata rebuilds)
    coverLastUpdated: Long = 0L,
    isMediaAvailable: Boolean = true,
    actions: MiniPlayerActions = MiniPlayerActions(),
    // Backdrop capture source (MiuixBlur sampling layer reference)
    backdrop: LayerBackdrop? = null,
    // Click gesture handler (Binds action to outer Surface to contain the ripple inside stadium boundaries)
    onClick: () -> Unit = {},
    // Glassmorphic mode configuration flag
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            // Terminate playback gracefully if underlying media assets are missing
            actions.onUnavailable()
        }
    }

    // Evaluate if MiuixBlur parameters are met to activate glassmorphism rendering
    val isBlurMode = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null
    val pillShape = RoundedCornerShape(100.dp)

    // Animation controller (Uses Animatable instead of InfiniteTransition to secure state control)
    // Allows playback pauses to lock the cover at its current rotation angle without snaps or resets.
    val rotation = remember { Animatable(0f) }

    // Rotation Loop (Increments rotation angles sequentially while media plays)
    // LaunchedEffect cancellation halts the loop, achieving a smooth "pause-in-place" action.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                // Modulo check (Resets angle bounds to [0, 360) to prevent precision decay during long play sessions)
                rotation.snapTo(rotation.value % 360f)
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 15000, easing = LinearEasing)
                )
            }
        }
    }

    // Map current rotation value directly to UI layer transforms
    val currentRotation = rotation.value

    // Fetch theme mode to dispatch matching color values
    val isDark = isSystemInDarkTheme()

    // Surface Click Binding (Restricts clickable hot-spots inside the pill contour)
    // Guarantees that outer margins and navigation padding zones ignore click events.
    // Creates a bounded stadium-shaped ripple effect to match design criteria.
    Surface(
        onClick = onClick,
        modifier = modifier
            // 1. Extends total boundary allocations horizontally.
            // 2. Fits container width dynamically to internal children and aligns it rightwards.
            .fillMaxWidth()
            .wrapContentWidth(Alignment.End)
            .widthIn(max = 400.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp) // Margin to let card float above screens
            .navigationBarsPadding() // Securely bypass system navigation bars
            .let {
                if (isBlurMode) {
                    // 1. Renders high-fidelity Gaussian blur (60f) with a fine frosted texture (0.05f).
                    // 2. Adjusts mix layer blending (0.35f dark / 0.65f light) to secure correct contrast.
                    it.textureBlur(
                        backdrop = backdrop,
                        shape = pillShape,
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
                    // 3. Overlays a linear specular reflection layer to simulate 3D glass physics.
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.White.copy(alpha = 0.03f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.06f)
                            )
                        ),
                        shape = pillShape
                    )
                    // 4. Applies a subtle refraction rim border (1.dp) to separate the capsule from the background.
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
                        shape = pillShape
                    )
                } else {
                    it
                }
            },
        // Background Tinting Alignment (Matches regular compact player attributes)
        // Keeps the container color transparent under blur mode to avoid occlusion,
        // using the standard surfaceVariant values otherwise.
        color = if (isBlurMode) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        // Boundary Clean-up (Removes all physical edge borders)
        // 1. Explicitly sets border parameter to null.
        // 2. Forces shadow elevation to 0.dp to eliminate halo visual artifacts.
        shape = pillShape,
        border = null,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                // Fit content constraints locally, keeping standard padding margins.
                .padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    // Wrap-width adjustment. Spreads vertical spacing to center components.
                    .padding(vertical = 12.dp)
                    .align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover image: Maps round corners and uses `coverLastUpdated` as a cache invalidation stamp.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        // Apply rotation transform matrix to simulate vinyl records rotating
                        .graphicsLayer { rotationZ = currentRotation }
                        .clip(RoundedCornerShape(100.dp))
                        // Applies a 1.dp crystalline refraction border in blur mode.
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
                                    shape = RoundedCornerShape(100.dp)
                                )
                            } else {
                                it
                            }
                        }
                        // Cover Gesture Mapping (Binds standard click signals to open player view)
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = actions.onHide
                        )
                ) {
                    if (coverPath != null) {
                        val context = LocalContext.current
                        // Thumbnail Scaling (Requests small-scale thumbnails to improve loading speeds)
                        val request = remember(coverPath, coverLastUpdated) {
                            CoverImageRequestFactory.build(
                                context = context,
                                sourcePath = coverPath,
                                lastUpdated = coverLastUpdated,
                                variant = CoverImageVariant.ThumbnailSmall,
                                scene = "pill-player-cover"
                            )
                        }
                        // Request construction uses the factory pattern to keep keys uniform.
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

                // Playback Control Button (Clean flat layout without circular background borders)
                IconButton(
                    onClick = actions.onPlayPauseClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(28.dp),
                        // Icon Tint (Uses standard onSurface color scheme to match light/dark views)
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
