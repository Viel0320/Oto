package com.viel.oto.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
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
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.animateColorScheme
import com.viel.oto.media.parser.ImageProcessor
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.CoverImageSourceSelector
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.LocalAmoled
import com.viel.oto.ui.common.theme.LocalDarkTheme
import com.viel.oto.ui.common.uiPerformanceTrace
import com.viel.oto.ui.motion.LocalAnimatedVisibilityScope
import com.viel.oto.ui.motion.LocalMini2PlayerSourceCover
import com.viel.oto.ui.motion.LocalMini2PlayerSourceScope
import com.viel.oto.ui.motion.LocalMini2PlayerTargetScope
import com.viel.oto.ui.motion.Mini2PlayerSourceCover
import com.viel.oto.ui.navigation.PlayerNavigationActions
import com.viel.oto.ui.player.miniplayer.CompactMediaPlayer
import com.viel.oto.ui.player.miniplayer.PillCompactMediaPlayer
import com.viel.oto.ui.settings.FullPlayerOpenSource
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Coordinates the mini-player and full-screen player overlays from shared player state.
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
    val settings by settingsViewModel.settingsState.collectAsStateWithLifecycle()

    val isFullPlayerVisible = settings.isFullPlayerVisible

    val hasActiveTrack by remember(playbackViewModel) {
        playbackViewModel.metadataState.map { it.hasActiveTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val isMiniPlayerHidden = settings.isMiniPlayerHidden

    val isMiniPlayerMotionSource = !isFullPlayerVisible || settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer

    val metadata by playbackViewModel.metadataState.collectAsStateWithLifecycle()
    val playbackControls by playbackViewModel.playbackControlState.collectAsStateWithLifecycle()

    val isMediaAvailable by remember(playbackViewModel, metadata.id) {
        playbackViewModel.currentBookAvailability(metadata.id)
    }.collectAsStateWithLifecycle(initialValue = true)

    val miniPlayerActions = remember(playbackViewModel, settingsViewModel) {
        MiniPlayerActions(
            onPlayPauseClick = { playbackViewModel.togglePlayPause() },
            onHide = { settingsViewModel.setMiniPlayerHidden(true) },
            onUnavailable = { playbackViewModel.closeCurrentPlayback() }
        )
    }

    val darkTheme = LocalDarkTheme.current
    val currentColorScheme = MaterialTheme.colorScheme
    val coverPath = metadata.coverPath

    var coverColor by remember(coverPath, metadata.coverLastUpdated) {
        mutableStateOf(ImageProcessor.getCachedColor(coverPath, metadata.coverLastUpdated)?.let { Color(it) })
    }

    val amoled = LocalAmoled.current
    val coverColorScheme = remember(coverColor, darkTheme, amoled) {
        coverColor?.let {
            dynamicColorScheme(seedColor = it, isDark = darkTheme, isAmoled = amoled, style = PaletteStyle.Content)
        }
    }

    val windowClass = LocalAppWindowSizeClass.current
    val usePillPlayer = windowClass.isWideScreen
    val playerAlignment = if (usePillPlayer) Alignment.BottomEnd else Alignment.BottomCenter
    val isPopupNeeded = hasActiveTrack

    val overlayState = when {
        isFullPlayerVisible -> PlayerOverlayState.Full
        isPopupNeeded && !isMiniPlayerHidden -> PlayerOverlayState.Mini
        else -> PlayerOverlayState.Hidden
    }
    var mini2PlayerSourceCover by remember { mutableStateOf<Mini2PlayerSourceCover?>(null) }
    LaunchedEffect(overlayState, metadata.id, metadata.coverLastUpdated) {
        if (overlayState == PlayerOverlayState.Mini && metadata.id.isNotBlank()) {
            mini2PlayerSourceCover = Mini2PlayerSourceCover(
                bookId = metadata.id,
                coverPath = CoverImageSourceSelector.small(
                    thumbnailPath = metadata.thumbnailPath,
                    coverPath = metadata.coverPath
                ),
                coverLastUpdated = metadata.coverLastUpdated
            )
        }
    }

    val playerHazeState = remember { HazeState() }
    val playerOverlayTraceState = "overlay=$overlayState,full=$isFullPlayerVisible," +
        "activeTrack=$hasActiveTrack,miniHidden=$isMiniPlayerHidden,pill=$usePillPlayer"

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
                when (initialState) {
                    PlayerOverlayState.Mini if targetState == PlayerOverlayState.Full -> {
                        if (settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer) {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        } else {
                            (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                        }
                    }
                    PlayerOverlayState.Full if targetState == PlayerOverlayState.Mini -> {
                        if (settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer) {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        } else {
                            (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                        }
                    }
                    PlayerOverlayState.Hidden if targetState == PlayerOverlayState.Mini -> {
                        (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                            .togetherWith(fadeOut(animationSpec = tween(300)))
                    }
                    PlayerOverlayState.Mini if targetState == PlayerOverlayState.Hidden -> {
                        fadeIn(animationSpec = tween(300))
                            .togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                    }
                    PlayerOverlayState.Hidden if targetState == PlayerOverlayState.Full -> {
                        (slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)))
                            .togetherWith(fadeOut(animationSpec = tween(300)))
                    }
                    PlayerOverlayState.Full if targetState == PlayerOverlayState.Hidden -> {
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
                    Spacer(modifier = Modifier.size(0.dp))
                }
                PlayerOverlayState.Mini -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = playerAlignment
                    ) {
                        CompositionLocalProvider(
                            LocalMini2PlayerSourceScope provides if (isMiniPlayerMotionSource) {
                                this@AnimatedContent
                            } else {
                                null
                            },
                            LocalAnimatedVisibilityScope provides this@AnimatedContent
                        ) {
                            MiniPlayerContent(
                                metadata = metadata,
                                isPlaying = playbackControls.isPlaying,
                                miniPlayerProgress = playbackViewModel.miniPlayerProgress,
                                isMediaAvailable = isMediaAvailable,
                                actions = miniPlayerActions,
                                hazeState = appHazeState,
                                glassEffectMode = glassEffectMode,
                                dynamicColorScheme = coverColorScheme,
                                 onExpandClick = { settingsViewModel.openFullPlayerFromMini() }
                            )
                        }
                    }
                }
                PlayerOverlayState.Full -> {
                    CompositionLocalProvider(
                        LocalMini2PlayerTargetScope provides if (
                            settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer
                        ) {
                            this@AnimatedContent
                        } else {
                            null
                        },
                        LocalMini2PlayerSourceCover provides if (
                            settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer
                        ) {
                            mini2PlayerSourceCover
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
                                    hazeState = playerHazeState,
                                    coverColor = coverColor,
                                    onColorExtracted = { coverColor = it },
                                    renderFloatingSurfaces = false,
                                    safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
                                )

                                PlayerFloatingSurfaceHost(
                                    playbackProgressState = playbackViewModel.playbackProgressState,
                                    metadata = metadata,
                                    settings = settings,
                                    actions = playerActions,
                                    hazeState = playerHazeState,
                                    glassEffectMode = glassEffectMode
                                )
                            }
                        }

                        MaterialTheme(
                            colorScheme = animateColorScheme(coverColorScheme ?: currentColorScheme),
                            content = contentBlock
                        )
                    }
                }
            }
        }
    }
}

/**
 * Visual display states owned by the player overlay host.
 */
private enum class PlayerOverlayState {
    Hidden,
    Mini,
    Full
}

/**
 * Renders the active mini-player variant inside the overlay's scoped composition locals.
 */
@Composable
private fun MiniPlayerContent(
    metadata: BookMetadataState,
    isPlaying: Boolean,
    miniPlayerProgress: StateFlow<Float>,
    isMediaAvailable: Boolean,
    actions: MiniPlayerActions,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    dynamicColorScheme: androidx.compose.material3.ColorScheme?,
    onExpandClick: () -> Unit
) {
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
                    isPlaying = isPlaying,
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
                    isPlaying = isPlaying,
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

    MaterialTheme(
        colorScheme = animateColorScheme(dynamicColorScheme ?: MaterialTheme.colorScheme),
        content = contentBlock
    )
}
