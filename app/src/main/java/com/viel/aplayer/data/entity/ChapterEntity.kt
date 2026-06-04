package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audiobook Chapter Schema (Entity representing logical chapter indices parsed for books)
 * Supports mapping boundaries to specific physical audio files.
 */
@Entity(
    tableName = "chapters",
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
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Chapters are anchored to an AUDIO BookFile for source updates and position mapping.
    indices = [Index("bookId"), Index("bookFileId")]
)
data class ChapterEntity(
    @PrimaryKey
    val id: String, // Stable chapter identifier
    val bookId: String,
    val bookFileId: String, // Stable anchor: associated physical track asset ID
    val index: Int,
    val title: String,
    val startPositionMs: Long, // Global starting position offset in milliseconds from the book start
    val durationMs: Long,
    val fileOffsetMs: Long, // Local starting offset in milliseconds relative to the physical audio file
    val source: String // EMBEDDED / CUE / M3U8 / MANUAL
)