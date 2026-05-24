package com.viel.aplayer.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.availability.AvailabilityChecker

// Detail-only availability check; rescans do not update old BookFile reachability.
class DetailAvailabilityChecker(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    // 详情页可用性检查通过统一标准件执行，保持 SAF 行为不变，同时给远程源接入预留状态模型。
    private val availabilityChecker = AvailabilityChecker(context.applicationContext)

    suspend fun check(bookId: String): DetailAvailabilityResult = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            // No AUDIO files means playback cannot be built for this book.
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return@withContext DetailAvailabilityResult(
                isAvailable = false,
                bookStatus = AudiobookSchema.BookStatus.UNAVAILABLE,
                readyAudioCount = 0,
                missingAudioCount = 0
            )
        }

        var readyCount = 0
        var missingCount = 0
        files.forEach { file ->
            val isReady = availabilityChecker.checkBookFile(file).isAvailable
            val status = if (isReady) {
                readyCount += 1
                AudiobookSchema.FileStatus.READY
            } else {
                missingCount += 1
                AudiobookSchema.FileStatus.MISSING
            }
            // Persist the per-file status so UI and playback can see restored/missing files.
            bookDao.updateBookFileStatus(file.id, status)
        }

        val bookStatus = when {
            readyCount == 0 -> AudiobookSchema.BookStatus.UNAVAILABLE
            missingCount > 0 -> AudiobookSchema.BookStatus.PARTIAL
            else -> AudiobookSchema.BookStatus.READY
        }
        // Detail checks only affect this book's availability state.
        bookDao.updateBookStatus(bookId, bookStatus)

        DetailAvailabilityResult(
            isAvailable = readyCount > 0,
            bookStatus = bookStatus,
            readyAudioCount = readyCount,
            missingAudioCount = missingCount
        )
    }

}

data class DetailAvailabilityResult(
    val isAvailable: Boolean,
    val bookStatus: String,
    val readyAudioCount: Int,
    val missingAudioCount: Int
)
