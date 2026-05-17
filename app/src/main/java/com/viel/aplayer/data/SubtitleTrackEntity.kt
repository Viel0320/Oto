package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 字幕轨道实体，只保存来源元信息，不保存字幕行内容。
 */
@Entity(
    tableName = "subtitle_tracks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class SubtitleTrackEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val bookFileId: String? = null, // 若为 FILE scope，则记录对应文件 ID
    val uri: String,
    val format: String, // SRT / ASS / SSA / VTT / LRC / EMBEDDED_LYRICS
    val source: String, // EXTERNAL_TEXT / EMBEDDED_LYRICS
    val scope: String, // BOOK / FILE
    val fileIndex: Int? = null,
    val globalOffsetMs: Long = 0L,
    val isTimed: Boolean = true,
    val isActive: Boolean = false,
    val status: String = "READY" // READY / ERROR / MISSING
)
