package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Scan Session Entity (Database model tracking global library rescan batches)
 */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey
    val id: String,
    // Scan Trigger Type Safe: Change trigger type to ScanTrigger enum for type safety.
    val trigger: AudiobookSchema.ScanTrigger, // COLD_START / USER
    // Scan Status Type Safe: Change status type to ScanStatus enum for type safety.
    val status: AudiobookSchema.ScanStatus, // RUNNING / COMPLETED
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val abandonedAt: Long? = null,
    val discoveredBookCount: Int = 0,
    val unavailableBookCount: Int = 0,
    val partialBookCount: Int = 0,
    val updatedBookCount: Int = 0,
    // Scan Summary Payload (Diagnostics retention)
    // Persists concrete changed item names for logs and future lightweight scan history surfaces.
    val summaryJson: String = ""
)
