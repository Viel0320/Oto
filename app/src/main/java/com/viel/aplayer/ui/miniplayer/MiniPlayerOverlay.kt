package com.viel.aplayer.ui.miniplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * MiniPlayerOverlay Setup (Independent Popup Window Overlay)
 *
 * Dedicated independent sub-window floating overlay component (MiniPlayerOverlay) for the mini player.
 * 1. Physical Window Decoupling: Render mini player in a system independent popup window, completely decoupled from the main page rendering tree.
 * 2. 100% Safe Foreground Sampling: Since window is physically decoupled, when frosted glass is enabled, it can safely sample the viewport of appBackdrop/detailBackdrop containing text/cards without Vulkan Feedback Loop crashes.
 * 3. Immersive Edge Extension: Open clippingEnabled = false on Popup properties to stretch frosted glass background under navigation bar for immersive transparency.
 * 4. Ultra-smooth Transition Animation: Nest AnimatedVisibility inside to get smooth slide-in/slide-out entrance/exit animations.
 */
@Composable
fun MiniPlayerOverlay(
    playerViewModel: PlayerViewModel,
    miniPlayerActions: MiniPlayerActions,
    isSearchActive: Boolean,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop? = null
) {
    // Only monitor player visibility (low-frequency signal)
    val isFullPlayerVisible by remember(playerViewModel) {
        playerViewModel.settingsState.map { it.isFullPlayerVisible }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Only monitor if there is an active track (low-frequency signal)
    val hasActiveTrack by remember(playerViewModel) {
        playerViewModel.metadataState.map { it.hasActiveTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    
    // Only monitor manually hidden state of the mini player
    val isMiniPlayerHidden by remember(playerViewModel) {
        playerViewModel.settingsState.map { it.isMiniPlayerHidden }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Use global LocalWindowClass to obtain adaptive configuration details, determining alignment and whether to use a pill player, enhancing responsiveness and cohesion.
    val windowClass = LocalWindowClass.current
    val isLandscape = windowClass.isLandscape
    val isLargeScreen = windowClass.isTablet
    val usePillPlayer = windowClass.isWideScreen

    // Pill player floats in the bottom-right; bar player clings to the bottom-center
    val playerAlignment = if (usePillPlayer) Alignment.BottomEnd else Alignment.BottomCenter

    // MiniPlayer Visibility Guard (Check Active Track and Search overlay)
    // If an active audio track exists and the search overlay is inactive, mount the mini-player layout container;
    // this replaces the NavHost startsWith("search") route guard check.
    val isPopupNeeded = hasActiveTrack && !isSearchActive

    if (isPopupNeeded) {
        // Eliminate Popup Wrapper (Resolve Window-level Blur Barrier)
        // Completely remove independent Popup window packaging, using Box full-screen container alignment inside main Window.
        // This resolves LayerBackdrop sampling isolation issues caused by cross-Android Window barriers, allowing frosted glass to refract underlying pages realistically.
        // Meanwhile, since it is mounted outside appBackdrop/detailBackdrop in APlayerApp root Box as sibling node,
        // it avoids Vulkan Feedback Loop graph rendering deadlock crashes.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = playerAlignment
        ) {
            // Place AnimatedVisibility transition inside main window to precisely capture slide-in/slide-out animations when mini player visibility switches
            AnimatedVisibility(
                visible = !isFullPlayerVisible && !isMiniPlayerHidden,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            ) {
                // Component Isolation (Scope Down Recomposition)
                // Widen progress and playback state updates inside MiniPlayerContent to scope down recomposition.
                // Pass down blur background state and glass mode to isolate refresh source.
                MiniPlayerContent(
                    viewModel = playerViewModel,
                    actions = miniPlayerActions,
                    backdrop = backdrop,
                    glassEffectMode = glassEffectMode
                )
            }
        }
    }
}

/**
 * MiniPlayerContent Setup (Scope-limited Playback Content Container)
 *
 * Mini player content container.
 * Specifically used to collect high-frequency updates (PlaybackState), scoping down recomposition.
 */
@Composable
private fun MiniPlayerContent(
    viewModel: PlayerViewModel,
    actions: MiniPlayerActions,
    backdrop: LayerBackdrop?,
    glassEffectMode: GlassEffectMode
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val metadata by viewModel.metadataState.collectAsStateWithLifecycle()
    val displayProgress by viewModel.miniPlayerProgress.collectAsStateWithLifecycle()
    val isMediaAvailable by remember(viewModel, metadata.id) {
        viewModel.currentBookAvailability(metadata.id)
    }.collectAsStateWithLifecycle(initialValue = true)

    // Use LocalWindowClass in content cards to retrieve screen size to decide rendering compact pill player or wide bottom player.
    val windowClass = LocalWindowClass.current
    val isLandscape = windowClass.isLandscape
    val isLargeScreen = windowClass.isTablet
    val usePillPlayer = windowClass.isWideScreen
    // Thumbnail Small Cover Path (Thumbnail Small Preferred)
    // Small cover path resolved to ThumbnailSmall, letting child components render without knowing "thumbnail preferred, original fallback" business details.
    val miniPlayerCoverPath = CoverImageSourceSelector.small(
        thumbnailPath = metadata.thumbnailPath,
        coverPath = metadata.coverPath
    )

    Box {
        if (usePillPlayer) {
            PillCompactMediaPlayer(
                isPlaying = playback.isPlaying,
                coverPath = miniPlayerCoverPath,
                coverLastUpdated = metadata.coverLastUpdated,
                isMediaAvailable = isMediaAvailable,
                actions = actions,
                backdrop = backdrop,
                onClick = { viewModel.setFullPlayerVisible(true) },
                glassEffectMode = glassEffectMode
            )
        } else {
            CompactMediaPlayer(
                isPlaying = playback.isPlaying,
                title = metadata.title,
                author = metadata.author,
                narrator = metadata.narrator,
                coverPath = miniPlayerCoverPath,
                coverLastUpdated = metadata.coverLastUpdated,
                progress = { displayProgress },
                isMediaAvailable = isMediaAvailable,
                actions = actions,
                backdrop = backdrop,
                onClick = { viewModel.setFullPlayerVisible(true) },
                glassEffectMode = glassEffectMode
            )
        }
    }
}
