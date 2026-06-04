package com.viel.aplayer.library.availability

import android.content.Context
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Detail-only availability check; rescans do not update old BookFile reachability.
class DetailAvailabilityChecker(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    // Perform Page-Level Availability Verification (Storage Decoupling)
    // Runs the check through a unified helper, maintaining SAF behaviors while leaving a placeholder model for remote sources.
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

        // Batch Availability Check by Directories (Performance Optimization)
        // Group files by parent directories to inspect each directory once, and batch write status updates (READY/MISSING) to database.
        val availabilityByFileId = availabilityChecker.checkBookFiles(files)
        val readyFileIds = mutableListOf<String>()
        val missingFileIds = mutableListOf<String>()
        files.forEach { file ->
            if (availabilityByFileId[file.id]?.isAvailable == true) {
                readyFileIds.add(file.id)
            } else {
                missingFileIds.add(file.id)
            }
        }
        if (readyFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(readyFileIds, AudiobookSchema.FileStatus.READY)
        }
        if (missingFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(missingFileIds, AudiobookSchema.FileStatus.MISSING)
        }

        val readyCount = readyFileIds.size
        val missingCount = missingFileIds.size

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
