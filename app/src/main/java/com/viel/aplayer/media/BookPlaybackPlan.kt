package com.viel.aplayer.media

import android.net.Uri
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.ui.player.components.SubtitleLine

/**
 * 书籍播放计划，定义了一本书如何被播放。
 */
data class BookPlaybackPlan(
    val bookId: String,
    val title: String,
    val author: String,
    // 播放计划现在只保留轻量的封面文件 URI，
    // 不再把封面原图字节直接塞进计划对象；
    // 这样可以避免启动播放时同步读取整张封面，
    // 也能避免多分轨队列把同一份封面字节重复复制到每个 MediaItem 中。
    val artworkUri: Uri? = null,
    val files: List<BookFileEntity>,
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
