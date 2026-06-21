package com.viel.aplayer.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceCover
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.Mini2PlayerSourceCover
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
             * Main Artwork Rendering (Mini-origin bridge aware)
             *
             * The shared element, breathing-scale, and corner modifiers stay on the stable outer
             * bounds. The inner artwork normally renders the current original directly, and only when
             * a mismatched mini source must be bridged does it fly the captured thumbnail and fade to
             * the original after the transition settles.
             */
            MiniOriginBridgeArtwork(
                coverPath = coverPath,
                coverLastUpdated = coverLastUpdated,
                coverScene = coverScene,
                bookId = bookId,
                isRouteOwnedTransition = sharedElementVisibilityScope != null,
                miniTargetScope = mini2PlayerTargetScope
            )
        }
    }
}

/*
 * Fade-through handoff tuning (Mini-origin bridge reveal shape)
 *
 * The source thumbnail clears over the first SOURCE_FADE_OUT_FRACTION of the handoff, then the
 * original fades in from TARGET_ENTER_SCALE. Naming these documents the fade-through timing instead
 * of scattering magic numbers across the graphics layers.
 */
private const val SOURCE_FADE_OUT_FRACTION = 0.35f
private const val TARGET_ENTER_SCALE = 0.92f

/**
 * Mini-origin artwork that stays continuous while the shared element flies, then fades to the
 * full-resolution cover only after the transition settles.
 *
 * Flicker-free contract (this is the whole reason the bridge lives here rather than inside a generic
 * crossfading image):
 * - While flying a mismatched book, it renders the *captured mini thumbnail* using the exact same
 *   cache key the mini source used, so the shared element carries one already-decoded bitmap with no
 *   source/target content swap mid-flight.
 * - The target original is decoded underneath an alpha gate whose initial value is 0 (never 1), so a
 *   not-yet-decoded target can never flash over the bridge frame regardless of effect timing.
 * - The fade runs only once geometry has settled and the target bitmap reported ready, so it happens
 *   on a static page with no shared-element overlay compositing on top of it.
 *
 * Matched-book expansions and route-owned transitions (Home/Detail/Search) skip the bridge entirely
 * and render the current original directly, preserving their existing seamless behavior.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniOriginBridgeArtwork(
    coverPath: String?,
    coverLastUpdated: Long,
    coverScene: String,
    bookId: String,
    isRouteOwnedTransition: Boolean,
    miniTargetScope: AnimatedVisibilityScope?,
) {
    val source = LocalMini2PlayerSourceCover.current

    /*
     * Entry-Gated Bridge Source (First full-player composition only)
     *
     * The initializer intentionally samples the target transition state once and does not key on
     * later settle-state changes. This keeps the captured source alive long enough for the
     * post-settle handoff, while book changes after the full player is already visible reset the
     * bookId key and fall back to direct artwork rendering.
     */
    val bridgeSource: Mini2PlayerSourceCover? = remember(
        isRouteOwnedTransition,
        miniTargetScope,
        source,
        bookId
    ) {
        resolveInitialMiniOriginBridgeSource(
            source = source,
            targetBookId = bookId,
            isRouteOwnedTransition = isRouteOwnedTransition,
            miniTargetScope = miniTargetScope
        )
    }

    if (bridgeSource == null) {
        CoverArtworkLayer(
            coverPath = coverPath,
            coverLastUpdated = coverLastUpdated,
            scene = coverScene
        )
        return
    }

    // Enter transition complete: geometry has settled and the shared-element overlay is torn down.
    val settled = miniTargetScope?.transition?.let { transition ->
        transition.currentState == EnterExitState.Visible &&
            transition.targetState == EnterExitState.Visible
    } ?: true

    var targetReady by remember(bridgeSource) { mutableStateOf(false) }
    var handoffDone by remember(bridgeSource) { mutableStateOf(false) }
    // Handoff progress (0..1), advanced linearly so the fade-through segments below own their own
    // shaping. It still starts at 0 and only advances once the gate releases, so the target can
    // never flash in before its bitmap is on-screen.
    val handoff = remember(bridgeSource) { Animatable(0f) }

    LaunchedEffect(bridgeSource, settled, targetReady) {
        if (settled && targetReady && !handoffDone) {
            handoff.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300, easing = LinearEasing)
            )
            handoffDone = true
        }
    }

    if (handoffDone) {
        // Settled and faded in: a single original layer that hits the just-decoded bitmap cache.
        CoverArtworkLayer(
            coverPath = coverPath,
            coverLastUpdated = coverLastUpdated,
            scene = coverScene
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Fade-through outgoing: the source thumbnail clears over the first third, briefly revealing
        // the cover container surface instead of cross-dissolving two unrelated artworks together.
        val sourceAlpha = (1f - handoff.value / SOURCE_FADE_OUT_FRACTION).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = sourceAlpha }
        ) {
            CoverArtworkLayer(
                coverPath = bridgeSource.coverPath,
                coverLastUpdated = bridgeSource.coverLastUpdated,
                scene = "$coverScene-bridge",
                variant = CoverImageVariant.ThumbnailSmall
            )
        }
        // Fade-through incoming: the original fades in and settles from a slight scale-up once the
        // source has mostly left. Its alpha is still gated at 0 until then, preserving the no-flash
        // contract while replacing the flat cross-dissolve with a directional reveal.
        val incoming = ((handoff.value - SOURCE_FADE_OUT_FRACTION) / (1f - SOURCE_FADE_OUT_FRACTION))
            .coerceIn(0f, 1f)
        val easedIncoming = FastOutSlowInEasing.transform(incoming)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = easedIncoming
                    val scale = TARGET_ENTER_SCALE + (1f - TARGET_ENTER_SCALE) * easedIncoming
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            CoverArtworkLayer(
                coverPath = coverPath,
                coverLastUpdated = coverLastUpdated,
                scene = coverScene,
                onReady = { targetReady = true }
            )
        }
    }
}

