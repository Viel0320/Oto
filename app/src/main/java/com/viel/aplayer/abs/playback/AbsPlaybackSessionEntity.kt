package com.viel.aplayer.abs.playback

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val state: String
)
