package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audiobooks")
data class AudiobookEntity(
    @PrimaryKey
    val uri: String,
    val title: String,
    val author: String = "Unknown Author", // Artist
    val narrator: String = "Unknown Narrator", // Composer
    val description: String = "", // Comment/Remarks
    val duration: Long = 0L, // Total duration in ms
    val year: String = "",
    val fileSize: Long = 0L,
    val coverPath: String? = null,
    val thumbnailPath: String? = null,
    val subtitlePath: String? = null,
    val lastPosition: Long = 0L, // Current playback position in ms
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val FINISHED_THRESHOLD = 0.99f
    }

    val isNotStarted: Boolean
        get() = lastPosition <= 0L

    val isFinished: Boolean
        get() = duration > 0L && lastPosition >= (duration * FINISHED_THRESHOLD).toLong()

    val isInProgress: Boolean
        get() = !isNotStarted && !isFinished

    val progressPercent: Int
        get() = if (duration > 0) {
            kotlin.math.ceil(lastPosition.toDouble() / duration.toDouble() * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
}
