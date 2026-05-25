package com.viel.aplayer.data.entity

import androidx.room.Embedded
import androidx.room.Relation
import com.viel.aplayer.data.db.AudiobookSchema

data class BookWithProgress(
    @Embedded val book: BookEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId"
    )
    val progress: BookProgressEntity?
) {
    val progressPercent: Int
        get() = if (book.totalDurationMs > 0 && progress != null) {
            kotlin.math.ceil(progress.globalPositionMs.toDouble() / book.totalDurationMs * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

    // 重构 isFinished 判断，直接与 BookEntity 的物理 readStatus 字段绑定，消除状态判定分叉并保持状态一致性
    val isFinished: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.FINISHED

    // 重构 isInProgress 判断，直接与 BookEntity 的物理 readStatus 字段绑定，消除状态判定分叉并保持状态一致性
    val isInProgress: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.IN_PROGRESS

    // 重构 isNotStarted 判断，直接与 BookEntity 的物理 readStatus 字段绑定，消除状态判定分叉并保持状态一致性
    val isNotStarted: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.NOT_STARTED
}