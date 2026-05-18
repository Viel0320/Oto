package com.viel.aplayer.library

import android.content.Context
import androidx.room.withTransaction
import com.viel.aplayer.data.AppDatabase
import com.viel.aplayer.data.AudiobookSchema
import com.viel.aplayer.data.BookEntity
import com.viel.aplayer.data.BookFileEntity
import com.viel.aplayer.data.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

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

    // Compatibility entry for direct single-file additions; manifest imports now go through ImportOrchestrator.
    suspend fun importSingleAudio(
        uri: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        durationMs: Long,
        fileSize: Long,
        lastModified: Long,
        coverPath: String?,
        thumbnailPath: String?,
        backgroundColorArgb: Int?,
        chapters: List<ChapterEntity>,
        existingBookId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val bookId = existingBookId ?: UUID.randomUUID().toString()
        val fileId = UUID.randomUUID().toString()
        val rootId = "default"

        // Book remains logical; the audio file identity is stored below in BookFile.
        val book = BookEntity(
            id = bookId,
            rootId = rootId,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = title,
            author = author.trim(),
            narrator = narrator.trim(),
            description = description,
            year = year,
            totalDurationMs = durationMs,
            totalFileSize = fileSize,
            coverPath = coverPath,
            thumbnailPath = thumbnailPath,
            backgroundColorArgb = backgroundColorArgb,
            status = AudiobookSchema.BookStatus.READY
        )

        // Single-file additions occupy one AUDIO BookFile row.
        val file = BookFileEntity(
            id = fileId,
            bookId = bookId,
            rootId = rootId,
            fileRole = AudiobookSchema.FileRole.AUDIO,
            index = 0,
            uri = uri,
            documentId = "",
            relativePath = "",
            displayName = title,
            durationMs = durationMs,
            fileSize = fileSize,
            lastModified = lastModified,
            status = AudiobookSchema.FileStatus.READY
        )

        bookDao.insertBook(book)
        bookDao.insertBookFiles(listOf(file))

        if (chapters.isNotEmpty()) {
            // Embedded chapters are anchored to the single AUDIO BookFile.
            val fixedChapters = chapters.mapIndexed { index, chapter ->
                val finalDuration = if (chapter.durationMs <= 0) {
                    if (index < chapters.size - 1) {
                        chapters[index + 1].startPositionMs - chapter.startPositionMs
                    } else {
                        durationMs - chapter.startPositionMs
                    }
                } else {
                    chapter.durationMs
                }

                chapter.copy(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = fileId,
                    durationMs = finalDuration.coerceAtLeast(0L)
                )
            }
            chapterDao.insertChapters(fixedChapters)
        }

        bookId
    }
}