/**
 * Resolves the mini-origin bridge source only for the initial mini-player to full-player entry.
 *
 * The bridge fixes a single transition handoff, not general cover replacement inside Player. Sampling
 * the target scope here prevents later metadata changes, queue jumps, or direct loads inside an
 * already-visible full player from replaying the mini-origin source thumbnail.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private fun resolveInitialMiniOriginBridgeSource(
    source: Mini2PlayerSourceCover?,
    targetBookId: String,
    isRouteOwnedTransition: Boolean,
    miniTargetScope: AnimatedVisibilityScope?,
): Mini2PlayerSourceCover? {
    val isEnteringFullPlayer = miniTargetScope?.transition?.let { transition ->
        transition.targetState == EnterExitState.Visible &&
            transition.currentState != EnterExitState.Visible
    } ?: false

    return if (
        isEnteringFullPlayer &&
        !isRouteOwnedTransition &&
        source != null &&
        source.bookId.isNotBlank() &&
        targetBookId.isNotBlank() &&
        source.bookId != targetBookId
    ) {
        source
    } else {
        null
    }
}

/**
 * One cover artwork layer with the shared full-player placeholder.
 *
 * @param onReady Released on Coil success or error so a gated fade can wait for the bitmap to be
 * on-screen (success) or give up gracefully (error) instead of stalling on a missing cover.
 */
@Composable
private fun CoverArtworkLayer(
    coverPath: String?,
    coverLastUpdated: Long,
    scene: String,
    variant: CoverImageVariant = CoverImageVariant.Original,
    onReady: (() -> Unit)? = null,
) {
    CoverImage(
        sourcePath = coverPath,
        lastUpdated = coverLastUpdated,
        variant = variant,
        scene = scene,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        allowHardware = true,
        bitmapConfig = null,
        onSuccess = onReady,
        onError = onReady
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
