package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 核心书籍实体，代表一本逻辑上的有声书。
 * 它可以是单文件音频，也可以是通过 manifest (cue/m3u8) 聚合的多文件书籍。
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val id: String, // 稳定主键，不再使用 URI
    val sourceType: String, // SINGLE_AUDIO / M4B / CUE / M3U8 / GENERATED_M3U8
    val sourceUri: String, // 原始来源 URI (音频文件或 manifest)
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val description: String = "",
    val year: String = "",
    val totalDurationMs: Long = 0L,
    val totalFileSize: Long = 0L,
    val coverPath: String? = null,
    val thumbnailPath: String? = null,
    val backgroundColorArgb: Int? = null,
    val sourceLastModified: Long = 0L,
    val sourceFileSize: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: String = "READY" // READY / PARTIAL / ERROR / CONFLICT / UNAVAILABLE
)
