package com.viel.aplayer.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import kotlin.math.abs

/**
 * Adaptive gesture player cover component (PlayerCover).
 *
 * Extracted BoxWithConstraints and its nested gesture listening and size calculation logic from PlayerScreen.kt,
 * encapsulating them into a unified and highly reusable PlayerCover component to achieve layout hierarchy decoupling and performance optimization.
 *
 * @param coverPath The local physical file path of the cover image.
 * @param isPlaying Whether the player is currently playing (affects the scale animation).
 * @param coverLastUpdated The timestamp when the cover file was last updated, used to invalidate Coil cache.
 * @param onAdjustVolume Callback triggered when adjusting the volume.
 * @param onNextChapter Callback triggered when skipping to the next chapter.
 * @param onPreviousChapter Callback triggered when skipping to the previous chapter.
 * @param modifier The layout modifier.
 * @param sizeRatio The ratio of the cover size relative to the minimum dimension of the container, defaulting to 0.8f (80%).
 * @param gesturesEnabled Whether gesture operations on the cover (volume adjustment and chapter skipping) are enabled, defaulting to true.
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
    onAdjustVolume: (Float) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    sizeRatio: Float = 0.8f,
    gesturesEnabled: Boolean = true,
    coverScene: String = "main-cover",
    /*
     * Shared Element Key Override (Route-specific artwork transition identity)
     *
     * Allows callers such as Home -> Detail to bind the same cover image to a route-specific
     * shared-element key without changing the player and mini-player artwork transition contract.
     */
    sharedElementKey: String? = null,
    /*
     * Shared Element Visibility Scope Override (Route-specific animation boundary)
     *
     * Lets route transitions pass their own destination AnimatedVisibilityScope so detail covers
     * do not accidentally reuse the mini-to-player target scope owned by PlayerOverlay.
     */
    sharedElementVisibilityScope: AnimatedVisibilityScope? = null,
    /*
     * Shared Element Start Corner Override (Route-specific source shape)
     *
     * Lets non-player transitions provide their own source corner radius so Home recent covers
     * start from 16.dp instead of falling back to the mini-player's 8.dp playback shape.
     */
    sharedElementStartCornerRadius: Dp? = null,
    // Add onColorExtracted lambda (Support propagating dynamic cover color) Pass color extraction callback to downstream cover views.
    onColorExtracted: ((Color) -> Unit)? = null
) {
    // Use BoxWithConstraints to dynamically capture the maximum available width and height of the parent container, ensuring perfect adaptivity in portrait, landscape, or split-screen modes.
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Compare available width and height, selecting the sizeRatio of the smaller dimension as the cover size to prevent size overflow and maintain visual breathing room.
        val minDimension = minOf(maxWidth, maxHeight)
        val coverSize = minDimension * sizeRatio

        // State variables to record cumulative horizontal drag to trigger chapter-skipping gestures.
        var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
        var hasTriggeredHorizontalDrag by remember { mutableStateOf(false) }

        // Construct the gesture recognition modifier. The pointerInput logic is appended only when gesturesEnabled is true.
        val gestureModifier = if (gesturesEnabled) {
            Modifier.pointerInput(Unit) {
                // Detect gestures: drag up/down to adjust volume, drag left/right to skip chapters.
                detectDragGestures(
                    onDragStart = {
                        totalHorizontalDrag = 0f
                        hasTriggeredHorizontalDrag = false
                    },
                    onDragEnd = {
                        totalHorizontalDrag = 0f
                        hasTriggeredHorizontalDrag = false
                    },
                    onDragCancel = {
                        totalHorizontalDrag = 0f
                        hasTriggeredHorizontalDrag = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (abs(dragAmount.y) > abs(dragAmount.x)) {
                            // Trigger the volume adjustment callback on vertical dragging
                            onAdjustVolume(-dragAmount.y * 0.002f)
                        } else if (!hasTriggeredHorizontalDrag) {
                            totalHorizontalDrag += dragAmount.x
                            if (abs(totalHorizontalDrag) > 300f) {
                                // Trigger the chapter skipping callback when horizontal drag exceeds the threshold (300px)
                                if (totalHorizontalDrag > 0) {
                                    onNextChapter()
                                } else {
                                    onPreviousChapter()
                                }
                                hasTriggeredHorizontalDrag = true
                            }
                        }
                    }
                )
            }
        } else {
            Modifier
        }

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
            // Add onColorExtracted lambda (Support propagating dynamic cover color) Forward color callback downstream.
            onColorExtracted = onColorExtracted,
            modifier = Modifier
                .size(coverSize)
                .then(gestureModifier)
        )
    }
}

