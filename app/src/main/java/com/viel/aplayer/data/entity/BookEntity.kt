package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

// New model: Book is the logical title; file ownership lives in BookFileEntity.
@Entity(
    tableName = "books",
    indices = [Index("rootId"), Index("status")],
    foreignKeys = [
        // 为每一次改动添加详尽的中文注释：建立针对 LibraryRootEntity 的级联外键，保证在 SAF 根目录删除释放后，级联物理清除相关书籍 entity (H-06)
        androidx.room.ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class BookEntity(
    @PrimaryKey
    val id: String,
    val rootId: String,
    val sourceType: String,
    // 为每一次改动添加详尽的中文注释：新增 sourceRoot 字段，排在 sourceType 后面，记录当前有声书所有物理资产所属的物理直接父目录 Uri，默认值设为空字符串以确保最佳的兼容性
    val sourceRoot: String = "",
    // GENERATED_M3U8 has no external manifest file, so its virtual playlist is stored here.
    val generatedManifestJson: String? = null,
    // Tracks the heuristic version used to create generated playlists.
    val heuristicRuleVersion: String? = null,
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val description: String = "",
    val year: String = "",
    val totalDurationMs: Long = 0L,
    val totalFileSize: Long = 0L,
    val coverPath: String? = null,
    val thumbnailPath: String? = null,
    val backgroundColorArgb: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: String = AudiobookSchema.BookStatus.READY,
    // 为每一次改动添加详尽的中文注释：新增 readStatus 字段用于持久化有声书的阅读状态（未开始/进行中/已完成），默认值设为 NOT_STARTED 以保证最佳平滑兼容性
    val readStatus: String = AudiobookSchema.ReadStatus.NOT_STARTED
)