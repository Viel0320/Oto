package com.viel.oto.data.entity

import androidx.room.Embedded
import androidx.room.Relation
import com.viel.oto.data.db.AudiobookSchema

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
        get() = book.readStatus == AudiobookSchema.ReadStatus.FINISHED

    val isInProgress: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.IN_PROGRESS

    val isNotStarted: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.NOT_STARTED
}