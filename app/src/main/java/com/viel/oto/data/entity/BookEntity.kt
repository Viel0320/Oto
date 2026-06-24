package com.viel.oto.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.oto.data.db.AudiobookSchema

@Entity(
    tableName = "books",
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
    val sourceType: AudiobookSchema.SourceType,
    val sourceRoot: String = "",
    val generatedManifestJson: String? = null,
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
    val addedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: AudiobookSchema.BookStatus = AudiobookSchema.BookStatus.READY,
    /**
     * User-visible reading state derived from playback progress and manual status changes.
     */
    val readStatus: AudiobookSchema.ReadStatus = AudiobookSchema.ReadStatus.NOT_STARTED,
    val series: String = ""
)
