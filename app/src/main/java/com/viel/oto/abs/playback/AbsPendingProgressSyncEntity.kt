package com.viel.oto.abs.playback

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
