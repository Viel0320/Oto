package com.viel.aplayer.library.orchestrator

import android.content.Context
import androidx.room.withTransaction
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.orchestrator.draftmodels.BookDraft
import com.viel.aplayer.library.orchestrator.draftmodels.ImportCommand
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Import persistence boundary for the new scan model.
class BookImporter(context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()
    // Ownership State Migrator (Pure replacement mapping)
    // Keeps progress/bookmark anchor remapping out of the persistence boundary so this importer only coordinates database work.
    private val ownershipStateMigrator = OwnershipStateMigrator()

    // Prepared scan commands are committed atomically after the run has completed.
    suspend fun applyImportRun(result: ImportRunResult) = withContext(Dispatchers.IO) {
        database.withTransaction {
            result.readyImports.forEach { command ->
                insertReadyDraft(command.draft)
            }
            result.replacementImports.forEach { command ->
                replaceExistingBooks(command)
            }
            result.refreshedBooks.forEach { command ->
                refreshExistingClaim(command, result.scanId)
            }
        }
    }

    // Ownership Replacement Transaction (Conflict chain elimination)
    // Reassigns ownership to a higher-priority draft while preserving user-facing state before obsolete book rows are deleted.
    private suspend fun replaceExistingBooks(command: ImportCommand.ReplaceExistingBooks) {
        val replacedBookIds = command.replacedBookIds.distinct()
        val oldBooks = replacedBookIds.mapNotNull { bookDao.getBookById(it) }
        val oldFilesByBookId = replacedBookIds.associateWith { bookDao.getAllFilesForBookList(it) }
        val oldProgresses = replacedBookIds.mapNotNull { bookDao.getProgressForBookSync(it) }
        val oldBookmarks = replacedBookIds.flatMap { bookmarkDao.getBookmarksForBookSync(it) }
        val migration = ownershipStateMigrator.migrate(
            OwnershipStateMigrationInput(
                draft = command.draft,
                oldBooks = oldBooks,
                oldFiles = oldFilesByBookId.values.flatten(),
                oldProgresses = oldProgresses,
                oldBookmarks = oldBookmarks
            )
        )

        insertReadyDraft(migration.draft)
        migration.progress?.let { bookDao.insertProgress(it) }
        if (migration.bookmarks.isNotEmpty()) {
            bookmarkDao.insertAll(migration.bookmarks)
        }
        oldBooks
            .filterNot { it.id == command.draft.book.id }
            .forEach { bookDao.deleteBook(it) }
    }

    // Repeated Manifest Scan Refresh (Idempotent ownership maintenance)
    // Updates visibility markers without adding duplicate imports or rewriting book-level metadata.
    private suspend fun refreshExistingClaim(command: ImportCommand.RefreshExistingBook, scanId: String) {
        val now = System.currentTimeMillis()
        bookDao.updateBookLastScannedAt(command.bookId, now)
        // Existing rescans are idempotent and must not mutate book.description.
        command.files.forEach { file ->
            bookDao.updateBookFileStatus(file.id, AudiobookSchema.FileStatus.READY, scanId)
        }
    }

    // New imports do not create BookProgress; playback creates progress on first real activity.
    private suspend fun insertReadyDraft(draft: BookDraft) {
        bookDao.insertBook(draft.book)
        bookDao.insertBookFiles(draft.files)
        if (draft.chapters.isNotEmpty()) {
            chapterDao.insertChapters(draft.chapters)
        }
    }

    // Deprecated Direct Ingestion Removed (Scan pipeline enforcement)
    // Legacy direct file insertion entry points have been removed; all imports must run via the VFS scan ImportRunResult.
}
