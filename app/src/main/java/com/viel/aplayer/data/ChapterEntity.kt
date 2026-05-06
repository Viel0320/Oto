package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = AudiobookEntity::class,
            parentColumns = ["uri"],
            childColumns = ["bookUri"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookUri")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookUri: String,
    val title: String,
    val startPosition: Long, // in ms
    val endPosition: Long // in ms
)
