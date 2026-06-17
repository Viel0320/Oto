package com.viel.aplayer.ui.player.layouts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BottomNavTabs
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackProgressViewState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreenMode
import com.viel.aplayer.ui.player.PlayerUiState
import com.viel.aplayer.ui.player.components.PlaybackPositionBookmarkListView
import com.viel.aplayer.ui.player.components.PlaybackPositionSubtitlesView
import com.viel.aplayer.ui.player.components.PlayerControlPanelStateful
import com.viel.aplayer.ui.player.components.PlayerVerticalAppBar
import com.viel.aplayer.ui.player.components.RelatedBooksView
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Portrait adaptive player layout (Component rendering main player details in vertical orientation)
 * Layout features single column design routing events through stateless lambdas.
 * Decouples ViewModel bindings and enforces clean stateless UI architecture contracts.
 *
 * @param playbackProgressState High-frequency playback progress stream consumed only by progress-aware children.
 * @param currentChapter The player chapter item currently in the playing state.
 * @param isPlaying Whether the player is currently playing.
 * @param playbackSpeed The current playback speed.
 * @param isSpeedManualMode Whether the playback speed is locked by manual adjustment.
 * @param bookmarkToDelete The bookmark item pending deletion.
 * @param bookmarkToEdit The bookmark item pending editing.
 * @param bookmarkEditTitle The draft title in the input box when editing a bookmark.
 * @param onRequestDeleteBookmark Callback to trigger the delete bookmark confirmation dialog.
 * @param onRequestEditBookmark Callback to trigger the edit bookmark dialog.
 * @param onBookmarkEditTitleChange Callback when the bookmark editing title input changes.
 * @param onConfirmDeleteBookmark Callback to confirm bookmark deletion.
 * @param onConfirmUpdateBookmark Callback to confirm updating the bookmark title.
 * @param onDismissBookmarkDialogs Callback to cancel/close the bookmark dialogs.
 */
