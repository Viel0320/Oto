package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Playback Progress State (Entity representing the current listening state of an audiobook)
 * Persisted independently of book metadata to decouple search indexes from progress updates.
 */
@Entity(
    tableName = "book_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookFileId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    // Progress Search Indexes: Establish index structures for progress tracking anchors (bookFileId) and last played timestamps (lastPlayedAt).
    indices = [
        Index("bookFileId"),
        Index("lastPlayedAt")
    ]
)
data class BookProgressEntity(
    @PrimaryKey
    val bookId: String,
    val globalPositionMs: Long = 0L, // Global position offset in milliseconds relative to the entire book duration
    val bookFileId: String? = null, // Progress stable anchor: target file ID
    val currentFileIndex: Int = 0,
    val positionInFileMs: Long = 0L, // Progress stable anchor: relative offset in milliseconds inside the target file
    val fileFingerprint: String? = null, // Auxiliary matching anchor: file fingerprint checksum
    val anchorStatus: String = "OK", // OK / REMAPPED / UNRESOLVED
    val playbackSpeed: Float = 1.0f,
    val lastPlayedAt: Long = System.currentTimeMillis()
)