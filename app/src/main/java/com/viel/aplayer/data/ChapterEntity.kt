package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 章节实体，支持落到具体的物理文件。
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    @PrimaryKey
    val id: String, // 建议使用稳定 ID
    val bookId: String,
    val bookFileId: String, // 章节实际落在哪一个音频文件
    val index: Int,
    val title: String,
    val startPositionMs: Long, // 在整本书中的全局起始位置
    val durationMs: Long,
    val fileOffsetMs: Long, // 在对应音频文件内部的起始位置
    val source: String // EMBEDDED / CUE / M3U8 / MANUAL
)
