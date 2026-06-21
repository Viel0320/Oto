package com.viel.aplayer.ui.player

import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.media.subtitle.SubtitleLine

/**
 * Model caching static metadata values of loaded audiobook.
 * Represents low-frequency view properties such as title, author, and chapters lists.
 */
data class BookMetadataState(
    /** To represent book entity primary key. */
    val id: String = "",
    /** To display book title. */
    val title: String = "",
    /** To display author credits. */
    val author: String = "",
    /** To display narrator credits. */
    val narrator: String = "",
    /** To store absolute local file coordinates for cover artwork. */
    val coverPath: String? = null,
    /** To store absolute local file coordinates for thumbnail. */
    val thumbnailPath: String? = null,
    /** To trigger view recompositions when cover assets are self-healed. */
    val coverLastUpdated: Long = 0L,
    /** To store audiobook chapter boundaries with file availability status. */
    val chapters: List<PlayerChapterItem> = emptyList(),
    /** To store parsed subtitle lists. */
    val subtitles: List<SubtitleLine> = emptyList(),
    /** To store custom bookmark positions. */
    val bookmarks: List<PlayerBookmarkItem> = emptyList(),
) {
    /** To verify if track metadata is loaded and valid. */
    val hasActiveTrack: Boolean
        get() = title.isNotEmpty() && title != "Unknown"

    /**
     * To calculate fractional chapter starts between 0.0 and 1.0.
     */
    fun getChapterMarkers(totalDuration: Long): List<Float> {
        return if (totalDuration > 0) {
            chapters.map { it.startPositionMs.toFloat() / totalDuration.toFloat() }
        } else {
            emptyList()
        }
    }

}
