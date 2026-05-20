package com.viel.aplayer.data.entity

import androidx.room.Embedded
import androidx.room.Relation

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

    val isFinished: Boolean
        get() = progress != null && book.totalDurationMs > 0 && 
                progress.globalPositionMs >= (book.totalDurationMs * 0.99).toLong()

    val isInProgress: Boolean
        get() = progress != null && progress.globalPositionMs > 0 && !isFinished

    val isNotStarted: Boolean
        get() = progress == null || progress.globalPositionMs <= 0L
}