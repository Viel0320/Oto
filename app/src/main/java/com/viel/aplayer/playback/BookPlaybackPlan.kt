package com.viel.aplayer.playback

import android.net.Uri
import com.viel.aplayer.data.BookFileEntity
import com.viel.aplayer.data.ChapterEntity

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
    val startGlobalPositionMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookPlaybackPlan) return false
        return bookId == other.bookId && title == other.title
    }

    override fun hashCode(): Int = bookId.hashCode()
}
