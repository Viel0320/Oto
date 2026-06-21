package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Stores the current listening state for one audiobook.
 *
 * Progress is persisted independently from book metadata so frequent playback updates do not
 * rewrite search or catalog records.
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
    indices = [
        Index("bookFileId"),
        Index("lastPlayedAt")
    ]
)
data class BookProgressEntity(
    @PrimaryKey
    val bookId: String,
    val globalPositionMs: Long = 0L,
    val bookFileId: String? = null,
    val currentFileIndex: Int = 0,
    val positionInFileMs: Long = 0L,
    val fileFingerprint: String? = null,
    val anchorStatus: AudiobookSchema.AnchorStatus = AudiobookSchema.AnchorStatus.OK,
    val playbackSpeed: Float = 1.0f,
    val lastPlayedAt: Long = System.currentTimeMillis()
)