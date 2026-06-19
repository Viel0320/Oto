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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceBookId
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys

/**
 * Identifies a single thumbnail bridge between a mini-player source cover and full-player target.
 *
 * The bridge is keyed by book identity rather than file path so normal cover cache updates cannot
 * re-arm the mini-origin thumbnail handoff inside the same mounted full-player transition.
 */
private data class MiniPlayerCoverBridgeIdentity(
    val sourceBookId: String,
    val targetBookId: String
)

/**
 * Adaptive player cover component (PlayerCover).
 *
 * Owns responsive cover sizing and shared-element artwork rendering while leaving
 * gestures, so transport actions remain discoverable and do not conflict with
 * tab swipes or system gestures.
 *
 * @param coverPath The local physical file path of the cover image.
 * @param transitionCoverPath Optional low-resolution artwork used only as a mismatched mini-source
 * bridge before the one-shot high-resolution fade.
 * @param isPlaying Whether the player is currently playing (affects the scale animation).
 * @param coverLastUpdated The timestamp when the cover file was last updated, used to invalidate Coil cache.
 * @param modifier The layout modifier.
 * @param sizeRatio The ratio of the cover size relative to the minimum dimension of the container, defaulting to 0.8f (80%).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerCover(
    modifier: Modifier = Modifier,
    bookId: String = "",
    isWideScreen: Boolean = false,
    coverPath: String?,
    transitionCoverPath: String? = coverPath,
    isPlaying: Boolean,
    coverLastUpdated: Long,
    sizeRatio: Float = 1f,
    coverScene: String = "main-cover",
    sharedElementKey: String? = null,
    sharedElementVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementStartCornerRadius: Dp? = null,
) {
    // Use BoxWithConstraints to dynamically capture the maximum available width and height of the parent container, ensuring perfect adaptivity in portrait, landscape, or split-screen modes.
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Compare available width and height, selecting the sizeRatio of the smaller dimension as the cover size to prevent size overflow and maintain visual breathing room.
        val minDimension = minOf(maxWidth, maxHeight)
        val coverSize = minDimension * sizeRatio

        MainCoverView(
            bookId = bookId,
            isWideScreen = isWideScreen,
            coverPath = coverPath,
            transitionCoverPath = transitionCoverPath,
            isPlaying = isPlaying,
            coverLastUpdated = coverLastUpdated,
            coverScene = coverScene,
            sharedElementKey = sharedElementKey,
            sharedElementVisibilityScope = sharedElementVisibilityScope,
            sharedElementStartCornerRadius = sharedElementStartCornerRadius,
            modifier = Modifier
                .size(coverSize)
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
 * @param transitionCoverPath Optional low-resolution artwork used only when a mismatched mini-player
 * source cover must bridge into this full-player target before the one-shot high-resolution fade.
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
    transitionCoverPath: String? = coverPath,
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
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerSourceBookId = LocalMini2PlayerSourceBookId.current
    val mini2PlayerTargetScope = LocalMini2PlayerTargetScope.current
    val animatedVisibilityScope = sharedElementVisibilityScope
        ?: mini2PlayerTargetScope

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

    // Transition Key Consistency Validation (Safeguard cover transitions against mismatched keys)
    // Compares the dynamic sharedElementKey against the current bookId. If the bookId is blank or
    // the provided transition key does not contain the target bookId, it bypasses the shared element modifier
    // to cleanly fall back to a normal animated transition.
    val isKeyConsistent = remember(sharedElementKey, bookId) {
        if (bookId.isBlank()) {
            false
        } else sharedElementKey?.contains(bookId) ?: true
    }

    /*
     * Mini-Origin Artwork Bridge (Mismatch-only thumbnail handoff)
     *
     * Mini-player expansion normally keeps the full-player artwork on the high-resolution request.
     * The thumbnail bridge is only armed when the captured mini source bookId does not match the
     * target cover bookId, which means the shared element may otherwise carry stale source artwork.
     * Until the target is fully visible, the full player renders the mini-sized thumbnail family.
     * The first composition after the target settles switches to the main artwork, allowing
     * CrossfadingCoverImage to run exactly one bridge crossfade after geometry has settled.
     */
    val isMiniOriginTarget = sharedElementVisibilityScope == null && mini2PlayerTargetScope != null
    val miniBridgeIdentity = remember(isMiniOriginTarget, mini2PlayerSourceBookId, bookId) {
        if (
            isMiniOriginTarget &&
            !mini2PlayerSourceBookId.isNullOrBlank() &&
            bookId.isNotBlank() &&
            mini2PlayerSourceBookId != bookId
        ) {
            MiniPlayerCoverBridgeIdentity(
                sourceBookId = mini2PlayerSourceBookId,
                targetBookId = bookId
            )
        } else {
            null
        }
    }
    val isMiniOriginTargetSettled = mini2PlayerTargetScope?.transition?.let { transition ->
        transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible
    } ?: false
    var consumedMiniBridgeIdentity by remember { mutableStateOf<MiniPlayerCoverBridgeIdentity?>(null) }
    val useTransitionArtwork = miniBridgeIdentity != null &&
            consumedMiniBridgeIdentity != miniBridgeIdentity &&
            !isMiniOriginTargetSettled
    LaunchedEffect(miniBridgeIdentity, isMiniOriginTargetSettled) {
        if (miniBridgeIdentity != null && isMiniOriginTargetSettled) {
            consumedMiniBridgeIdentity = miniBridgeIdentity
        }
    }
    val activeCoverPath = if (useTransitionArtwork) {
        transitionCoverPath ?: coverPath
    } else {
        coverPath
    }
    val activeCoverVariant = if (useTransitionArtwork) {
        CoverImageVariant.ThumbnailSmall
    } else {
        CoverImageVariant.Main1200
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
            /*
             * Main Artwork Crossfade (Animate bitmap identity without changing motion channels)
             *
             * The shared element and breathing-scale modifiers stay on the stable outer cover bounds,
             * while only the inner decoded artwork fades after the next bitmap has loaded.
             */
            CrossfadingCoverImage(
                sourcePath = activeCoverPath,
                lastUpdated = coverLastUpdated,
                variant = activeCoverVariant,
                scene = coverScene,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                allowHardware = true,
                bitmapConfig = null
            ) {
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
            sizeRatio = 0.8f
        )
    }
}
