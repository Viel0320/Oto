package com.viel.oto.library.orchestrator.draftmodels

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity

/**
 * Pipeline Data Model.
 *
 * Represents an execution instruction emitted by the import pipeline to write to the persistent database.
 */
sealed interface ImportCommand {
    /**
     * Pipeline Action.
     *
     * Constructs a complete, conflict-free, and playable audiobook draft in the database.
     */
    data class CreateReadyBook(val draft: BookDraft) : ImportCommand

    /**
     * Ownership Reassignment.
     *
     * Inserts the incoming higher-priority draft, migrates user state from the replaced books,
     * and removes the obsolete ownership rows in the persistence boundary.
     */
    data class ReplaceExistingBooks(
        val draft: BookDraft,
        val replacedBookIds: List<String>
    ) : ImportCommand

    /**
     * Pipeline Action.
     *
     * Updates visibility and ownership of files associated with a book, bypassing book-level metadata rewrites.
     */
    data class RefreshExistingBook(val bookId: String, val files: List<BookFileEntity>) : ImportCommand

    /**
     * Pipeline Action.
     *
     * Logs errors encountered during scanning and parsing processes.
     */
    data class RecordFailure(val failure: ImportFailure) : ImportCommand
}

/**
 * Domain Aggregation.
 *
 * Combines the BookEntity database row with its respective BookFileEntity tracks and ChapterEntity chapters.
 */
data class BookDraft(
    /**
     * Domain Entity.
     */
    val book: BookEntity,

    /**
     * Domain Entity Collection.
     */
    val files: List<BookFileEntity>,

    /**
     * Domain Entity Collection.
     */
    val chapters: List<ChapterEntity>
)

/**
 * Diagnostic Model.
 */
data class ImportFailure(
    /**
     * Stable Reference.
     */
    val sourceUri: String,

    /**
     * User-Readable Description.
     */
    val message: String,

    /**
     * Technical Diagnostics.
     */
    val throwableMessage: String? = null
)

/**
 * Pipeline Output summary.
 */
data class ImportRunResult(
    /**
     * Stable ID Reference.
     */
    val scanId: String,

    /**
     * Pipeline Actions.
     */
    val readyImports: List<ImportCommand.CreateReadyBook>,

    /**
     * Pipeline Actions.
     */
    val refreshedBooks: List<ImportCommand.RefreshExistingBook>,

    /**
     * Pipeline Actions.
     */
    val failures: List<ImportCommand.RecordFailure>,

    /**
     * Pipeline Actions.
     *
     * Kept separate from readyImports so scan summaries can report these as updates instead of new books.
     */
    val replacementImports: List<ImportCommand.ReplaceExistingBooks> = emptyList()
) {
    /**
     * Statistics Metrics.
     */
    val discoveredCount: Int get() = readyImports.size

    /**
     * UI Presentation Helpers.
     */
    val discoveredNames: List<String> get() = readyImports.map { it.draft.book.title }

    /**
     * UI Presentation Helpers.
     */
    val partialNames: List<String> get() = (readyImports.map { it.draft } + replacementImports.map { it.draft })
        .filter { it.book.status == AudiobookSchema.BookStatus.PARTIAL }
        .map { it.book.title }

    /**
     * UI Presentation Helpers.
     */
    val updateExistingNames: List<String> get() = replacementImports.map { it.draft.book.title }

    /**
     * UI Presentation Helpers.
     */
    val failureNames: List<String> get() = failures.map { "${it.failure.sourceUri.substringAfterLast('/')}: ${it.failure.message}" }

    /**
     * Statistics Metrics.
     */
    val updateExistingCount: Int get() = replacementImports.size

    /**
     * Statistics Metrics.
     */
    val partialNewBookCount: Int get() = (readyImports.map { it.draft } + replacementImports.map { it.draft })
        .count { it.book.status == AudiobookSchema.BookStatus.PARTIAL }

    /**
     * Statistics Metrics.
     */
    val failureCount: Int get() = failures.size
}
