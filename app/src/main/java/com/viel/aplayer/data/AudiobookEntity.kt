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
    val lastPosition: Long = 0L, // Current playback position in ms
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)
