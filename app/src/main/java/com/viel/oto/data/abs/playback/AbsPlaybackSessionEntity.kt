package com.viel.oto.data.abs.playback

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.oto.data.db.AudiobookSchema

/**
 * Local cache row for an AudiobookShelf playback session.
 *
 * The row mirrors only the session state needed to resume or close server sessions; raw ABS DTOs
 * remain in the ABS network layer and are not persisted directly.
 */
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
