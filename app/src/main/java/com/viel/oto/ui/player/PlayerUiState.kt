package com.viel.oto.ui.player

import com.viel.oto.application.library.player.PlayerRelatedBook
import com.viel.oto.application.library.player.PlayerRelatedSection
import com.viel.oto.ui.settings.PlayerSettingsState

/**
 * Aggregator for view layer configurations.
 * Bundles metadata, playback status, and settings parameters as a single MVI state container.
 */
data class PlayerUiState(
    /** To store book details. */
    val metadata: BookMetadataState = BookMetadataState(),
    /** To store player states. */
    val playback: PlaybackState = PlaybackState(),
    /** To store visual parameters. */
    val settings: PlayerSettingsState = PlayerSettingsState(),
    /** To display list of related author collections. */
    val relatedAuthorSections: List<PlayerRelatedSection> = emptyList(),
    /** To display list of related narrator collections. */
    val relatedNarratorSections: List<PlayerRelatedSection> = emptyList(),
    /** To store list of imported audiobooks. */
    val recentlyAddedBooks: List<PlayerRelatedBook> = emptyList(),
    val heuristicRecommendedBooks: List<PlayerRelatedBook> = emptyList()
) {

    /** To expose player states. */
    val isPlaying get() = playback.isPlaying
    val playWhenReady get() = playback.playWhenReady

    /** To expose details properties. */
    val currentId get() = metadata.id
    val currentTitle get() = metadata.title
    val currentAuthor get() = metadata.author
    val currentNarrator get() = metadata.narrator
    val currentCoverPath get() = metadata.coverPath
    val currentThumbnailPath get() = metadata.thumbnailPath

    /** To expose playback position coordinates. */
    val currentPosition get() = playback.currentPosition
    val duration get() = playback.duration
    val progress get() = playback.progress
    val playbackSpeed get() = playback.playbackSpeed
    val isSpeedManualMode get() = playback.isSpeedManualMode

    /** To expose settings properties. */
    val selectedSleepTimer get() = settings.selectedSleepTimer
    val showUndoSeek get() = settings.showUndoSeek
    val isChapterListVisible get() = settings.isChapterListVisible
    val isBookmarkDialogVisible get() = settings.isBookmarkDialogVisible
    val bookmarkTitle get() = settings.bookmarkTitle
    val selectedContentTab get() = settings.selectedContentTab
    val isMiniPlayerHidden get() = settings.isMiniPlayerHidden
    val isChapterProgressMode get() = settings.isChapterProgressMode
    val isFullPlayerVisible get() = settings.isFullPlayerVisible

    /** To expose calculated track states. */
    val hasActiveTrack get() = metadata.hasActiveTrack
    val currentChapters get() = metadata.chapters
    val currentSubtitles get() = metadata.subtitles
    val currentBookmarks get() = metadata.bookmarks
}
