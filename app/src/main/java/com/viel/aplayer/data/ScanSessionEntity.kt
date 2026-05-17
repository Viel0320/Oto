package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 扫描会话实体，记录全库重扫批次。
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
    val pendingActionCount: Int = 0
)
