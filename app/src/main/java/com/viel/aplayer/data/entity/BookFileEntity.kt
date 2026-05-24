package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * 物理音频/清单文件实体。
 * 一本书可以包含一个或多个通过 VFS 定位的物理文件。
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
        // 为每一次改动添加详尽的中文注释：BookFile 通过 rootId 级联到库根，删除库根时同步清理该来源下的文件所有权。
        ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("sourceIdentity"), Index("rootId")]
)
data class BookFileEntity(
    @PrimaryKey
    val id: String, // 稳定主键
    val bookId: String,
    // 为每一次改动添加详尽的中文注释：SOURCE_MANIFEST 和 AUDIO 共用所有权表，清单文件也通过 VFS 定位。
    val fileRole: String = AudiobookSchema.FileRole.AUDIO,
    val rootId: String, // 所属授权目录 ID
    val index: Int, // 在播放队列中的顺序
    // 为每一次改动添加详尽的中文注释：BookFile 不再持久化 provider 原生 URI，所有定位统一使用 rootId + sourcePath 这组 VFS 路径。
    val sourcePath: String,
    // 为每一次改动添加详尽的中文注释：sourceIdentity 保存来源标准件返回的稳定身份，用作辅助 claim，不再保存旧 URI 身份。
    val sourceIdentity: String,
    // 为每一次改动添加详尽的中文注释：etag 用于 WebDAV 等远程源的增量检测；SAF 当前没有稳定 etag，保持 null。
    val etag: String? = null,
    // 为每一次改动添加详尽的中文注释：manifestEntryPath 只保存 CUE/M3U8 原始条目文本用于诊断，不参与文件身份判定。
    val manifestEntryPath: String? = null,
    val displayName: String,
    val durationMs: Long,
    val fileSize: Long,
    val lastModified: Long,
    val fingerprint: String? = null,
    val lastSeenScanId: String? = null,
    val status: String = AudiobookSchema.FileStatus.READY // READY / MISSING
)
