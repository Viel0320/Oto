package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Scan Session Entity (Database model tracking global library rescan batches)
 */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey
    val id: String,
    val trigger: String, // COLD_START / USER
    val status: String, // RUNNING / COMPLETED
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val abandonedAt: Long? = null,
    val discoveredBookCount: Int = 0,
    val unavailableBookCount: Int = 0,
    val partialBookCount: Int = 0,
    val updatedBookCount: Int = 0,
    val pendingActionCount: Int = 0,
    // Persist display names from this scan so the completion dialog can show concrete items.
    val summaryJson: String = ""
)