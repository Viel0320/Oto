package com.viel.aplayer.library.availability

import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MissingBookFileRecoveryChecker(
    database: AppDatabase,
    private val availabilityChecker: AvailabilityChecker
) {
    private val bookDao = database.bookDao()

    suspend fun recoverMissingAudioFiles(): MissingBookFileRecoveryResult = withContext(Dispatchers.IO) {
        recoverFrom(bookDao.getMissingAudioBookFilesOnce())
    }

    /**
     * Root-scoped missing-file recovery for per-root parallel scans.
     * Restores only files owned by the given root so a root scan never touches other roots' rows.
     */
    suspend fun recoverMissingAudioFiles(rootId: String): MissingBookFileRecoveryResult = withContext(Dispatchers.IO) {
        recoverFrom(bookDao.getMissingAudioBookFilesByRootId(rootId))
    }

    private suspend fun recoverFrom(missingFiles: List<BookFileEntity>): MissingBookFileRecoveryResult {
        if (missingFiles.isEmpty()) return MissingBookFileRecoveryResult()

        val restoredBookIds = linkedSetOf<String>()
        val affectedBookIds = missingFiles.mapTo(linkedSetOf()) { it.bookId }
        val availabilityByFileId = availabilityChecker.checkBookFiles(missingFiles)
        val restoredFileIds = mutableListOf<String>()
        missingFiles.forEach { file ->
            if (availabilityByFileId[file.id]?.isAvailable == true) {
                restoredFileIds.add(file.id)
                restoredBookIds.add(file.bookId)
            }
        }
        if (restoredFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(restoredFileIds, AudiobookSchema.FileStatus.READY)
        }
        val restoredFileCount = restoredFileIds.size

        affectedBookIds.forEach { bookId ->
            recalculateBookStatus(bookId)
        }

        val restoredBookTitles = restoredBookIds.mapNotNull { bookId ->
            bookDao.getBookById(bookId)?.title?.takeIf { it.isNotBlank() }
        }

        return MissingBookFileRecoveryResult(
            restoredFileCount = restoredFileCount,
            restoredBookCount = restoredBookIds.size,
            restoredBookTitles = restoredBookTitles
        )
    }

    private suspend fun recalculateBookStatus(bookId: String) {
        val files = bookDao.getFilesForBookList(bookId)
        val bookStatus = statusFromFiles(files)
        bookDao.updateBookStatus(bookId, bookStatus)
    }

    private fun statusFromFiles(files: List<BookFileEntity>): AudiobookSchema.BookStatus {
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
) {
    /**
     * Combines per-root recovery results when a defensive multi-root scan recovers several roots in one session.
     */
    operator fun plus(other: MissingBookFileRecoveryResult): MissingBookFileRecoveryResult =
        MissingBookFileRecoveryResult(
            restoredFileCount = restoredFileCount + other.restoredFileCount,
            restoredBookCount = restoredBookCount + other.restoredBookCount,
            restoredBookTitles = restoredBookTitles + other.restoredBookTitles
        )
}
