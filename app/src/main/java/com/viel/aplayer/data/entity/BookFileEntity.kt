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
        ),
        // 为每一次改动添加详尽的中文注释：建立针对 LibraryRootEntity 的级联 CASCADE 外键约束，当删除文件夹时级联清理关联的音频文件表记录 (H-06)
        ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("uri"), Index("documentId"), Index("rootId")]
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
    // sourcePath 是面向 VFS 的通用路径；SAF 第一阶段等同 relativePath，WebDAV 后续存远程规范路径。
    val sourcePath: String = relativePath,
    // sourceIdentity 保存跨协议稳定身份；SAF 第一阶段等同 documentId/uri 组合，远程源后续可使用 path+etag 等规则。
    val sourceIdentity: String = documentId.ifBlank { uri },
    // remotePath 仅为远程协议预留；SAF 保持 null，避免把本地目录语义误写成远程路径。
    val remotePath: String? = null,
    // etag 用于 WebDAV 等远程源的增量检测；SAF 当前没有稳定 etag，保持 null。
    val etag: String? = null,
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
