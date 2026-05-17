package com.viel.aplayer.media

import com.viel.aplayer.data.ChapterEntity

/**
 * 从音频文件中提取出的完整元数据。
 */
data class AudiobookMetadata(
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val year: String,
    val durationMs: Long,
    /** 提取出的章节列表（暂未绑定 bookId） */
    val chapters: List<ChapterEntity> = emptyList()
)
