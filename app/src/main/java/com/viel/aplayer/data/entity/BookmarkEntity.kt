package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Bookmark Data Asset (Entity representing user-defined bookmarks for audiobooks)
 * Supports dual-saving mechanisms via global book timelines and stable file anchors.
 */
@Entity(
    tableName = "bookmarks",
    // Remove Bookmark Foreign Keys (Decouple bookmarks from hard database constraints to prevent deletion of user data during library root revocation or manual file remapping)
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
    // Anchor Status Type Safe: Change the anchorStatus field type to AnchorStatus enum for type safety.
    val anchorStatus: AudiobookSchema.AnchorStatus = AudiobookSchema.AnchorStatus.OK,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)