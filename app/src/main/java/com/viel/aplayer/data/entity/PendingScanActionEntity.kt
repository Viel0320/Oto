package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Pending Scan Action Entity (Database model representing deferred decisions requiring user confirmation)
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
    // Stable Deduplication Key (Queue message identifier)
    // Unique action key to deduplicate pending items during multiple scan iterations.
    val actionKey: String,
    val type: String, // CONFLICT / UPDATE_EXISTING
    val bookId: String? = null,
    val payloadJson: String,
    val message: String,
    val lastSeenScanId: String,
    // Pending actions are removed when resolved/skipped, so the row only needs creation time.
    val createdAt: Long = System.currentTimeMillis()
)