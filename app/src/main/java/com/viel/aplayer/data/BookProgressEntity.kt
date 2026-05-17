package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放进度实体，独立于书籍信息保存。
 */
@Entity(tableName = "book_progress")
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
