package com.viel.oto.ui.player.layouts

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
import com.viel.oto.ui.player.components.PlayerLandscapeHeader
import com.viel.oto.ui.player.components.RelatedBooksView
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Component rendering dual-column layouts for horizontal phone screens.
 * Optimizes layouts for tight vertical spaces and wider horizontal bounds.
 * Splits layout into left tab area and right control area.
 */
@Composable
fun PlayerLandscapePhone(
    modifier: Modifier = Modifier,
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
    safeDrawingPadding: PaddingValues
) {
    val windowClass = LocalAppWindowSizeClass.current
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
                                            isWideScreen = windowClass.isWideScreen,
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
                        }
                        PlayerContentShell.Bookmarks -> {
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
                                    onBookClick = actions.content.onOpenRelatedBookDetail,
                                    onPlayClick = actions.content.onLoadRelatedBook
                                )
                            }
                        }
                    }
                }
            }

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
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = windowClass.screenHorizontalPadding),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = Color.Transparent,
            border = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                PlayerLandscapeHeader(
                    metadata = metadata,
                    settings = settings,
                    actions = actions,
                    glassEffectMode = glassEffectMode,
                    hazeState = chapterSheetHazeState
                )

                Spacer(modifier = Modifier.weight(1f))

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
