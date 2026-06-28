package com.viel.oto.ui.player.layouts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.viel.oto.application.library.player.PlayerBookmarkItem
import com.viel.oto.application.library.player.PlayerChapterItem
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.CoverImageSourceSelector
import com.viel.oto.ui.common.PlayerCover
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.player.BookMetadataState
import com.viel.oto.ui.player.PlaybackProgressViewState
import com.viel.oto.ui.player.PlayerActions
import com.viel.oto.ui.player.PlayerScreenMode
import com.viel.oto.ui.player.PlayerUiState
import com.viel.oto.ui.player.components.BottomNavTabs
import com.viel.oto.ui.player.components.PlaybackPositionBookmarkListView
import com.viel.oto.ui.player.components.PlaybackPositionSubtitlesView
import com.viel.oto.ui.player.components.PlayerControlPanelStateful
import com.viel.oto.ui.player.components.PlayerVerticalAppBar
import com.viel.oto.ui.player.components.RelatedBooksView
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Component rendering main player details in vertical orientation.
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
    subtitleSyncOffsetMs: Long,
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
    settings: com.viel.oto.ui.settings.PlayerSettingsState,
    actions: PlayerActions,
    fullUiState: PlayerUiState,
    currentMode: PlayerScreenMode,
    onModeChange: (PlayerScreenMode) -> Unit,
    animatedBgColor: Color,
    glassEffectMode: GlassEffectMode,
    chapterSheetHazeState: HazeState?,
    offsetY: Animatable<Float, *>,
    scope: kotlinx.coroutines.CoroutineScope,
    dismissThreshold: Float,
    focusManager: FocusManager,
    navigationActions: com.viel.oto.ui.navigation.PlayerNavigationActions,
    modifier: Modifier = Modifier,
    safeDrawingPadding: PaddingValues
) {

    var prevMode by remember { mutableStateOf(currentMode) }
    LaunchedEffect(currentMode) {
        prevMode = currentMode
    }
    val windowClass = LocalAppWindowSizeClass.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(safeDrawingPadding)
    ) {
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
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = windowClass.screenHorizontalPadding)
        ) {
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        when (shell) {
                            PlayerContentShell.PlaybackShell -> {
                                var lastPlaybackMode by remember { mutableStateOf(currentMode) }
                                if (currentMode == PlayerScreenMode.PLAYER || currentMode == PlayerScreenMode.SUBTITLES) {
                                    lastPlaybackMode = currentMode
                                }

                                AnimatedContent(
                                    targetState = lastPlaybackMode,
                                    modifier = Modifier.weight(1f),
                                    transitionSpec = {
                                        (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(
                                            animationSpec = tween(300)
                                        ))
                                            .using(SizeTransform(clip = false))
                                    },
                                    label = "player_playback_top_transition"
                                ) { topMode ->
                                    when (topMode) {
                                        PlayerScreenMode.SUBTITLES -> {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                PlaybackPositionSubtitlesView(
                                                    playbackProgressState = playbackProgressState,
                                                    subtitles = metadata.subtitles,
                                                    subtitleSyncOffsetMs = subtitleSyncOffsetMs,
                                                    onAdjustSubtitleSync = actions.playback.onAdjustSubtitleSync,
                                                    onResetSubtitleSync = actions.playback.onResetSubtitleSync,
                                                    onSeek = { actions.playback.onSeek(it, true) },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }

                                        else -> {
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
                                PlayerControlPanelStateful(
                                    playbackProgressState = playbackProgressState,
                                    currentChapter = currentChapter,
                                    isPlaying = isPlaying,
                                    metadata = metadata,
                                    settings = settings,
                                    actions = actions,
                                    buttonColor = animatedBgColor,
                                    hazeState = chapterSheetHazeState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                )
                            }

                            PlayerContentShell.Bookmarks -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    PlaybackPositionBookmarkListView(
                                        playbackProgressState = playbackProgressState,
                                        bookmarks = metadata.bookmarks,
                                        bookmarkToDelete = bookmarkToDelete,
                                        bookmarkToEdit = bookmarkToEdit,
                                        bookmarkEditTitle = bookmarkEditTitle,
                                        onBookmarkClick = { pos ->
                                            actions.playback.onSeek(
                                                pos,
                                                true
                                            )
                                        },
                                        onRequestDelete = onRequestDeleteBookmark,
                                        onRequestEdit = onRequestEditBookmark,
                                        onEditTitleChange = onBookmarkEditTitleChange,
                                        onConfirmDelete = onConfirmDeleteBookmark,
                                        onConfirmUpdate = onConfirmUpdateBookmark,
                                        onDismissDialogs = onDismissBookmarkDialogs,
                                        hazeState = chapterSheetHazeState,
                                        glassEffectMode = glassEffectMode,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            PlayerContentShell.Related -> {
                                Box(modifier = Modifier.weight(1f)) {
                                    RelatedBooksView(
                                        currentBookId = metadata.id,
                                        heuristicBooks = fullUiState.heuristicRecommendedBooks,
                                        authorSections = fullUiState.relatedAuthorSections,
                                        narratorSections = fullUiState.relatedNarratorSections,
                                        recentBooks = fullUiState.recentlyAddedBooks,
                                        onBookClick = actions.content.onOpenRelatedBookDetail,
                                        onPlayClick = actions.content.onLoadRelatedBook
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
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
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
