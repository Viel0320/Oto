package com.viel.aplayer.ui.state

import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.ui.utils.DEFAULT_COVER_BACKGROUND_ARGB

data class RelatedSection(
    val name: String,
    val books: List<AudiobookEntity>
)

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val currentTitle: String = "",
    val currentAuthor: String = "",
    val currentNarrator: String = "",
    val currentCoverPath: String? = null,
    val currentThumbnailPath: String? = null,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val selectedSleepTimer: Int = 0,
    val currentChapters: List<ChapterEntity> = emptyList(),
    val currentSubtitles: List<SubtitleLine> = emptyList(),
    val currentBookmarks: List<BookmarkEntity> = emptyList(),
    val relatedAuthorSections: List<RelatedSection> = emptyList(),
    val relatedNarratorSections: List<RelatedSection> = emptyList(),
    val recentlyAddedBooks: List<AudiobookEntity> = emptyList(),
    val showUndoSeek: Boolean = false,
    val isChapterListVisible: Boolean = false,
    val isBookmarkDialogVisible: Boolean = false,
    val bookmarkTitle: String = "",
    val selectedContentTab: Int = -1,
    val isSpeedManualMode: Boolean = false,
    val isMiniPlayerHidden: Boolean = false,
    val backgroundColorArgb: Int = DEFAULT_COVER_BACKGROUND_ARGB,
    val isChapterProgressMode: Boolean = false,
    val isFullPlayerVisible: Boolean = false
) {
    val hasActiveTrack: Boolean
        get() = currentTitle.isNotEmpty() && currentTitle != "Unknown Title"

    val currentChapter: ChapterEntity?
        get() = currentChapters.findLast { currentPosition >= it.startPosition }
            ?: currentChapters.firstOrNull()

    val chapterMarkers: List<Float>
        get() = if (duration > 0) {
            currentChapters.map { it.startPosition.toFloat() / duration.toFloat() }
        } else {
            emptyList()
        }

    val progress: Float
        get() = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
}