package com.viel.oto.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

import com.viel.oto.data.db.AudiobookSchema

/**
 * Stores a user-defined bookmark on a book timeline.
 *
 * Bookmarks keep both the global book offset and the stable file anchor so remapping can recover
 * user data when library roots or physical files change.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId"), Index("bookFileId")]
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val globalPositionMs: Long,
    val bookFileId: String? = null,
    val fileOffsetMs: Long = 0L,
    val fileFingerprint: String? = null,
    val anchorStatus: AudiobookSchema.AnchorStatus = AudiobookSchema.AnchorStatus.OK,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)