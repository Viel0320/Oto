package com.viel.oto.abs.playback

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.oto.data.db.AudiobookSchema

@Entity(
    tableName = "abs_playback_session",
    indices = [Index("bookId"), Index("remoteItemId"), Index("state")]
)
data class AbsPlaybackSessionEntity(
    @PrimaryKey
    val bookId: String,
    val remoteItemId: String,
    val sessionId: String,
    val currentTimeSec: Double,
    val timeListenedSec: Double,
    /**
     * Bounds persisted ABS runtime sessions with the online cache policy.
     * The syncer uses this local wall-clock value to decide whether a restored session row can still suppress a fresh server open.
     */
    val openedAt: Long,
    val state: AudiobookSchema.AbsPlaybackSessionState
)
