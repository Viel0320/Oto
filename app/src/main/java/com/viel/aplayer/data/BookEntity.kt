package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// New model: Book is the logical title; file ownership lives in BookFileEntity.
@Entity(tableName = "books", indices = [Index("rootId"), Index("status")])
data class BookEntity(
    @PrimaryKey
    val id: String,
    val rootId: String,
    val sourceType: String,
    // GENERATED_M3U8 has no external manifest file, so its virtual playlist is stored here.
    val generatedManifestJson: String? = null,
    // Tracks the heuristic version used to create generated playlists.
    val heuristicRuleVersion: String? = null,
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val description: String = "",
    val year: String = "",
    val totalDurationMs: Long = 0L,
    val totalFileSize: Long = 0L,
    val coverPath: String? = null,
    val thumbnailPath: String? = null,
    val backgroundColorArgb: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: String = AudiobookSchema.BookStatus.READY
)
