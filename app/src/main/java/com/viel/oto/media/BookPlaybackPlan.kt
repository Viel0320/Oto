package com.viel.oto.media

import android.net.Uri
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.media.subtitle.SubtitleLine

/**
 * Defines structure and properties for playing a specific book.
 *
 * The plan carries playback-buffer intent as a media-layer policy so UI/application read models
 * can decide local-vs-streaming behavior without leaking BookCacheStatus into the media runtime.
 */
data class BookPlaybackPlan(
    val bookId: String,
    val title: String,
    val author: String,
    val artworkUri: Uri? = null,
    val files: List<BookFileEntity>,
    val subtitlesByFileId: Map<String, PlaybackSubtitle> = emptyMap(),
    val startGlobalPositionMs: Long = 0L,
    val bufferPolicy: PlaybackBufferPolicy = PlaybackBufferPolicy.Buffered
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookPlaybackPlan) return false
        return bookId == other.bookId && title == other.title
    }

    override fun hashCode(): Int = bookId.hashCode()
}

/**
 * Describes how aggressively Media3 should pre-load audio bytes for a playback plan.
 *
 * Buffered is used for network-backed playback, while Direct marks sources that the application
 * read model has already classified as local or fully cached.
 */
enum class PlaybackBufferPolicy {
    Buffered,
    Direct
}

data class PlaybackSubtitle(
    val uri: Uri,
    val mimeType: String?,
    val label: String,
    val lines: List<SubtitleLine>
)
