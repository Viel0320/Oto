package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放进度实体，独立于书籍信息保存。
 */
@Entity(
    tableName = "book_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookFileId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    // Progress keeps a nullable file anchor so source updates can remap position safely.
    indices = [Index("bookFileId")]
)
data class BookProgressEntity(
    @PrimaryKey
    val bookId: String,
    val globalPositionMs: Long = 0L, // 整本书中的全局位置
    val bookFileId: String? = null, // 进度稳定锚点：文件 ID
    val currentFileIndex: Int = 0,
    val positionInFileMs: Long = 0L, // 进度稳定锚点：文件内偏移
    val fileFingerprint: String? = null, // 辅助匹配锚点
    val anchorStatus: String = "OK", // OK / REMAPPED / UNRESOLVED
    val playbackSpeed: Float = 1.0f,
    val lastPlayedAt: Long = System.currentTimeMillis()
)