package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Represents logical chapter boundaries parsed for a book.
 *
 * Each chapter keeps the physical audio-file anchor needed to remap positions after source
 * updates without depending only on global book offsets.
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
    indices = [Index("bookId"), Index("bookFileId")]
)
data class ChapterEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val bookFileId: String,
    val index: Int,
    val title: String,
    val startPositionMs: Long,
    val durationMs: Long,
    val fileOffsetMs: Long,
    val source: AudiobookSchema.ChapterSource
)