@Composable
fun PlayerPortrait(
    playbackProgressState: StateFlow<PlaybackProgressViewState>,
    currentChapter: PlayerChapterItem?,
    isPlaying: Boolean,
    playbackSpeed: Float,
    isSpeedManualMode: Boolean,
    bookmarkToDelete: PlayerBookmarkItem?,
    bookmarkToEdit: PlayerBookmarkItem?,
    bookmarkEditTitle: String,
    onRequestDeleteBookmark: (PlayerBookmarkItem) -> Unit,
    onRequestEditBookmark: (PlayerBookmarkItem) -> Unit,
    onBookmarkEditTitleChange: (String) -> Unit,
    onConfirmDeleteBookmark: () -> Unit,
    onConfirmUpdateBookmark: () -> Unit,
    onDismissBookmarkDialogs: () -> Unit,
    metadata: BookMetadataState,
    settings: com.viel.aplayer.ui.settings.PlayerSettingsState,
    actions: PlayerActions,
    fullUiState: PlayerUiState,
    currentMode: PlayerScreenMode,
    onModeChange: (PlayerScreenMode) -> Unit,
    animatedBgColor: Color,
    glassEffectMode: GlassEffectMode,
    // Player Floating Haze Source (Use the stable app-level sampler for player glass surfaces)
    // PlayerScreen passes the resolved app-level source so chapter sheets, bookmark dialogs, controls, and header menus stay on one HazeState.
    chapterSheetHazeState: HazeState?,
    // Generic layout parameters (To specify Animatable type constraints to avoid runtime matching errors)
    offsetY: Animatable<Float, AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    dismissThreshold: Float,
    // Clean FocusManager import (To bypass platform package compilation symbols drift)
    focusManager: FocusManager,
    navigationActions: com.viel.aplayer.ui.navigation.PlayerNavigationActions,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    // Sync Previous Mode: Tracks the previous playback tab mode to allow custom transition logic (e.g. crossfading to PLAYER mode).
    // Updates synchronously inside LaunchedEffect when currentMode changes.
    var prevMode by remember { mutableStateOf(currentMode) }
    LaunchedEffect(currentMode) {
        prevMode = currentMode
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // AppBar widget delegation (To wrap header information, gestures, and timer triggers)
        PlayerVerticalAppBar(
            metadata = metadata,
            settings = settings,
            actions = actions,
            navigationActions = navigationActions,
            focusManager = focusManager,
            glassEffectMode = glassEffectMode,
            hazeState = chapterSheetHazeState,
            offsetY = offsetY,
            scope = scope,
            dismissThreshold = dismissThreshold
        )

        // Horizontal swipe thresholds (To map horizontal swipe gesture boundaries to 80.dp pixel measurements)
        val swipeThresholdPx = with(density) { 80.dp.toPx() }
        val tabModes = remember {
            listOf(PlayerScreenMode.BOOKMARKS, PlayerScreenMode.SUBTITLES, PlayerScreenMode.RELATED)
        }
        
        // Map tab modes (To match selected screen tab to active animation container)
        val contentShell = remember(currentMode) {
            when (currentMode) {
                PlayerScreenMode.BOOKMARKS -> PlayerContentShell.Bookmarks
                PlayerScreenMode.RELATED -> PlayerContentShell.Related
                PlayerScreenMode.PLAYER,
                PlayerScreenMode.SUBTITLES -> PlayerContentShell.PlaybackShell
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .pointerInput(currentMode) {
                    // Intercept horizontal gestures (To block swipes under core playback modes)
                    if (currentMode == PlayerScreenMode.PLAYER) return@pointerInput
                    var accumulatedX = 0f
                    var hasSwipeTriggered = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            accumulatedX = 0f
                            hasSwipeTriggered = false
                        },
                        onDragEnd = {
                            accumulatedX = 0f
                            hasSwipeTriggered = false
                        },
                        onDragCancel = {
                            accumulatedX = 0f
                            hasSwipeTriggered = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!hasSwipeTriggered) {
                                accumulatedX += dragAmount
                                if (kotlin.math.abs(accumulatedX) > swipeThresholdPx) {
                                    val currentIndex = tabModes.indexOf(currentMode)
                                    val nextMode = if (accumulatedX < 0) {
                                        if (currentIndex < tabModes.lastIndex) tabModes[currentIndex + 1]
                                        else PlayerScreenMode.PLAYER
                                    } else {
                                        if (currentIndex > 0) tabModes[currentIndex - 1]
                                        else PlayerScreenMode.PLAYER
                                    }
                                    onModeChange(nextMode)
                                    hasSwipeTriggered = true
                                }
                            }
                            change.consume()
                        }
                    )
                }
        ) {
            // Horizontal sliding transitions (To animate card displacements smoothly across modes)
            AnimatedContent(
                targetState = contentShell,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    // Decide Transition Spec: Applies fade-in/fade-out for PLAYER (-1) transitions, and horizontal sliding for others.
                    val isPlayerTransition = (currentMode == PlayerScreenMode.PLAYER || prevMode == PlayerScreenMode.PLAYER)
                    if (isPlayerTransition) {
                        (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)))
                            .using(SizeTransform(clip = false))
                    } else {
                        if (targetState.index > initialState.index) {
                            (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(animationSpec = tween(300)))
                        } else {
                            (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(300)))
                        }.using(SizeTransform(clip = false))
                    }
                },
                label = "player_mode_transition"
            ) { shell ->
                Column(modifier = Modifier.fillMaxSize()) {
                    when (shell) {
                        PlayerContentShell.PlaybackShell -> {
                            // Sync Playback Mode State: Tracks and locks the active playback sub-view (subtitles or player artwork cover).
                            // This state only updates when currentMode is SUBTITLES or PLAYER, freezing the view during exit transitions to other content shells.
                            var lastPlaybackMode by remember { mutableStateOf(currentMode) }
                            if (currentMode == PlayerScreenMode.PLAYER || currentMode == PlayerScreenMode.SUBTITLES) {
                                lastPlaybackMode = currentMode
                            }
                            
                            // Crossfade transitions (To animate artwork cover and subtitles card displays)
                            AnimatedContent(
                                targetState = lastPlaybackMode,
                                modifier = Modifier.weight(1f),
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)))
                                        .using(SizeTransform(clip = false))
                                },
                                label = "player_playback_top_transition"
                            ) { topMode ->
                                when (topMode) {
                                    PlayerScreenMode.SUBTITLES -> {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            // Subtitles list wrapper (To render stateless SubtitlesView)
                                            PlaybackPositionSubtitlesView(
                                                playbackProgressState = playbackProgressState,
                                                subtitles = metadata.subtitles,
                                                onSeek = { actions.playback.onSeek(it, true) },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    else -> {
                                        // Main artwork cover (Transport gestures were moved to explicit controls to avoid hidden playback commands on artwork)
                                        // Prefers high-resolution original images over thumbnail drafts.
                                        PlayerCover(
                                            bookId = metadata.id,
                                            isWideScreen = false,
                                            coverPath = CoverImageSourceSelector.main(
                                                coverPath = metadata.coverPath,
                                                thumbnailPath = metadata.thumbnailPath
                                            ),
                                            isPlaying = isPlaying,
                                            coverLastUpdated = metadata.coverLastUpdated,
                                            coverScene = "player-main-cover"
                                        )
                                    }
                                }
                            }
                            // Control panel layout (To render buttons, timelines, and speech multipliers)
                            PlayerControlPanelStateful(
                                playbackProgressState = playbackProgressState,
                                currentChapter = currentChapter,
                                isPlaying = isPlaying,
                                metadata = metadata,
                                settings = settings,
                                actions = actions,
                                buttonColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                hazeState = chapterSheetHazeState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                            )
                        }
                        PlayerContentShell.Bookmarks -> {
                            // Bookmark list container (To display saved bookmark elements)
                            Box(modifier = Modifier.fillMaxSize()) {
                                PlaybackPositionBookmarkListView(
                                    playbackProgressState = playbackProgressState,
                                    bookmarks = metadata.bookmarks,
                                    bookmarkToDelete = bookmarkToDelete,
                                    bookmarkToEdit = bookmarkToEdit,
                                    bookmarkEditTitle = bookmarkEditTitle,
                                    onBookmarkClick = { pos -> actions.playback.onSeek(pos, true) },
                                    onRequestDelete = onRequestDeleteBookmark,
                                    onRequestEdit = onRequestEditBookmark,
                                    onEditTitleChange = onBookmarkEditTitleChange,
                                    onConfirmDelete = onConfirmDeleteBookmark,
                                    onConfirmUpdate = onConfirmUpdateBookmark,
                                    onDismissDialogs = onDismissBookmarkDialogs,
                                    // Bookmark Dialog Haze Routing (Reuse the stable player floating source)
                                    // Edit/delete bookmark dialogs share chapterSheetHazeState with the chapter sheet and player chrome to avoid source rebinding.
                                    hazeState = chapterSheetHazeState,
                                    glassEffectMode = glassEffectMode,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        PlayerContentShell.Related -> {
                            // Related books collection (To query related author and narrator libraries)
                            Box(modifier = Modifier.weight(1f)) {
                                RelatedBooksView(
                                    currentBookId = metadata.id,
                                    heuristicBooks = fullUiState.heuristicRecommendedBooks,
                                    authorSections = fullUiState.relatedAuthorSections,
                                    narratorSections = fullUiState.relatedNarratorSections,
                                    recentBooks = fullUiState.recentlyAddedBooks,
                                    onBookClick = actions.content.onLoadRelatedBook
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom navigation layout (To toggle main tabs inside player screen)
        BottomNavTabs(
            selectedTab = currentMode,
            playbackSpeed = playbackSpeed,
            selectedSleepTimer = settings.selectedSleepTimer,
            isSpeedManualMode = isSpeedManualMode,
            playbackActions = actions.playback,
            onTabSelected = {
                val nextMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                onModeChange(nextMode)
            }
        )
    }
}
