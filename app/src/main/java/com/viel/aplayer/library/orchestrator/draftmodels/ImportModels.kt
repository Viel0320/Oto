package com.viel.aplayer.library.orchestrator.draftmodels

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity

/**
 * Import Pipeline Execution Command (Pipeline Data Model)
 *
 * Represents an execution instruction emitted by the import pipeline to write to the persistent database.
 */
sealed interface ImportCommand {
    /**
     * Create Ready Audiobook (Pipeline Action)
     *
     * Constructs a complete, conflict-free, and playable audiobook draft in the database.
     */
    data class CreateReadyBook(val draft: BookDraft) : ImportCommand

    /**
     * Replace Existing Audiobooks (Ownership Reassignment)
     *
     * Inserts the incoming higher-priority draft, migrates user state from the replaced books,
     * and removes the obsolete ownership rows in the persistence boundary.
     */
    data class ReplaceExistingBooks(
        val draft: BookDraft,
        val replacedBookIds: List<String>
    ) : ImportCommand

    /**
     * Update Existing Audiobook (Pipeline Action)
     *
     * Overwrites metadata, chapters, and track information of a previously recorded audiobook.
     */
    data class UpdateExistingBook(val bookId: String, val draft: BookDraft) : ImportCommand

    // Rescans only refresh file claim visibility; book metadata is written when a book is created.
    /**
     * Refresh Existing Audiobook Tracks (Pipeline Action)
     *
     * Updates visibility and ownership of files associated with a book, bypassing book-level metadata rewrites.
     */
    data class RefreshExistingBook(val bookId: String, val files: List<BookFileEntity>) : ImportCommand

    /**
     * Record Import Failure (Pipeline Action)
     *
     * Logs errors encountered during scanning and parsing processes.
     */
    data class RecordFailure(val failure: ImportFailure) : ImportCommand
}

/**
 * Consolidated Audiobook Draft (Domain Aggregation)
 *
 * Combines the BookEntity database row with its respective BookFileEntity tracks and ChapterEntity chapters.
 */
data class BookDraft(
    /**
     * Core Book Metadata (Domain Entity)
     */
    val book: BookEntity,

    /**
     * Associated Track Files (Domain Entity Collection)
     */
    val files: List<BookFileEntity>,

    // Source semantics live on Book.sourceType; manifest/audio ownership lives in BookFile.
    /**
     * Audiobook Chapters (Domain Entity Collection)
     */
    val chapters: List<ChapterEntity>
)

/**
 * Import Process Failure Details (Diagnostic Model)
 */
data class ImportFailure(
    /**
     * Source URI or VFS Path (Stable Reference)
     */
    val sourceUri: String,

    /**
     * Failure Explanation (User-Readable Description)
     */
    val message: String,

    /**
     * Underlying Exception Trace (Technical Diagnostics)
     */
    val throwableMessage: String? = null
)

/**
 * Integrated Scan Execution Results (Pipeline Output summary)
 */
data class ImportRunResult(
    /**
     * Scan Session Identifier (Stable ID Reference)
     */
    val scanId: String,

    /**
     * Commands for New Audiobooks (Pipeline Actions)
     */
    val readyImports: List<ImportCommand.CreateReadyBook>,

    /**
     * Commands for Refreshing Existing Audiobooks (Pipeline Actions)
     */
    val refreshedBooks: List<ImportCommand.RefreshExistingBook>,

    /**
     * Commands for Scanned Failures (Pipeline Actions)
     */
    val failures: List<ImportCommand.RecordFailure>,

    /**
     * Commands for Ownership Replacements (Pipeline Actions)
     *
     * Kept separate from readyImports so scan summaries can report these as updates instead of new books.
     */
    val replacementImports: List<ImportCommand.ReplaceExistingBooks> = emptyList()
) {
    /**
     * Count of Discovered Books (Statistics Metrics)
     */
    val discoveredCount: Int get() = readyImports.size

    // Scan Summary Names (Completion diagnostics)
    // Stores concrete titles in summaryJson so logs and future lightweight summaries can report what changed.
    /**
     * Discovered Audiobook Title List (UI Presentation Helpers)
     */
    val discoveredNames: List<String> get() = readyImports.map { it.draft.book.title }

    /**
     * Scopes Flagged as Partial New Books (UI Presentation Helpers)
     */
    val partialNames: List<String> get() = (readyImports.map { it.draft } + replacementImports.map { it.draft })
        .filter { it.book.status == AudiobookSchema.BookStatus.PARTIAL }
        .map { it.book.title }

    /**
     * Scopes Flagged for Metadata Updates (UI Presentation Helpers)
     */
    val updateExistingNames: List<String> get() = replacementImports.map { it.draft.book.title }

    /**
     * Diagnostic Failure Message List (UI Presentation Helpers)
     */
    val failureNames: List<String> get() = failures.map { "${it.failure.sourceUri.substringAfterLast('/')}: ${it.failure.message}" }

    /**
     * Count of Metadata Update Actions (Statistics Metrics)
     */
    val updateExistingCount: Int get() = replacementImports.size

    /**
     * Count of Partial New Book Actions (Statistics Metrics)
     */
    val partialNewBookCount: Int get() = (readyImports.map { it.draft } + replacementImports.map { it.draft })
        .count { it.book.status == AudiobookSchema.BookStatus.PARTIAL }

    /**
     * Count of Scan Failures (Statistics Metrics)
     */
    val failureCount: Int get() = failures.size
}
