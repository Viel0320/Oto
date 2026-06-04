package com.viel.aplayer.library.orchestrator.draftmodels

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.PendingScanActionEntity

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
     * Record Pending Scan Action (Pipeline Action)
     *
     * Creates an entry for tasks requiring user intervention (e.g. metadata conflicts or file overlaps).
     */
    data class CreatePendingAction(val action: PendingScanActionEntity) : ImportCommand

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
     * Commands for Pending User Decisions (Pipeline Actions)
     */
    val pendingActions: List<ImportCommand.CreatePendingAction>,

    /**
     * Commands for Scanned Failures (Pipeline Actions)
     */
    val failures: List<ImportCommand.RecordFailure>
) {
    /**
     * Count of Discovered Books (Statistics Metrics)
     */
    val discoveredCount: Int get() = readyImports.size

    // Names are persisted for ScanResultDialog item details.
    /**
     * Discovered Audiobook Title List (UI Presentation Helpers)
     */
    val discoveredNames: List<String> get() = readyImports.map { it.draft.book.title }

    /**
     * Pending Decision Message List (UI Presentation Helpers)
     */
    val pendingNames: List<String> get() = pendingActions.map { it.action.message }

    /**
     * Scopes Flagged as Partial New Books (UI Presentation Helpers)
     */
    val partialNames: List<String> get() = pendingActions
        .filter { it.action.type == AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK }
        .map { it.action.message }

    /**
     * Scopes Flagged for Metadata Updates (UI Presentation Helpers)
     */
    val updateExistingNames: List<String> get() = pendingActions
        .filter { it.action.type == AudiobookSchema.PendingActionType.UPDATE_EXISTING }
        .map { it.action.message }

    /**
     * Diagnostic Failure Message List (UI Presentation Helpers)
     */
    val failureNames: List<String> get() = failures.map { "${it.failure.sourceUri.substringAfterLast('/')}: ${it.failure.message}" }

    // Pending stats back ScanSession and the scan-complete dialog.
    /**
     * Count of Conflict Actions (Statistics Metrics)
     */
    val conflictCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.CONFLICT }

    /**
     * Count of Metadata Update Actions (Statistics Metrics)
     */
    val updateExistingCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.UPDATE_EXISTING }

    /**
     * Count of Partial New Book Actions (Statistics Metrics)
     */
    val partialNewBookCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK }

    /**
     * Count of Scan Failures (Statistics Metrics)
     */
    val failureCount: Int get() = failures.size
}