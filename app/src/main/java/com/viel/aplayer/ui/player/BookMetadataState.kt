package com.viel.aplayer.ui.player

import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.ui.player.components.SubtitleLine

/**
 * Book metadata state (Model caching static metadata values of loaded audiobook)
 * Represents low-frequency view properties such as title, author, and chapters lists.
 */
data class BookMetadataState(
    /** Book unique identifier (To represent book entity primary key) */
    val id: String = "",
    /** Book title text (To display book title) */
    val title: String = "",
    /** Book author name (To display author credits) */
    val author: String = "",
    /** Audiobook narrator name (To display narrator credits) */
    val narrator: String = "",
    /** Original cover path (To store absolute local file coordinates for cover artwork) */
    val coverPath: String? = null,
    /** Thumbnail cover path (To store absolute local file coordinates for thumbnail) */
    val thumbnailPath: String? = null,
    /** Cover modification timestamp (To trigger view recompositions when cover assets are self-healed) */
    val coverLastUpdated: Long = 0L,
    /** Track chapters (To store audiobook chapter boundaries with file availability status) */
    val chapters: List<ChapterWithBookFile> = emptyList(),
    /** External subtitle lines (To store parsed subtitle lists) */
    val subtitles: List<SubtitleLine> = emptyList(),
    /** User saved bookmarks (To store custom bookmark positions) */
    val bookmarks: List<BookmarkEntity> = emptyList(),
    // Deprecated: backgroundColorArgb is removed
) {
    /** Active track validator (To verify if track metadata is loaded and valid) */
    val hasActiveTrack: Boolean
        get() = title.isNotEmpty() && title != "Unknown"

    /**
     * Map chapter markers (To calculate fractional chapter starts between 0.0 and 1.0)
     */
    fun getChapterMarkers(totalDuration: Long): List<Float> {
        return if (totalDuration > 0) {
            chapters.map { it.chapter.startPositionMs.toFloat() / totalDuration.toFloat() }
        } else {
            emptyList()
        }
    }

}