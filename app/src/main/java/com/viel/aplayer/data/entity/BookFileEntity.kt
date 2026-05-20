package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

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
    indices = [Index("bookId"), Index("uri"), Index("documentId")]
)
data class BookFileEntity(
    @PrimaryKey
    val id: String, // 稳定主键
    val bookId: String,
    // New model role: SOURCE_MANIFEST and AUDIO share this ownership table.
    val fileRole: String = AudiobookSchema.FileRole.AUDIO,
    val rootId: String, // 所属授权目录 ID
    val index: Int, // 在播放队列中的顺序
    val uri: String,
    val documentId: String, // SAF document id
    val relativePath: String, // 相对授权目录的路径
    // Keeps the raw cue/m3u8 entry text for diagnostics only, not for identity.
    val manifestEntryPath: String? = null,
    val displayName: String,
    val durationMs: Long,
    val fileSize: Long,
    val lastModified: Long,
    val fingerprint: String? = null,
    val lastSeenScanId: String? = null,
    val status: String = AudiobookSchema.FileStatus.READY // READY / MISSING
)