package com.viel.aplayer.library

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema

// Import persistence boundary for the new scan model.
class BookImporter(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()

    // Prepared scan commands are committed atomically after the run has completed.
    suspend fun applyImportRun(result: ImportRunResult) = withContext(Dispatchers.IO) {
        database.withTransaction {
            result.readyImports.forEach { command ->
                insertReadyDraft(command.draft)
            }
            result.refreshedBooks.forEach { command ->
                refreshExistingClaim(command, result.scanId)
            }
            result.pendingActions.forEach { command ->
                val existing = database.scanSessionDao().getActionByKey(command.action.actionKey)
                val action = existing?.copy(
                    scanSessionId = command.action.scanSessionId,
                    type = command.action.type,
                    bookId = command.action.bookId,
                    payloadJson = command.action.payloadJson,
                    message = command.action.message,
                    lastSeenScanId = command.action.lastSeenScanId
                ) ?: command.action
                // Duplicate action keys refresh the current queue row; no resolved/skipped state is retained.
                database.scanSessionDao().insertAction(action)
            }
        }
    }

    // Repeated manifest scans update visibility markers without adding duplicate pending actions.
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

    // 为每一次改动添加详尽的中文注释：旧单文件直接入库入口已移除，所有持久化导入必须来自 VFS 扫描生成的 ImportRunResult。
}
