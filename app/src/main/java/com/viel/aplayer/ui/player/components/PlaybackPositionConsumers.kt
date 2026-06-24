package com.viel.aplayer.ui.player.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.media.subtitle.SubtitleLine
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackProgressViewState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkListView
import com.viel.aplayer.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Subtitles bridge that limits playback-position collection to the subtitle viewport.
 *
 * The surrounding layout decides whether subtitles are visible, while this component owns the
 * high-frequency position read needed for active-line highlighting and auto-scroll.
 */
@Composable
fun PlaybackPositionSubtitlesView(
    playbackProgressState: StateFlow<PlaybackProgressViewState>,
    subtitles: List<SubtitleLine>,
    subtitleSyncOffsetMs: Long,
    onAdjustSubtitleSync: (Long) -> Unit,
    onResetSubtitleSync: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progressState by playbackProgressState.collectAsStateWithLifecycle()
    SubtitlesView(
        subtitles = subtitles,
        currentPosition = progressState.elapsedMs,
        subtitleSyncOffsetMs = subtitleSyncOffsetMs,
        onAdjustSubtitleSync = onAdjustSubtitleSync,
        onResetSubtitleSync = onResetSubtitleSync,
        onSeek = onSeek,
        modifier = modifier
    )
}

/**
 * Bookmark list bridge that keeps active-bookmark highlighting off the parent layout scope.
 *
 * Bookmark metadata and dialog state remain normal layout inputs; only the volatile playback
 * position is collected here so playback ticks recompose the bookmark list subtree when visible.
 */
@Composable
fun PlaybackPositionBookmarkListView(
    playbackProgressState: StateFlow<PlaybackProgressViewState>,
    bookmarks: List<PlayerBookmarkItem>,
    bookmarkToDelete: PlayerBookmarkItem?,
    bookmarkToEdit: PlayerBookmarkItem?,
    bookmarkEditTitle: String,
    onBookmarkClick: (Long) -> Unit,
    onRequestDelete: (PlayerBookmarkItem) -> Unit,
    onRequestEdit: (PlayerBookmarkItem) -> Unit,
    onEditTitleChange: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onConfirmUpdate: () -> Unit,
    onDismissDialogs: () -> Unit,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier
) {
    val progressState by playbackProgressState.collectAsStateWithLifecycle()
    BookmarkListView(
        bookmarks = bookmarks,
        bookmarkToDelete = bookmarkToDelete,
        bookmarkToEdit = bookmarkToEdit,
        bookmarkEditTitle = bookmarkEditTitle,
        onBookmarkClick = onBookmarkClick,
        onRequestDelete = onRequestDelete,
        onRequestEdit = onRequestEdit,
        onEditTitleChange = onEditTitleChange,
        onConfirmDelete = onConfirmDelete,
        onConfirmUpdate = onConfirmUpdate,
        onDismissDialogs = onDismissDialogs,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        currentPosition = progressState.elapsedMs,
        modifier = modifier
    )
}

/**
 * Chapter sheet bridge that subscribes to playback progress only inside floating player surfaces.
 *
 * PlayerScreen and PlayerOverlay can host the floating surfaces without reading progress at their
 * own level, while the sheet still receives fresh position and duration when it is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackPositionChapterListSheetStateful(
    playbackProgressState: StateFlow<PlaybackProgressViewState>,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    sheetState: SheetState,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode
) {
    val progressState by playbackProgressState.collectAsStateWithLifecycle()
    ChapterListSheetStateful(
        currentPosition = progressState.elapsedMs,
        totalDuration = progressState.durationMs,
        metadata = metadata,
        settings = settings,
        actions = actions,
        sheetState = sheetState,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode
    )
}
