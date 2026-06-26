package com.viel.oto.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.oto.application.library.LibraryChapterSource
import com.viel.oto.application.library.player.PlayerChapterItem
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.player.BookMetadataState
import com.viel.oto.ui.player.PlaybackProgressViewState
import com.viel.oto.ui.player.PlayerActions
import com.viel.oto.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Stateful playback control panel bridge that owns high-frequency progress collection.
 *
 * Layout templates call this wrapper instead of reading playback progress themselves, so position
 * ticks recompose the control panel subtree and do not invalidate the full player screen layout.
 */
@Composable
fun PlayerControlPanelStateful(
    modifier: Modifier = Modifier,
    playbackProgressState: StateFlow<PlaybackProgressViewState>,
    currentChapter: PlayerChapterItem?,
    metadata: BookMetadataState,
    isPlaying: Boolean,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    buttonColor: Color,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState? = null
) {
    val progressState by playbackProgressState.collectAsStateWithLifecycle()
    PlayerControlPanel(
        currentPosition = progressState.elapsedMs,
        bufferedPosition = progressState.bufferedMs,
        totalDuration = progressState.durationMs,
        isChapterMode = progressState.isChapterProgressMode,
        currentChapter = currentChapter,
        metadata = metadata,
        isPlaying = isPlaying,
        settings = settings,
        actions = actions,
        buttonColor = buttonColor,
        glassEffectMode = glassEffectMode,
        hazeState = hazeState,
        modifier = modifier
    )
}

/**
 * Renders the lower section of the full-screen player from explicit state and callbacks.
 *
 * The panel remains stateless so high-frequency playback progress can be isolated by
 * PlayerControlPanelStateful without forcing the full player layout to recompose.
 *
 * @param currentPosition The current physical playback progress of the player (in milliseconds).
 * @param bufferedPosition The current physical buffered progress of the player (in milliseconds).
 * @param totalDuration The current physical total duration of the player (in milliseconds).
 * @param isChapterMode Whether the progress bar is currently in chapter progress view mode.
 * @param currentChapter The player-scene chapter item that is currently playing.
 * @param metadata The metadata state of the current book.
 * @param isPlaying Whether the player is currently playing.
 * @param settings The player UI settings state.
 * @param actions Aggregated player actions callback.
 * @param buttonColor The dominant color of the control buttons (the transition-animated dominant color of the book cover).
 * @param glassEffectMode The current glass effect mode (Material/Haze) for the player.
 * @param modifier The layout modifier passed from outside to elastically control width and alignment layout.
 */
@Composable
fun PlayerControlPanel(
    modifier: Modifier = Modifier,
    currentPosition: Long,
    bufferedPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    currentChapter: PlayerChapterItem?,
    metadata: BookMetadataState,
    isPlaying: Boolean,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    buttonColor: Color,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        ChapterDisplay(
            currentChapterTitle = currentChapter?.title ?: metadata.title,
            onChapterClick = actions.content.onShowChapterList,
            onBookmarkClick = actions.bookmarks.onShowDialog,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        PlaybackProgress(
            currentPosition = currentPosition,
            bufferedPosition = bufferedPosition,
            totalDuration = totalDuration,
            isChapterMode = isChapterMode,
            chapters = metadata.chapters,
            markers = metadata.getChapterMarkers(totalDuration),
            onSeek = { pos -> actions.playback.onSeek(pos, true) },
            modifier = Modifier.fillMaxWidth(),
            glassEffectMode = glassEffectMode
        )
        Spacer(Modifier.height(24.dp))

        PlaybackControls(
            isPlaying = isPlaying,
            playbackSeekStepConfig = settings.playbackSeekStepConfig,
            actions = actions.playback,
            buttonColor = buttonColor,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PlayerControlPanelPreview() {
    OtoTheme {
        PlayerControlPanel(
            currentPosition = 120000L,
            bufferedPosition = 220000L,
            totalDuration = 360000L,
            isChapterMode = false,
            currentChapter = PlayerChapterItem(
                id = "chapter_1",
                bookId = "book_1",
                bookFileId = "file_1",
                index = 1,
                title = "Chapter 1: Crisis Era",
                startPositionMs = 0L,
                durationMs = 360000L,
                fileOffsetMs = 0L,
                source = LibraryChapterSource.EMBEDDED
            ),
            metadata = BookMetadataState(
                id = "book_1",
                title = "The Dark Forest",
                author = "Cixin Liu",
                narrator = "Narrator",
                coverPath = null,
                thumbnailPath = null,
                coverLastUpdated = 0L,
                chapters = listOf(
                    PlayerChapterItem(
                        "ch_1",
                        "book_1",
                        "file_1",
                        1,
                        "Prologue",
                        0L,
                        180000L,
                        0L,
                        LibraryChapterSource.EMBEDDED
                    ),
                    PlayerChapterItem(
                        "ch_2",
                        "book_1",
                        "file_1",
                        2,
                        "Chapter 1: Crisis Era",
                        180000L,
                        360000L,
                        180000L,
                        LibraryChapterSource.EMBEDDED
                    )
                )
            ),
            isPlaying = true,
            settings = PlayerSettingsState(),
            actions = PlayerActions(),
            buttonColor = Color(0xFFE91E63),
            glassEffectMode = GlassEffectMode.Material
        )
    }
}
