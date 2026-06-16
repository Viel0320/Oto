package com.viel.aplayer.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.uiPerformanceTrace
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.DynamicColorSchemeHelper
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import com.viel.aplayer.ui.motion.LocalAnimatedVisibilityScope
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceScope
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.miniplayer.CompactMediaPlayer
import com.viel.aplayer.ui.player.miniplayer.PillCompactMediaPlayer
import com.viel.aplayer.ui.settings.FullPlayerOpenSource
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Player overlay component (PlayerOverlay).
 *
 * This component is now unified to manage both the mini-player and full-screen player overlays.
 */
@Composable
fun PlayerOverlay(
    modifier: Modifier = Modifier,
    playbackViewModel: PlaybackViewModel,
    bookmarkViewModel: BookmarkViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    playerActions: PlayerActions,
    playerNavigationActions: PlayerNavigationActions,
    glassEffectMode: GlassEffectMode,
    appHazeState: HazeState? = null
) {
    // Sync Visibility States (Directly derive overlay visibility flags from the collected settings state flow to eliminate rendering race conditions during cold starts)
    // Under cold start scenarios, mapping setting states through independent flows causes a race condition between visibility transitions and motion sources. Unifying them into a single state flow solves this.
    val settings by settingsViewModel.settingsState.collectAsStateWithLifecycle()

    val isFullPlayerVisible = settings.isFullPlayerVisible

    val hasActiveTrack by remember(playbackViewModel) {
        playbackViewModel.metadataState.map { it.hasActiveTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val isMiniPlayerHidden = settings.isMiniPlayerHidden

    // Unify Motion Source Flag (Ensure mini player motion source is always true when full player is hidden, so its stable layout bounds remain registered in the SharedTransitionLayout to prevent cold start animation loss)
    // Under cold start scenarios, the default entry source is Direct. If we restrict the motion source scope to only match when fullPlayerOpenSource is MiniPlayer, the mini-player's bounds are not tracked before the first expand command starts. Letting it stay active when full screen is hidden ensures the shared bounds are initialized and ready.
    val isMiniPlayerMotionSource = !isFullPlayerVisible || settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer

    // Gather Playback States (Unify high-frequency and metadata state collection at the overlay root)
    // Facilitates centralized state flows, reducing downstream parameter complexity and making layouts simpler.
    val metadata by playbackViewModel.metadataState.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()

    val isMediaAvailable by remember(playbackViewModel, metadata.id) {
        playbackViewModel.currentBookAvailability(metadata.id)
    }.collectAsStateWithLifecycle(initialValue = true)

    // Internal Action Instantiation
    // Instantiate MiniPlayerActions locally inside the unified overlay to prevent passing redundant variables from APlayerApp shell.
    val miniPlayerActions = remember(playbackViewModel, settingsViewModel) {
        MiniPlayerActions(
            onPlayPauseClick = { playbackViewModel.togglePlayPause() },
            onHide = { settingsViewModel.setMiniPlayerHidden(true) },
            onUnavailable = { playbackViewModel.closeCurrentPlayback() }
        )
    }

    // Unified Cover Color & Scheme Generation (Extract cover color and build dynamic color scheme at the root level)
    // Avoids redundant cover color reads and scheme generation computations for mini and full player components.
    val darkTheme = LocalDarkTheme.current
    val currentColorScheme = MaterialTheme.colorScheme
    val coverPath = metadata.coverPath

    var coverColor by remember(coverPath, metadata.coverLastUpdated) {
        mutableStateOf<Color?>(ImageProcessor.getCachedColor(coverPath, metadata.coverLastUpdated)?.let { Color(it) })
    }

    val dynamicColorScheme = remember(coverColor, darkTheme, currentColorScheme) {
        if (coverColor != null) {
            DynamicColorSchemeHelper.generateColorSchemeFromSeed(coverColor!!, darkTheme, currentColorScheme)
        } else {
            null
        }
    }

    val windowClass = LocalAppWindowSizeClass.current
    val usePillPlayer = windowClass.isWideScreen
    val playerAlignment = if (usePillPlayer) Alignment.BottomEnd else Alignment.BottomCenter
    // Remove Overlay Occlusion Check (Allow player overlays to display independently of search or other active overlay screens)
    // Simplifies player visibility logic so that the mini-player's presence depends solely on having an active track.
    val isPopupNeeded = hasActiveTrack

    // Resolve Player Overlay State (Directly map active visibility configuration to a single state)
    // Avoids separate visibility checks and keeps transition logic predictable.
    val overlayState = when {
        isFullPlayerVisible -> PlayerOverlayState.Full
        isPopupNeeded && !isMiniPlayerHidden -> PlayerOverlayState.Mini
        else -> PlayerOverlayState.Hidden
    }

    val fallbackPlayerHazeState = remember { HazeState() }
    val resolvedPlayerHazeState = appHazeState ?: fallbackPlayerHazeState
    // Player Overlay Trace State (Track the overlay state machine at its unified host)
    // This helps separate mini-player continuous drawing from full-player rendering without logging media metadata.
    val playerOverlayTraceState = "overlay=$overlayState,full=$isFullPlayerVisible," +
        "activeTrack=$hasActiveTrack,miniHidden=$isMiniPlayerHidden,pill=$usePillPlayer"

    // Unified Transition Container (Replaces two independent AnimatedVisibility containers with a single AnimatedContent)
    // This enables a cohesive transition state machine and customizes transition specs to avoid conflicting slide animations during expanding morphs.
    Box(
        modifier = modifier
            .fillMaxSize()
            .uiPerformanceTrace(
                node = "PlayerOverlay",
                route = "Player",
                state = playerOverlayTraceState
            )
    ) {
        AnimatedContent(
            targetState = overlayState,
            transitionSpec = {
                when {
                    initialState == PlayerOverlayState.Mini && targetState == PlayerOverlayState.Full -> {
                        // Title: Conditional Slide Transition for Direct Entry (Ensure direct entry requests slide up in landscape mode)
                        // Description: Checks if the open source is MiniPlayer to apply fade transitions, otherwise falls back to standard vertical slide transition for direct player loads.
                        if (usePillPlayer && settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer) {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        } else {
                            (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                        }
                    }
                    initialState == PlayerOverlayState.Full && targetState == PlayerOverlayState.Mini -> {
                        // Title: Conditional Slide Transition for Direct Dismiss (Ensure player slides down when minimizing if it wasn't opened from mini-player)
                        // Description: Applies fade transitions only if the player was opened from the mini-player, otherwise uses the vertical slide transition to match direct entry behavior.
                        if (usePillPlayer && settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer) {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        } else {
                            (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                        }
                    }
                    initialState == PlayerOverlayState.Hidden && targetState == PlayerOverlayState.Mini -> {
                        (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                            .togetherWith(fadeOut(animationSpec = tween(300)))
                    }
                    initialState == PlayerOverlayState.Mini && targetState == PlayerOverlayState.Hidden -> {
                        fadeIn(animationSpec = tween(300))
                            .togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                    }
                    initialState == PlayerOverlayState.Hidden && targetState == PlayerOverlayState.Full -> {
                        (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                            .togetherWith(fadeOut(animationSpec = tween(300)))
                    }
                    initialState == PlayerOverlayState.Full && targetState == PlayerOverlayState.Hidden -> {
                        fadeIn(animationSpec = tween(300))
                            .togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                    }
                    else -> {
                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                    }
                }
            },
            label = "player_overlay_transition"
        ) { state ->
            when (state) {
                PlayerOverlayState.Hidden -> {
                    // Empty Layout Placeholder (Supply a zero-sized Spacer when no player component is active)
                    // Avoids unnecessary rendering and memory allocation when the overlay is completely hidden.
                    Spacer(modifier = Modifier.size(0.dp))
                }
                PlayerOverlayState.Mini -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = playerAlignment
                    ) {
                        CompositionLocalProvider(
                            // Motion Source Scope: Provide the mini-player motion source whenever it is the active player.
                            // The outer layout's shared bounds are conditionally ignored in CompactPlayer.kt, but this allows cover shared elements to transition.
                            LocalMini2PlayerSourceScope provides if (isMiniPlayerMotionSource) {
                                this@AnimatedContent
                            } else {
                                null
                            },
                            LocalAnimatedVisibilityScope provides this@AnimatedContent
                        ) {
                            MiniPlayerContent(
                                metadata = metadata,
                                playback = playbackState,
                                miniPlayerProgress = playbackViewModel.miniPlayerProgress,
                                isMediaAvailable = isMediaAvailable,
                                actions = miniPlayerActions,
                                hazeState = appHazeState,
                                glassEffectMode = glassEffectMode,
                                dynamicColorScheme = dynamicColorScheme,
                                 onExpandClick = { settingsViewModel.openFullPlayerFromMini() }
                            )
                        }
                    }
                }
                PlayerOverlayState.Full -> {
                    CompositionLocalProvider(
                        // Motion Target Scope: Provide the full-player motion target scope whenever expanding from the mini player.
                        // The outer layout's shared bounds are conditionally ignored in PlayerScreen.kt, but this allows cover shared elements to transition.
                        LocalMini2PlayerTargetScope provides if (
                            settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer
                        ) {
                            this@AnimatedContent
                        } else {
                            null
                        },
                        LocalAnimatedVisibilityScope provides this@AnimatedContent
                    ) {
                        val contentBlock = @Composable {
                            Box(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                PlayerScreen(
                                    playbackViewModel = playbackViewModel,
                                    bookmarkViewModel = bookmarkViewModel,
                                    settingsViewModel = settingsViewModel,
                                    actions = playerActions,
                                    navigationActions = playerNavigationActions,
                                    glassEffectMode = glassEffectMode,
                                    hazeState = resolvedPlayerHazeState,
                                    coverColor = coverColor,
                                    onColorExtracted = { coverColor = it },
                                    renderFloatingSurfaces = false
                                )

                                PlayerFloatingSurfaceHost(
                                    playbackProgressState = playbackViewModel.playbackProgressState,
                                    metadata = metadata,
                                    settings = settings,
                                    actions = playerActions,
                                    hazeState = resolvedPlayerHazeState,
                                    glassEffectMode = glassEffectMode
                                )
                            }
                        }

                        if (dynamicColorScheme != null) {
                            MaterialTheme(colorScheme = dynamicColorScheme, content = contentBlock)
                        } else {
                            contentBlock()
                        }
                    }
                }
            }
        }
    }
}

// Player Overlay State Enum (Define the visual display states of the player overlay)
// Hidden represents inactive playback, Mini matches compact player and Full matches expanded player.
private enum class PlayerOverlayState {
    Hidden,
    Mini,
    Full
}

/**
 * MiniPlayerContent Setup (Scope-limited Playback Content Container)
 *
 * Mini player content container.
 */
@Composable
private fun MiniPlayerContent(
    metadata: BookMetadataState,
    playback: PlaybackState,
    miniPlayerProgress: StateFlow<Float>,
    isMediaAvailable: Boolean,
    actions: MiniPlayerActions,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    dynamicColorScheme: androidx.compose.material3.ColorScheme?,
    onExpandClick: () -> Unit
) {
    // Track Availability Check (Consolidate accessibility and availability listener at the overlay container layer instead of repeating it in sub-components)
    // Consolidating this check at the container level ensures that actions.onUnavailable is executed exactly once and decouples inner player views from tracking business constraints.
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            actions.onUnavailable()
        }
    }

    val windowClass = LocalAppWindowSizeClass.current
    val usePillPlayer = windowClass.isWideScreen
    val miniPlayerCoverPath = CoverImageSourceSelector.small(
        thumbnailPath = metadata.thumbnailPath,
        coverPath = metadata.coverPath
    )

    val contentBlock = @Composable {
        Box {
            if (usePillPlayer) {
                PillCompactMediaPlayer(
                    bookId = metadata.id,
                    isPlaying = playback.isPlaying,
                    coverPath = miniPlayerCoverPath,
                    coverLastUpdated = metadata.coverLastUpdated,
                    actions = actions,
                    hazeState = hazeState,
                    onClick = onExpandClick,
                    glassEffectMode = glassEffectMode
                )
            } else {
                val displayProgress by miniPlayerProgress.collectAsStateWithLifecycle()
                CompactMediaPlayer(
                    bookId = metadata.id,
                    isPlaying = playback.isPlaying,
                    title = metadata.title,
                    author = metadata.author,
                    narrator = metadata.narrator,
                    coverPath = miniPlayerCoverPath,
                    coverLastUpdated = metadata.coverLastUpdated,
                    progress = { displayProgress },
                    actions = actions,
                    hazeState = hazeState,
                    onClick = onExpandClick,
                    glassEffectMode = glassEffectMode
                )
            }
        }
    }

    if (dynamicColorScheme != null) {
        MaterialTheme(colorScheme = dynamicColorScheme, content = contentBlock)
    } else {
        contentBlock()
    }
}
