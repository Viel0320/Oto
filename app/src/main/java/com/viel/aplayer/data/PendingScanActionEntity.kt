package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待处理扫描操作实体，记录重扫后需要用户决策的事项。
 */
@Entity(
    tableName = "pending_scan_actions",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["scanSessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    // actionKey is the stable dedupe key; bookId is nullable for source-level conflicts.
    indices = [Index("scanSessionId"), Index("bookId"), Index(value = ["actionKey"], unique = true)]
)
data class PendingScanActionEntity(
    @PrimaryKey
    val id: String,
    val scanSessionId: String,
    val actionKey: String, // 稳定去重键
    val type: String, // CONFLICT / UPDATE_EXISTING
    val bookId: String? = null,
    val payloadJson: String,
    val message: String,
    val lastSeenScanId: String,
    // Pending actions are removed when resolved/skipped, so the row only needs creation time.
    val createdAt: Long = System.currentTimeMillis()
)
