package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书签实体，支持全球位置和稳定锚点双重保存。
 */
@Entity(
    tableName = "bookmarks",
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
    // bookFileId is the stable bookmark anchor; null is kept when remapping cannot resolve a file.
    indices = [Index("bookId"), Index("bookFileId")]
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val globalPositionMs: Long, // 当前结构下的全局显示位置
    val bookFileId: String? = null, // 稳定锚点：文件 ID
    val fileOffsetMs: Long = 0L, // 稳定锚点：文件内偏移
    val fileFingerprint: String? = null,
    val anchorStatus: String = "OK", // OK / REMAPPED / UNRESOLVED
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)