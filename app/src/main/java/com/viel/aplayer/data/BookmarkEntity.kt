package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookUri: String,
    val position: Long,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)
