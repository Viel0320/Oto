package com.viel.aplayer.media

import com.viel.aplayer.data.ChapterEntity

/**
 * 从音频文件中提取出的完整元数据。
 */
data class AudiobookMetadata(
    val title: String,
    val author: String,
    val narrator: String,
    /** 专辑名用于扫描阶段判断多个音频文件是否属于同一本书。 */
    val album: String = "",
    /** 曲目序号用于启发式聚合时按 ID3 顺序生成章节。 */
    val trackIndex: Int? = null,
    val description: String,
    val year: String,
    val durationMs: Long,
    /** 提取出的章节列表（暂未绑定 bookId） */
    val chapters: List<ChapterEntity> = emptyList()
)
