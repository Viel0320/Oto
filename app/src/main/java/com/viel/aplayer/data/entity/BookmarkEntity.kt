package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bookmark Data Asset (Entity representing user-defined bookmarks for audiobooks)
 * Supports dual-saving mechanisms via global book timelines and stable file anchors.
 */
@Entity(
    tableName = "bookmarks",
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
    // bookFileId is the stable bookmark anchor; null is kept when remapping cannot resolve a file.
    indices = [Index("bookId"), Index("bookFileId")]
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val globalPositionMs: Long, // Global progress offset in milliseconds for UI display
    val bookFileId: String? = null, // Stable anchor: target book file ID
    val fileOffsetMs: Long = 0L, // Stable anchor: relative millisecond offset inside the target file
    val fileFingerprint: String? = null,
    val anchorStatus: String = "OK", // OK / REMAPPED / UNRESOLVED
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)