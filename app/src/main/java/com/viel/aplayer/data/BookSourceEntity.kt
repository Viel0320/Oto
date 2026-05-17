package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍来源实体，记录书籍的导入或章节来源信息。
 */
@Entity(
    tableName = "book_sources",
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
data class BookSourceEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val rootId: String,
    val type: String, // SINGLE_AUDIO / CUE / M3U8 / M4B_EMBEDDED / GENERATED_M3U8
    val sourceUri: String,
    val sourceDocumentId: String,
    val relativePath: String? = null,
    val generatedManifestJson: String? = null, // GENERATED_M3U8 专用的虚拟 manifest 信息
    val heuristicRuleVersion: String? = null,
    val heuristicConfidence: Float? = null,
    val isActive: Boolean = true,
    val status: String = "ACTIVE", // ACTIVE / OPTIONAL / CONFLICT / WARNING
    val message: String? = null,
    val importedAt: Long = System.currentTimeMillis(),
    val lastSeenScanId: String? = null
)
