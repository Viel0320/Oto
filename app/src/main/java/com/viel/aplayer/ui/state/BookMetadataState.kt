package com.viel.aplayer.ui.state

import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.util.image.ImageProcessor

/**
 * 当前加载的有声书元数据状态。
 * 仅在切换书籍或元数据解析完成后才会发生变化，属于低频更新数据。
 */
data class BookMetadataState(
    /** 书籍的唯一标识 URI */
    val uri: String = "",
    /** 书名 */
    val title: String = "",
    /** 作者 */
    val author: String = "",
    /** 播讲人 */
    val narrator: String = "",
    /** 封面图的本地路径 */
    val coverPath: String? = null,
    /** 缩略图的本地路径 */
    val thumbnailPath: String? = null,
    /** 章节列表 */
    val chapters: List<ChapterEntity> = emptyList(),
    /** 字幕/歌词行列表 */
    val subtitles: List<SubtitleLine> = emptyList(),
    /** 用户添加的书签列表 */
    val bookmarks: List<BookmarkEntity> = emptyList(),
    /** 封面提取出的主色调（ARGB），用于界面背景适配 */
    val backgroundColorArgb: Int = ImageProcessor.DEFAULT_BACKGROUND_ARGB
) {
    /** 是否存在有效的播放轨道 */
    val hasActiveTrack: Boolean
        get() = title.isNotEmpty() && title != "Unknown Title"

    /**
     * 根据总时长计算章节在进度条上的标记位置（0.0 - 1.0）。
     * @param totalDuration 书籍的总时长（毫秒）
     */
    fun getChapterMarkers(totalDuration: Long): List<Float> {
        return if (totalDuration > 0) {
            chapters.map { it.startPosition.toFloat() / totalDuration.toFloat() }
        } else {
            emptyList()
        }
    }
}
