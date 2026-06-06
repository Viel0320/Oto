package com.viel.aplayer.ui.player

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.ui.player.components.relatedsection.RelatedSection
import com.viel.aplayer.ui.settings.PlayerSettingsState

/**
 * Root UI state model (Aggregator for view layer configurations)
 * Bundles metadata, playback status, and settings parameters as a single MVI state container.
 */
data class PlayerUiState(
    /** Metadata substate (To store book details) */
    val metadata: BookMetadataState = BookMetadataState(),
    /** Playback substate (To store player states) */
    val playback: PlaybackState = PlaybackState(),
    /** Settings substate (To store visual parameters) */
    val settings: PlayerSettingsState = PlayerSettingsState(),
    /** Author sections (To display list of related author collections) */
    val relatedAuthorSections: List<RelatedSection> = emptyList(),
    /** Narrator sections (To display list of related narrator collections) */
    val relatedNarratorSections: List<RelatedSection> = emptyList(),
    /** Recently added books (To store list of imported audiobooks) */
    val recentlyAddedBooks: List<BookWithProgress> = emptyList(),
    // Recommended books (To store list of smart scored recommendation items)
    val heuristicRecommendedBooks: List<BookWithProgress> = emptyList()
) {
    // --- Accessor delegates (To preserve backward compatibility for existing UI code) ---
    
    /** Playback state getters (To expose player states) */
    val isPlaying get() = playback.isPlaying
    val playWhenReady get() = playback.playWhenReady
    
    /** Book metadata getters (To expose details properties) */
    val currentId get() = metadata.id
    val currentTitle get() = metadata.title
    val currentAuthor get() = metadata.author
    val currentNarrator get() = metadata.narrator
    val currentCoverPath get() = metadata.coverPath
    val currentThumbnailPath get() = metadata.thumbnailPath
    // Deprecated: backgroundColorArgb get delegate is removed
    
    /** Position timing getters (To expose playback position coordinates) */
    val currentPosition get() = playback.currentPosition
    val duration get() = playback.duration
    val progress get() = playback.progress
    val playbackSpeed get() = playback.playbackSpeed
    val isSpeedManualMode get() = playback.isSpeedManualMode
    
    /** Spaced settings getters (To expose settings properties) */
    val selectedSleepTimer get() = settings.selectedSleepTimer
    val showUndoSeek get() = settings.showUndoSeek
    val isChapterListVisible get() = settings.isChapterListVisible
    val isBookmarkDialogVisible get() = settings.isBookmarkDialogVisible
    val bookmarkTitle get() = settings.bookmarkTitle
    val selectedContentTab get() = settings.selectedContentTab
    val isMiniPlayerHidden get() = settings.isMiniPlayerHidden
    val isChapterProgressMode get() = settings.isChapterProgressMode
    val isFullPlayerVisible get() = settings.isFullPlayerVisible
    
    /** Derived state getters (To expose calculated track states) */
    val hasActiveTrack get() = metadata.hasActiveTrack
    val currentChapters get() = metadata.chapters
    val currentSubtitles get() = metadata.subtitles
    val currentBookmarks get() = metadata.bookmarks
}