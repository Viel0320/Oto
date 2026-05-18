package com.viel.aplayer.playback

import android.net.Uri
import com.viel.aplayer.data.BookFileEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.components.SubtitleLine

/**
 * 书籍播放计划，定义了一本书如何被播放。
 */
data class BookPlaybackPlan(
    val bookId: String,
    val title: String,
    val author: String,
    val artworkUri: Uri? = null,
    val artworkData: ByteArray? = null, // 新增：用于通知栏显示的封面字节数据
    val files: List<BookFileEntity>,
    val chapters: List<ChapterEntity>,
    // 每个 AUDIO 文件预解析出的同目录同名字幕，播放器和字幕页共用这份结果。
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