/**
 * Main cover view of the full screen player.
 *
 * Extracted from PlayerScreen.kt as an independent component, responsible for displaying the audiobook cover image
 * and implementing a subtle scaling animation when switching between play and pause states.
 *
 * @param coverPath The local physical file path of the cover image.
 * @param isPlaying Whether the player is currently playing (affects the scale animation).
 * @param coverLastUpdated The timestamp when the cover file was last updated, used to invalidate Coil cache to trigger an immediate refresh after cover self-healing reconstruction.
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
     * Allows route-owned artwork transitions to match their real source card radius while
     * keeping mini-player playback transitions on the existing 8.dp or 100.dp start radius.
     */
    sharedElementStartCornerRadius: Dp? = null,
    // Add onColorExtracted lambda (Support propagating dynamic cover color) Accept color extraction callback on main cover.
    onColorExtracted: ((Color) -> Unit)? = null
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = sharedElementVisibilityScope
        ?: LocalMini2PlayerTargetScope.current

    /*
     * Start Cover Corner Resolution (Transition source shape selection)
     *
     * Uses a route-specific override when supplied, otherwise preserves the playback-specific
     * mini-player start radius chosen from compact bar or wide pill layout.
     */
    val startCoverCorner = sharedElementStartCornerRadius ?: if (isWideScreen) 100.dp else 8.dp

    /*
     * Artwork Cover Corner Radius Transition (Dynamic round corner interpolation)
     * Interpolate the artwork cover corner radius between the compact card (Bar: 8.dp / Pill: 100.dp)
     * and the full screen player's target corner radius (24.dp).
     */
    // Align transition durations: Set artwork cover corner radius transition spec to 300ms to align with other page transitions.
    val animatedCoverCornerRadius by animatedVisibilityScope?.transition?.animateDp(
        label = "full_cover_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 24.dp else startCoverCorner
    }
        ?: remember { mutableStateOf(24.dp) }

    val sharedElementModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                /*
                 * Player Cover Key Resolution (Centralized fallback identity)
                 *
                 * Uses SharedElementKeys for the default player artwork key while still allowing
                 * route-specific callers, such as Home -> Detail, to pass an explicit override.
                 */
                rememberSharedContentState(key = sharedElementKey ?: SharedElementKeys.mini2playerCover(bookId)),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }

    // Scale the cover to 1.0 when playing, and shrink to 0.95 when paused, cooperating with a 300ms animation to create a breathing sensation.
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = tween(300),
        label = "cover_breathing_scale"
    )

    Box(
        modifier = modifier
            // Adjust full width layout.
            //
            // According to the latest requirements to "fully stretch the width" of the cover in the left column of the play page without leaving excess horizontal empty edges, and to support weight(1f) adaptive stretch placeholder below it,
            // we change the outermost Box dimensions from .fillMaxSize() to .fillMaxWidth() and remove the horizontal / vertical padding that hindered stretching.
            .fillMaxWidth(),
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
            // Define local state to track whether asynchronous cover loading failed.
            // Combined with Coil's onError callback, this completely eliminates the risk of disk I/O blocking from synchronous File.exists() calls on the main thread,
            // and handles cases where files physically exist but are corrupt and cannot be decoded, automatically transitioning smoothly to a placeholder.
            var isImageError by remember(coverPath) { mutableStateOf(false) }

            if (coverPath != null && !isImageError) {
                val context = LocalContext.current
                // The main cover uniformly uses the Main1200 specification, allowing the details page and playback page to share the same dimensions and cache key.
                // If the main cover specification needs to be increased or decreased in the future, only the request factory and enum need to be modified, instead of editing multiple UI files individually.
                val request = remember(coverPath, coverLastUpdated) {
                    // coverScene is passed by the caller to differentiate main cover sources like the player, details page, or edit page.
                    // When multiple Main1200 Bitmaps exist concurrently in the profiler, the actual holding scene can be traced back through logs.
                    CoverImageRequestFactory.build(
                        context = context,
                        sourcePath = coverPath,
                        lastUpdated = coverLastUpdated,
                        variant = CoverImageVariant.Main1200,
                        scene = coverScene
                    )
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    // Intercept onSuccess load (Extract dominant color directly from memory-based Drawable) Retrieve Drawable from Coil result and propagate color.
                    onSuccess = { successResult ->
                        val colorInt = com.viel.aplayer.media.parser.ImageProcessor.getDominantColorFromDrawable(successResult.result.drawable)
                        // Cache Calculated Color: Write the extracted dominant color into the main process LruCache to speed up future renders.
                        com.viel.aplayer.media.parser.ImageProcessor.putColorToCache(coverPath, colorInt)
                        onColorExtracted?.invoke(Color(colorInt))
                    },
                    onError = {
                        // Main cover component degradation.
                        //
                        // The main cover component only retains UI degradation responsibilities; loading failure exception types,
                        // decodeCostMs, and cacheHitRatio are already logged by the unified request listener to avoid duplicate logs and conflicting metrics.
                        isImageError = true
                    }
                )
            } else {
                // Present a unified placeholder background + play icon when the cover path is empty or loading errors occur, aligning visually with the details page specifications.
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
        // Simulate PlayerCover in Preview. Since the preview cannot simulate real gesture interactions,
        // it is primarily used here to verify the adaptive scaling effect of the cover under different screen aspect ratios (portrait vs landscape).
        PlayerCover(
            bookId = "preview",
            isWideScreen = false,
            coverPath = null, // Default Cover Placeholder (Pass null to display the default cover art placeholder)
            isPlaying = true,
            coverLastUpdated = 0L,
            onAdjustVolume = {},
            onNextChapter = {},
            onPreviousChapter = {},
            sizeRatio = 0.8f
        )
    }
}
