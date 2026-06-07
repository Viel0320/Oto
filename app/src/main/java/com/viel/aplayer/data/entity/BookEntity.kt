package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

// New model: Book is the logical title; file ownership lives in BookFileEntity.
@Entity(
    tableName = "books",
    // Book Search Indexes: Establish index structures for high-frequency filtering fields (readStatus, series, author, narrator) and sorting fields (title, addedAt) to speed up query performance.
    indices = [
        Index("rootId"),
        Index("status"),
        Index("readStatus"),
        Index("series"),
        Index("author"),
        Index("narrator"),
        Index("title"),
        Index("addedAt")
    ],
    foreignKeys = [
        // Library Root Cascade Foreign Key (Establish a cascade delete relationship with LibraryRootEntity)
        // Ensures that when a root directory is removed/revoked, all associated books are wiped from the DB automatically.
        androidx.room.ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class BookEntity(
    @PrimaryKey
    val id: String,
    val rootId: String,
    val sourceType: String,
    // Asset Parent Directory Property (Tracks the direct parent directory URI for all associated assets, defaulting to empty)
    val sourceRoot: String = "",
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
    // Remove backgroundColorArgb (Eradicate database persistence of cover dominant color field) Delete the column to support fully dynamic UI coloring.
    val addedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: String = AudiobookSchema.BookStatus.READY,
    // Playback Progress State Property (Tracks the user read/playback state (NOT_STARTED, IN_PROGRESS, FINISHED), defaulting to NOT_STARTED)
    val readStatus: String = AudiobookSchema.ReadStatus.NOT_STARTED,
    // Series Metadata Property (Tracks the literary collection or series name, defaulting to empty)
    // Represents the series group information associated with the audiobook.
    val series: String = ""
)