package com.viel.aplayer.library.availability

import android.content.Context
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Cold-start helper: recover missing BookFile rows without re-importing already claimed files.
class MissingBookFileRecoveryChecker(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    // Reuse Availability Check on Cold-Start (Infrastructure Decoupling)
    // Cold-start recovery leverages the unified AvailabilityChecker instead of embedding protocol-specific logic.
    private val availabilityChecker = AvailabilityChecker(context.applicationContext)

    suspend fun recoverMissingAudioFiles(): MissingBookFileRecoveryResult = withContext(Dispatchers.IO) {
        val missingFiles = bookDao.getMissingAudioBookFilesOnce()
        if (missingFiles.isEmpty()) return@withContext MissingBookFileRecoveryResult()

        val restoredBookIds = linkedSetOf<String>()
        val affectedBookIds = missingFiles.mapTo(linkedSetOf()) { it.bookId }
        val availabilityByFileId = availabilityChecker.checkBookFiles(missingFiles)
        val restoredFileIds = mutableListOf<String>()
        missingFiles.forEach { file ->
            if (availabilityByFileId[file.id]?.isAvailable == true) {
                // Batch Availability Checks in Recovery (Performance Optimization)
                // Minimizes overhead by checking availability for all missing files in the same parent directory at once.
                restoredFileIds.add(file.id)
                restoredBookIds.add(file.bookId)
            }
        }
        if (restoredFileIds.isNotEmpty()) {
            // Restored files become READY immediately, but no import/pending action is created.
            bookDao.updateBookFileStatuses(restoredFileIds, AudiobookSchema.FileStatus.READY)
        }
        val restoredFileCount = restoredFileIds.size

        affectedBookIds.forEach { bookId ->
            // Every book with a missing row is recalculated after the cold-start check, even if no file recovered.
            recalculateBookStatus(bookId)
        }

        val restoredBookTitles = restoredBookIds.mapNotNull { bookId ->
            // Summary names are limited to books that actually restored at least one file.
            bookDao.getBookById(bookId)?.title?.takeIf { it.isNotBlank() }
        }

        MissingBookFileRecoveryResult(
            restoredFileCount = restoredFileCount,
            restoredBookCount = restoredBookIds.size,
            restoredBookTitles = restoredBookTitles
        )
    }

    private suspend fun recalculateBookStatus(bookId: String) {
        val files = bookDao.getFilesForBookList(bookId)
        val bookStatus = statusFromFiles(files)
        // Missing recovery updates only availability status for the affected existing book.
        bookDao.updateBookStatus(bookId, bookStatus)
    }

    private fun statusFromFiles(files: List<BookFileEntity>): String {
        val readyCount = files.count { it.status == AudiobookSchema.FileStatus.READY }
        val missingCount = files.count { it.status == AudiobookSchema.FileStatus.MISSING }
        return when {
            files.isEmpty() || readyCount == 0 -> AudiobookSchema.BookStatus.UNAVAILABLE
            missingCount > 0 -> AudiobookSchema.BookStatus.PARTIAL
            else -> AudiobookSchema.BookStatus.READY
        }
    }

}

data class MissingBookFileRecoveryResult(
    val restoredFileCount: Int = 0,
    val restoredBookCount: Int = 0,
    val restoredBookTitles: List<String> = emptyList()
)
