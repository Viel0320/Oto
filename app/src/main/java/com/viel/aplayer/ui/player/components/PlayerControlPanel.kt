package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.settings.PlayerSettingsState
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * Player control panel component (PlayerControlPanel).
 *
 * This panel occupies the lower section of the full screen player, vertically arranging three sub-sections: chapter title, playback progress bar, and playback control buttons.
 * It has been refactored to completely remove any dependencies on PlayerViewModel and its internal UI state classes.
 * All states are explicitly passed down using primitive types and general entity parameters to achieve layer 3 stateless pure-rendering separation and maximum recomposition isolation.
 *
 * @param currentPosition The current physical playback progress of the player (in milliseconds).
 * @param totalDuration The current physical total duration of the player (in milliseconds).
 * @param isChapterMode Whether the progress bar is currently in chapter progress view mode.
 * @param currentChapter The chapter entity that is currently playing.
 * @param metadata The metadata state of the current book.
 * @param isPlaying Whether the player is currently playing.
 * @param playbackSpeed The current playback speed.
 * @param isSpeedManualMode Whether the playback speed is locked by manual adjustment.
 * @param settings The player UI settings state.
 * @param actions Aggregated player actions callback.
 * @param buttonColor The dominant color of the control buttons (the transition-animated dominant color of the book cover).
 * @param glassEffectMode The current glass effect mode (Material/miuix-blur) for the player.
 * @param backdrop The LayerBackdrop sampling source.
 * @param modifier The layout modifier passed from outside to elastically control width and alignment layout.
 */
@Composable
fun PlayerControlPanel(
    currentPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    currentChapter: ChapterEntity?,
    metadata: BookMetadataState,
    isPlaying: Boolean,
    playbackSpeed: Float,
    isSpeedManualMode: Boolean,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    buttonColor: Color,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier
) {
    Column(
        // Hardcoded padding(horizontal = 24.dp) has been stripped, moving control of layout padding to external callers to achieve full edge-to-edge layout or custom indentations.
        // Add 8dp of padding to the entire play control panel container to prevent control panel sub-elements from sticking to the edges, improving visual elegance and touch experience.
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // Chapter title display component, decoupled from ViewModel, directly calling the stateless component by passing the unpacked current chapter title and other parameters.
        ChapterDisplay(
            currentChapterTitle = currentChapter?.title ?: metadata.title,
            onChapterClick = actions.content.onShowChapterList,
            onBookmarkClick = actions.bookmarks.onShowDialog,
            glassEffectMode = glassEffectMode,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        // Progress bar display component, decoupled from ViewModel, directly passing the current playback position, total duration, and corresponding chapter partitions to achieve peak rendering performance.
        PlaybackProgress(
            currentPosition = currentPosition,
            totalDuration = totalDuration,
            isChapterMode = isChapterMode,
            // Map and unpack the independent chapter physical definitions from the metadata list on the fly to match the data type requirements of the stateless component.
            chapters = metadata.chapters.map { it.chapter },
            markers = metadata.getChapterMarkers(totalDuration),
            onSeek = { pos -> actions.playback.onSeek(pos, true) },
            modifier = Modifier.fillMaxWidth(),
            glassEffectMode = glassEffectMode
        )
        Spacer(Modifier.height(24.dp))
        
        // Playback control component, passing fillMaxWidth to allow the five control buttons at the bottom to expand horizontally with equal spacing, adapting to container widths of various sizes.
        PlaybackControls(
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            selectedSleepTimer = settings.selectedSleepTimer,
            isSpeedManualMode = isSpeedManualMode,
            actions = actions.playback,
            buttonColor = buttonColor,
            glassEffectMode = glassEffectMode,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
    }
}
