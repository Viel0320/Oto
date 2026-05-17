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
        )
    ],
    indices = [Index("scanSessionId")]
)
data class PendingScanActionEntity(
    @PrimaryKey
    val id: String,
    val scanSessionId: String,
    val actionKey: String, // 稳定去重键
    val type: String, // CONFLICT / UPDATE_EXISTING
    val status: String, // PENDING / RESOLVED / SKIPPED
    val bookId: String? = null,
    val payloadJson: String,
    val message: String,
    val lastSeenScanId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
)
