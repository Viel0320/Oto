package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.PendingScanActionEntity

sealed interface ImportCommand {
    data class CreateReadyBook(val draft: BookDraft) : ImportCommand
    data class UpdateExistingBook(val bookId: String, val draft: BookDraft) : ImportCommand
    // Rescans only refresh file claim visibility; book metadata is written when a book is created.
    data class RefreshExistingBook(val bookId: String, val files: List<BookFileEntity>) : ImportCommand
    data class CreatePendingAction(val action: PendingScanActionEntity) : ImportCommand
    data class RecordFailure(val failure: ImportFailure) : ImportCommand
}

data class BookDraft(
    val book: BookEntity,
    val files: List<BookFileEntity>,
    // Source semantics live on Book.sourceType; manifest/audio ownership lives in BookFile.
    val chapters: List<ChapterEntity>
)

data class ImportFailure(
    val sourceUri: String,
    val message: String,
    val throwableMessage: String? = null
)

data class ImportRunResult(
    val scanId: String,
    val readyImports: List<ImportCommand.CreateReadyBook>,
    val refreshedBooks: List<ImportCommand.RefreshExistingBook>,
    val pendingActions: List<ImportCommand.CreatePendingAction>,
    val failures: List<ImportCommand.RecordFailure>
) {
    val discoveredCount: Int get() = readyImports.size
    // Names are persisted for ScanResultDialog item details.
    val discoveredNames: List<String> get() = readyImports.map { it.draft.book.title }
    val pendingNames: List<String> get() = pendingActions.map { it.action.message }
    val partialNames: List<String> get() = pendingActions
        .filter { it.action.type == AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK }
        .map { it.action.message }
    val updateExistingNames: List<String> get() = pendingActions
        .filter { it.action.type == AudiobookSchema.PendingActionType.UPDATE_EXISTING }
        .map { it.action.message }
    val failureNames: List<String> get() = failures.map { "${it.failure.sourceUri.substringAfterLast('/')}: ${it.failure.message}" }
    // Pending stats back ScanSession and the scan-complete dialog.
    val conflictCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.CONFLICT }
    val updateExistingCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.UPDATE_EXISTING }
    val partialNewBookCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK }
    val failureCount: Int get() = failures.size
}