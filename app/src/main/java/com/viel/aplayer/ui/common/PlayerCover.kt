package com.viel.aplayer.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys

/**
 * Adaptive player cover component.
 *
 * Owns responsive cover sizing and shared-element artwork geometry while keeping playback gestures
 * on explicit controls, so transport actions do not conflict with tabs or system gestures.
 *
 * @param coverPath The local physical file path of the cover image.
 * @param isPlaying Whether the player is currently playing and should use the expanded cover scale.
 * @param coverLastUpdated The timestamp used to invalidate the Coil cover cache.
 * @param modifier The layout modifier.
 * @param sizeRatio The cover size ratio relative to the smallest available container dimension.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerCover(
    modifier: Modifier = Modifier,
    bookId: String = "",
    isWideScreen: Boolean = false,
    coverPath: String?,
    isPlaying: Boolean,
    coverLastUpdated: Long,
    sizeRatio: Float = 1f,
    coverScene: String = "main-cover",
    sharedElementKey: String? = null,
    sharedElementVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementStartCornerRadius: Dp? = null,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val minDimension = minOf(maxWidth, maxHeight)
        val coverSize = minDimension * sizeRatio

        MainCoverView(
            bookId = bookId,
            isWideScreen = isWideScreen,
            coverPath = coverPath,
            isPlaying = isPlaying,
            coverLastUpdated = coverLastUpdated,
            coverScene = coverScene,
            sharedElementKey = sharedElementKey,
            sharedElementVisibilityScope = sharedElementVisibilityScope,
            sharedElementStartCornerRadius = sharedElementStartCornerRadius,
            modifier = Modifier.size(coverSize)
        )
    }
}

/**
 * Full-player artwork surface.
 *
 * Keeps shared-element geometry, corner interpolation, and the breathing scale on stable outer
 * bounds while rendering the current cover bitmap directly with no outgoing artwork layer.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainCoverView(
    modifier: Modifier = Modifier,
    bookId: String = "",
    isWideScreen: Boolean = false,
    coverPath: String?,
    isPlaying: Boolean,
    coverLastUpdated: Long = 0L,
    coverScene: String = "main-cover",
    /*
     * Shared Element Key Override (Independent motion channel selection)
     *
     * Uses an optional explicit key so detail artwork can participate in Home -> Detail motion
     * while the full player keeps using the existing cover_<bookId> transition key.
     */
    sharedElementKey: String? = null,
    /*
     * Shared Element Visibility Scope Override (Independent motion boundary selection)
     *
     * Uses an optional explicit visibility scope for route-owned transitions, while player covers
     * default only to the Mini->Player target scope provided by the full-player overlay.
     */
    sharedElementVisibilityScope: AnimatedVisibilityScope? = null,
    /*
     * Shared Element Start Corner Override (Source shape alignment)
     *
     * Allows route-owned artwork transitions to match their real source card radius while keeping
     * mini-player playback transitions on the existing 8.dp or 100.dp start radius.
     */
    sharedElementStartCornerRadius: Dp? = null,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerTargetScope = LocalMini2PlayerTargetScope.current
    val animatedVisibilityScope = sharedElementVisibilityScope ?: mini2PlayerTargetScope

    val startCoverCorner = sharedElementStartCornerRadius ?: if (isWideScreen) 100.dp else 8.dp
    val animatedCoverCornerRadius by animatedVisibilityScope?.transition?.animateDp(
        label = "full_cover_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 24.dp else startCoverCorner
    } ?: remember { mutableStateOf(24.dp) }

    val isKeyConsistent = remember(sharedElementKey, bookId) {
        if (bookId.isBlank()) {
            false
        } else {
            sharedElementKey?.contains(bookId) ?: true
        }
    }

    val sharedElementModifier = if (isKeyConsistent && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                /*
                 * Player Cover Key Resolution (Centralized fallback identity)
                 *
                 * Uses SharedElementKeys for the default player artwork key while still allowing
                 * route-specific callers, such as Home -> Detail, to pass an explicit override.
                 */
                rememberSharedContentState(key = sharedElementKey ?: SharedElementKeys.mini2playerCover()),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }

    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = tween(300),
        label = "cover_breathing_scale"
    )

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(sharedElementModifier)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    transformOrigin = TransformOrigin(0.5f, 0.0f)
                }
                .clip(RoundedCornerShape(animatedCoverCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            /*
             * Main Artwork Rendering (Direct current bitmap display)
             *
             * The shared element and breathing-scale modifiers stay on stable outer bounds, while
             * the inner image layer displays only the latest cover request without old/new overlap.
             */
            CoverImage(
                sourcePath = coverPath,
                lastUpdated = coverLastUpdated,
                variant = CoverImageVariant.Original,
                scene = coverScene,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                allowHardware = true,
                bitmapConfig = null
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

@Preview(name = "Player Cover - Portrait", showBackground = true, widthDp = 360, heightDp = 640)
@Preview(name = "Player Cover - Landscape", showBackground = true, widthDp = 640, heightDp = 360)
@Composable
fun PlayerCoverPreview() {
    APlayerTheme {
        PlayerCover(
            bookId = "preview",
            isWideScreen = false,
            coverPath = null,
            isPlaying = true,
            coverLastUpdated = 0L,
            sizeRatio = 0.8f
        )
    }
}
