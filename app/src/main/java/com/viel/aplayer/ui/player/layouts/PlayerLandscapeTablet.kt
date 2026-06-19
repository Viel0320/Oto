package com.viel.aplayer.ui.player.layouts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackProgressViewState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreenMode
import com.viel.aplayer.ui.player.PlayerUiState
import com.viel.aplayer.ui.player.components.BottomNavTabs
import com.viel.aplayer.ui.player.components.PlaybackPositionBookmarkListView
import com.viel.aplayer.ui.player.components.PlaybackPositionSubtitlesView
import com.viel.aplayer.ui.player.components.PlayerControlPanelStateful
import com.viel.aplayer.ui.player.components.PlayerLandscapeHeader
import com.viel.aplayer.ui.player.components.RelatedBooksView
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Tablet landscape player layout (Component rendering double-column player panels in wide screens)
 * Implements clean layouts for tablet screens and folding displays.
 * Applies symmetrical padding structures and spacing margins to provide professional visual hierarchies.
 */
@Composable
fun PlayerLandscapeTablet(
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
    modifier: Modifier = Modifier,
    safeDrawingPadding: PaddingValues
) {
    // Resolve window dimensions (To query device width coordinates without reading LocalConfiguration parameters directly)
    val windowClass = LocalAppWindowSizeClass.current

    // Sync Previous Mode: Tracks the previous playback tab mode to allow custom transition logic (e.g. crossfading to PLAYER mode).
    // Updates synchronously inside LaunchedEffect when currentMode changes.
    var prevMode by remember { mutableStateOf(currentMode) }
    LaunchedEffect(currentMode) {
        prevMode = currentMode
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(safeDrawingPadding)
    ) {
        val contentShell = remember(currentMode) {
            when (currentMode) {
                PlayerScreenMode.BOOKMARKS -> PlayerContentShell.Bookmarks
                PlayerScreenMode.RELATED -> PlayerContentShell.Related
                PlayerScreenMode.PLAYER,
                PlayerScreenMode.SUBTITLES -> PlayerContentShell.PlaybackShell
            }
        }

        // ==========================================
        // 1. Left side tab content column (Ratio 1f)
        // ==========================================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = windowClass.screenHorizontalPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = contentShell,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300))
                                togetherWith
                                fadeOut(animationSpec = tween(300)))
                    },
                ) { shell ->
                    when (shell) {
                        PlayerContentShell.PlaybackShell -> {
                            // Sync Playback Mode State: Tracks and locks the active playback sub-view (subtitles or player artwork cover).
                            // This state only updates when currentMode is SUBTITLES or PLAYER, freezing the view during exit transitions to other content shells.
                            var lastPlaybackMode by remember { mutableStateOf(currentMode) }
                            if (currentMode == PlayerScreenMode.PLAYER || currentMode == PlayerScreenMode.SUBTITLES) {
                                lastPlaybackMode = currentMode
                            }
                            
                            AnimatedContent(
                                targetState = lastPlaybackMode,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)))
                                        .using(SizeTransform(clip = false))
                                },
                                label = "player_playback_top_transition"
                            ) { topMode ->
                                when (topMode) {
                                    PlayerScreenMode.SUBTITLES -> {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            // Subtitles view layout (To render parsed lyrics elements using stateless components)
                                            PlaybackPositionSubtitlesView(
                                                playbackProgressState = playbackProgressState,
                                                subtitles = metadata.subtitles,
                                                onSeek = { actions.playback.onSeek(it, true) },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    else -> {
                                        // Tablet main cover (Provide a thumbnail bridge candidate only for mismatched mini-player source artwork)
                                        PlayerCover(
                                            bookId = metadata.id,
                                            isWideScreen = windowClass.isWideScreen,
                                            coverPath = CoverImageSourceSelector.main(
                                                coverPath = metadata.coverPath,
                                                thumbnailPath = metadata.thumbnailPath
                                            ),
                                            transitionCoverPath = CoverImageSourceSelector.small(
                                                thumbnailPath = metadata.thumbnailPath,
                                                coverPath = metadata.coverPath
                                            ),
                                            isPlaying = isPlaying,
                                            coverLastUpdated = metadata.coverLastUpdated,
                                            coverScene = "player-main-cover"
                                        )
                                    }
                                }
                            }
                        }
                        PlayerContentShell.Bookmarks -> {
                            // Bookmark list layout (To delegate custom bookmark lists to stateless component)
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
                            Box(modifier = Modifier.fillMaxSize()) {
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

            // Bottom navigation tabs (To coordinate active tab state in left column)
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

        // ==========================================
        // 2. Right side tablet controls & title header (Ratio 1f)
        // ==========================================
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = Color.Transparent,
            border = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = windowClass.screenHorizontalPadding)
            ) {
                PlayerLandscapeHeader(
                    metadata = metadata,
                    settings = settings,
                    actions = actions,
                    glassEffectMode = glassEffectMode,
                    hazeState = chapterSheetHazeState
                )

                // Push control panel down (To align controls at bottom boundaries)
                Spacer(modifier = Modifier.weight(1f))
                
                // Tablet playback controls (To render timeline markers and speed values)
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
