package com.viel.oto.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viel.oto.data.db.AudiobookSchema

/**
 * Database model tracking global library rescan batches.
 */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey
    val id: String,
    val trigger: AudiobookSchema.ScanTrigger,
    val status: AudiobookSchema.ScanStatus,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val abandonedAt: Long? = null,
    val discoveredBookCount: Int = 0,
    val unavailableBookCount: Int = 0,
    val partialBookCount: Int = 0,
    val updatedBookCount: Int = 0,
    val summaryJson: String = ""
)
