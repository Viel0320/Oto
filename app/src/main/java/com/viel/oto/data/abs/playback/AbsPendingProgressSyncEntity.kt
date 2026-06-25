package com.viel.oto.data.abs.playback

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable pending progress payload for an ABS book.
 *
 * The entity stores normalized playback progress values, not transport DTOs, so retry workers can
 * survive process death without coupling Room rows to remote response shapes.
 */
@Entity(
    tableName = "abs_pending_progress_sync",
    indices = [Index("bookId"), Index("remoteItemId")]
)
data class AbsPendingProgressSyncEntity(
    @PrimaryKey
    val bookId: String,
    val remoteItemId: String,
    val currentTimeSec: Double,
    val timeListenedSec: Double,
    val durationSec: Double,
    val updatedAt: Long
)
