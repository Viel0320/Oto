package com.viel.aplayer.media

import android.net.Uri
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.media.subtitle.SubtitleLine

/**
 * Playback Plan Configuration (Defines structure and properties for playing a specific book)
 */
data class BookPlaybackPlan(
    val bookId: String,
    val title: String,
    val author: String,
    // Artwork URI Referencing (Store lightweight cover image URI to avoid memory inflation)
    // Prevents synchronous reading of raw cover bytes during player startup
    // and eliminates redundant copy actions of the same image across multiple MediaItems.
    val artworkUri: Uri? = null,
    val files: List<BookFileEntity>,
    // Subtitle Registry (Map subtitle paths by their corresponding audio file ID to share with the player UI)
    val subtitlesByFileId: Map<String, PlaybackSubtitle> = emptyMap(),
    val startGlobalPositionMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookPlaybackPlan) return false
        return bookId == other.bookId && title == other.title
    }

    override fun hashCode(): Int = bookId.hashCode()
}

data class PlaybackSubtitle(
    val uri: Uri,
    val mimeType: String?,
    val label: String,
    val lines: List<SubtitleLine>
)
