package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 物理音频文件实体。
 * 一本书可以包含一个或多个物理文件。
 */
@Entity(
    tableName = "book_files",
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
data class BookFileEntity(
    @PrimaryKey
    val id: String, // 稳定主键
    val bookId: String,
    val rootId: String, // 所属授权目录 ID
    val index: Int, // 在播放队列中的顺序
    val uri: String,
    val documentId: String, // SAF document id
    val relativePath: String, // 相对授权目录的路径
    val displayName: String,
    val durationMs: Long,
    val fileSize: Long,
    val lastModified: Long,
    val fingerprint: String? = null,
    val lastSeenScanId: String? = null,
    val status: String = "READY" // READY / MISSING / UNSUPPORTED / ERROR
)
