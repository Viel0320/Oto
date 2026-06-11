package com.viel.aplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.DynamicColorSchemeHelper
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.motion.LocalAnimatedVisibilityScope
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceScope
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.settings.FullPlayerOpenSource
import dev.chrisbanes.haze.HazeState
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
    playerViewModel: PlayerViewModel,
    playerActions: PlayerActions,
    playerNavigationActions: PlayerNavigationActions,
    isSearchActive: Boolean,
    glassEffectMode: GlassEffectMode,
    appHazeState: HazeState? = null
) {
    // Sync Visibility States (Directly derive overlay visibility flags from the collected settings state flow to eliminate rendering race conditions during cold starts)
    // Under cold start scenarios, mapping setting states through independent flows causes a race condition between visibility transitions and motion sources. Unifying them into a single state flow solves this.
    val settings by playerViewModel.settingsState.collectAsStateWithLifecycle()

    val isFullPlayerVisible = settings.isFullPlayerVisible

    val hasActiveTrack by remember(playerViewModel) {
        playerViewModel.metadataState.map { it.hasActiveTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val isMiniPlayerHidden = settings.isMiniPlayerHidden

    // Unify Motion Source Flag (Ensure mini player motion source is always true when full player is hidden, so its stable layout bounds remain registered in the SharedTransitionLayout to prevent cold start animation loss)
    // Under cold start scenarios, the default entry source is Direct. If we restrict the motion source scope to only match when fullPlayerOpenSource is MiniPlayer, the mini-player's bounds are not tracked before the first expand command starts. Letting it stay active when full screen is hidden ensures the shared bounds are initialized and ready.
    val isMiniPlayerMotionSource = !isFullPlayerVisible || settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer

    // Gather Playback States (Unify high-frequency and metadata state collection at the overlay root)
    // Facilitates centralized state flows, reducing downstream parameter complexity and making layouts simpler.
    val metadata by playerViewModel.metadataState.collectAsStateWithLifecycle()
    val progressState by playerViewModel.playbackProgressState.collectAsStateWithLifecycle()
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    val miniPlayerProgress by playerViewModel.miniPlayerProgress.collectAsStateWithLifecycle()

    val isMediaAvailable by remember(playerViewModel, metadata.id) {
        playerViewModel.currentBookAvailability(metadata.id)
    }.collectAsStateWithLifecycle(initialValue = true)

    // Internal Action Instantiation
    // Instantiate MiniPlayerActions locally inside the unified overlay to prevent passing redundant variables from APlayerApp shell.
    val miniPlayerActions = remember(playerViewModel) {
        MiniPlayerActions(
            onPlayPauseClick = { playerViewModel.togglePlayPause() },
            onHide = { playerViewModel.setMiniPlayerHidden(true) },
            onUnavailable = { playerViewModel.closeCurrentPlayback() }
        )
    }

    // Unified Cover Color & Scheme Generation (Extract cover color and build dynamic color scheme at the root level)
    // Avoids redundant cover color reads and scheme generation computations for mini and full player components.
    val darkTheme = LocalDarkTheme.current
    val currentColorScheme = MaterialTheme.colorScheme
    val coverPath = metadata.coverPath

    var coverColor by remember(coverPath) {
        mutableStateOf<Color?>(ImageProcessor.getCachedColor(coverPath)?.let { Color(it) })
    }

    val dynamicColorScheme = remember(coverColor, darkTheme) {
        if (coverColor != null) {
            DynamicColorSchemeHelper.generateColorSchemeFromSeed(coverColor!!, darkTheme, currentColorScheme)
        } else {
            null
        }
    }

    val windowClass = LocalWindowClass.current
    val usePillPlayer = windowClass.isWideScreen
    val playerAlignment = if (usePillPlayer) Alignment.BottomEnd else Alignment.BottomCenter
    val isPopupNeeded = hasActiveTrack && !isSearchActive

    Box(modifier = modifier.fillMaxSize()) {
        // Mini Player Rendering Branch (Integrate minimized player view in unified page layout)
        // Wraps PillCompactMediaPlayer or CompactMediaPlayer inside the same box tree, controlling layout alignment and visibility.
        if (isPopupNeeded) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = playerAlignment
            ) {
                AnimatedVisibility(
                    visible = !isFullPlayerVisible && !isMiniPlayerHidden,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(300)
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(300)
                    ) + fadeOut(animationSpec = tween(300))
                ) {
                    CompositionLocalProvider(
                        LocalMini2PlayerSourceScope provides if (isMiniPlayerMotionSource) {
                            this@AnimatedVisibility
                        } else {
                            null
                        },
                        LocalAnimatedVisibilityScope provides this@AnimatedVisibility
                    ) {
                        MiniPlayerContent(
                            metadata = metadata,
                            playback = playbackState,
                            displayProgress = miniPlayerProgress,
                            isMediaAvailable = isMediaAvailable,
                            actions = miniPlayerActions,
                            hazeState = appHazeState,
                            glassEffectMode = glassEffectMode,
                            dynamicColorScheme = dynamicColorScheme,
                            onColorExtracted = { coverColor = it },
                            onExpandClick = { playerViewModel.openFullPlayerFromMini() }
                        )
                    }
                }
            }
        }

        // --- Full Player Layer ---
        val fallbackPlayerHazeState = remember { HazeState() }
        val resolvedPlayerHazeState = appHazeState ?: fallbackPlayerHazeState

        AnimatedVisibility(
            visible = isFullPlayerVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
        ) {
            CompositionLocalProvider(
                LocalMini2PlayerTargetScope provides if (
                    settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer
                ) {
                    this@AnimatedVisibility
                } else {
                    null
                },
                LocalAnimatedVisibilityScope provides this@AnimatedVisibility
            ) {
                val contentBlock = @Composable {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        PlayerScreen(
                            viewModel = playerViewModel,
                            actions = playerActions,
                            navigationActions = playerNavigationActions,
                            glassEffectMode = glassEffectMode,
                            hazeState = resolvedPlayerHazeState,
                            coverColor = coverColor,
                            onColorExtracted = { coverColor = it },
                            renderFloatingSurfaces = false
                        )

                        PlayerFloatingSurfaceHost(
                            currentPosition = progressState.elapsedMs,
                            totalDuration = progressState.durationMs,
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

/**
 * MiniPlayerContent Setup (Scope-limited Playback Content Container)
 *
 * Mini player content container.
 */
@Composable
private fun MiniPlayerContent(
    metadata: BookMetadataState,
    playback: PlaybackState,
    displayProgress: Float,
    isMediaAvailable: Boolean,
    actions: MiniPlayerActions,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    dynamicColorScheme: androidx.compose.material3.ColorScheme?,
    onColorExtracted: (Color) -> Unit,
    onExpandClick: () -> Unit
) {
    // Track Availability Check (Consolidate accessibility and availability listener at the overlay container layer instead of repeating it in sub-components)
    // Consolidating this check at the container level ensures that actions.onUnavailable is executed exactly once and decouples inner player views from tracking business constraints.
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            actions.onUnavailable()
        }
    }

    val windowClass = LocalWindowClass.current
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
                    glassEffectMode = glassEffectMode,
                    onColorExtracted = onColorExtracted
                )
            } else {
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
                    glassEffectMode = glassEffectMode,
                    onColorExtracted = onColorExtracted
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
