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

    // Refactor isFinished Flag (Directly bind completion checks to the readStatus field inside BookEntity)
    // This eliminates flag synchronization splits and guarantees state consistency across UI models.
    val isFinished: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.FINISHED

    // Refactor isInProgress Flag (Directly bind reading progress checks to the readStatus field inside BookEntity)
    // This ensures that the active/in-progress status aligns with the persistent database values.
    val isInProgress: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.IN_PROGRESS

    // Refactor isNotStarted Flag (Directly bind unstarted state checks to the readStatus field inside BookEntity)
    // This avoids status checks branching away from database states.
    val isNotStarted: Boolean
        get() = book.readStatus == AudiobookSchema.ReadStatus.NOT_STARTED
}